package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.InvocationDirectory;
import io.github.cowwoc.cat.common.ProcessExecution;
import io.github.cowwoc.cat.common.PromptEvaluation;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import static io.github.cowwoc.cat.common.evaluation.CommandArguments.expand;

import static io.github.cowwoc.cat.common.PromptEvaluation.MAXIMUM_PREFLIGHT_RECEIPTS;
import static io.github.cowwoc.cat.common.PromptEvaluation.array;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.deterministicAssertionIds;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.deterministicCheckType;
import static io.github.cowwoc.cat.common.PromptEvaluation.object;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.organicRouteDeliveryAssertion;
import static io.github.cowwoc.cat.common.PromptEvaluation.path;
import static io.github.cowwoc.cat.common.PromptEvaluation.read;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.requiredDirectory;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.requiredFile;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.requiresWritableProject;
import static io.github.cowwoc.cat.common.evaluation.RoutingEvidence.routeDeliveryCheck;
import static io.github.cowwoc.cat.common.PromptEvaluation.suiteFingerprint;
import static io.github.cowwoc.cat.common.PromptEvaluation.text;
import static io.github.cowwoc.cat.common.evaluation.DeterministicChecks.validate;
import static io.github.cowwoc.cat.common.PromptEvaluation.workRootForTemporaryResources;
import static io.github.cowwoc.cat.common.PromptEvaluation.write;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proves a suite's deterministic controls before an evaluator builds a runner or starts model work.
 * <p>
 * This owner reuses a receipt only when the immutable suite fingerprint is unchanged. It retains control results in an
 * operation-owned workspace and removes that workspace after publishing the reusable receipt.
 */
public final class Preflight
{
	/**
	 * Prevents construction of this static preflight owner.
	 */
	private Preflight()
	{
	}

	/**
	 * Runs a deterministic suite preflight once for each immutable suite fingerprint.
	 *
	 * @param json the mapper that creates control receipts and copied-evidence descriptions
	 * @param temporaryRoot the CAT work temp directory that owns reusable transient receipts
	 * @param suitePath the version-controlled test suite
	 * @throws IOException if preflight cannot establish that the deterministic controls work
	 */
	public static void preflightCached(JsonMapper json, Path temporaryRoot, Path suitePath) throws IOException
	{
		String fingerprint = suiteFingerprint(suitePath);
		Path cache = workRootForTemporaryResources(temporaryRoot).resolve(WorkDirectories.PREFLIGHT);
		Path receipt = cache.resolve(fingerprint + ".json");
		if (Files.isRegularFile(receipt, NOFOLLOW_LINKS))
		{
			ObjectNode cached = object(read(receipt), "preflight receipt");
			if (cached.path("suite_sha256").asString().equals(fingerprint) &&
				cached.path("status").asString().equals("passed"))
			{
				return;
			}
		}

		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workspace = cleanup.add(InvocationDirectory.create(temporaryRoot, "prompt-evaluation-preflight"));
			preflight(json, suitePath, workspace);
			ObjectNode result = json.createObjectNode();
			result.put("suite_sha256", fingerprint);
			result.put("status", "passed");
			result.put("completed_at_epoch_millis", System.currentTimeMillis());
			write(receipt, result);
			pruneReceipts(cache, receipt);
		}
	}

	/**
	 * Runs each declared deterministic-check good and bad fixture before a harness build or model turn starts.
	 *
	 * @param json the mapper that creates control receipts and copied-evidence descriptions
	 * @param suitePath the immutable scenario-suite root
	 * @param workspace the empty workspace that owns transient check results
	 * @throws IOException if the suite is invalid or a check disagrees with one of its declared control fixtures
	 */
	public static void preflight(JsonMapper json, Path suitePath, Path workspace) throws IOException
	{
		suitePath = suitePath.toAbsolutePath().normalize();
		workspace = workspace.toAbsolutePath().normalize();
		SuiteContract.validate(suitePath);
		if (Files.exists(workspace) && !Files.isDirectory(workspace))
			throw new IOException("Preflight workspace is not a directory: " + workspace);
		Files.createDirectories(workspace);

		ObjectNode suite = SuiteContract.read(suitePath);
		for (JsonNode node : array(suite.path("cases"), "cases", suitePath))
		{
			ObjectNode testCase = object(node, "test case");
			JsonNode declared = testCase.path("deterministic_check");
			if (!declared.isMissingNode())
				runCheckSelfTests(json, suitePath, testCase, object(declared, "deterministic check"), workspace);
		}
	}

	/**
	 * Keeps reusable preflight receipts bounded while retaining the receipt required by the active operation.
	 *
	 * @param cache the evaluator-owned preflight receipt directory
	 * @param currentReceipt the receipt required by the active operation
	 * @throws IOException if an old receipt cannot be removed
	 */
	private static void pruneReceipts(Path cache, Path currentReceipt) throws IOException
	{
		try (Stream<Path> entries = Files.list(cache))
		{
			List<Path> receipts = entries.filter(path -> path.getFileName().toString().endsWith(".json")).
				filter(path -> !path.equals(currentReceipt)).sorted(Comparator.comparingLong(path ->
				{
					try
					{
						return Files.getLastModifiedTime(path).toMillis();
					}
					catch (IOException e)
					{
						throw new IllegalStateException(e);
					}
				})).toList();
			for (int index = 0; index < Math.max(0, receipts.size() - MAXIMUM_PREFLIGHT_RECEIPTS + 1); ++index)
				Files.deleteIfExists(receipts.get(index));
		}
	}

	/**
	 * Runs the control fixtures that prove one deterministic check rejects the difference it claims to detect.
	 *
	 * @param json the mapper that creates control receipts and copied-evidence descriptions
	 * @param suitePath the immutable testcase-suite manifest
	 * @param testCase the testcase that owns the check
	 * @param check the validated check declaration
	 * @param workspace the owned transient preflight workspace
	 * @throws IOException if a control fixture does not produce its declared assertion states
	 */
	private static void runCheckSelfTests(JsonMapper json, Path suitePath, ObjectNode testCase, ObjectNode check,
		Path workspace)
		throws IOException
	{
		JsonNode declared = check.path("self_tests");
		if (declared.isMissingNode())
			return;
		String caseId = text(testCase, "id", suitePath);
		boolean builtInRouteDelivery = deterministicCheckType(check, suitePath).equals("organic_route_delivery");
		Path artifact = null;
		if (!builtInRouteDelivery)
			artifact = requiredFile(suitePath, text(check, "artifact", suitePath), "Deterministic check artifact",
				caseId);
		int index = 0;
		for (JsonNode node : array(declared, "deterministic check self_tests", suitePath))
		{
			ObjectNode control = object(node, "deterministic check control fixture");
			Path project = requiredDirectory(suitePath, text(control, "project", suitePath),
				"Check control project", caseId);
			Path events = requiredFile(suitePath, text(control, "evidence_events", suitePath),
				"Check control events", caseId);
			Path executorResult = materializeControlEvents(json, events,
				workspace.resolve(caseId + "-check-control-" + index + "-executor-output.jsonl"));
			Path output = workspace.resolve(caseId + "-check-control-" + index + ".json");
			Path diagnostic = workspace.resolve(caseId + "-check-control-" + index + ".stderr.txt");
			Path checkProject = project;
			Path checkExecutorResult = executorResult;
			if (requiresWritableProject(check, suitePath))
			{
				ObjectNode copiedEvidence = ValidatorEvidence.describe(json, project, executorResult,
					workspace.resolve(caseId + "-check-control-" + index + "-project"), Set.of(".git", "node_modules"),
					PromptEvaluation.VALIDATOR_FINAL_RESPONSE);
				checkProject = path(text(copiedEvidence, "project", workspace));
				checkExecutorResult = path(text(copiedEvidence, "raw_result", workspace));
			}
			index += 1;
			if (builtInRouteDelivery)
			{
				write(output, routeDeliveryCheck(json, testCase, checkExecutorResult,
					organicRouteDeliveryAssertion(testCase, suitePath)));
			}
			else
			{
				Map<String, String> values = new HashMap<>();
				values.put("fixture", project.toString());
				values.put("check", artifact.toString());
				values.put("project", checkProject.toString());
				values.put("output", output.toString());
				if (checkExecutorResult != null)
					values.put("executor_result", checkExecutorResult.toString());
				List<String> command = expand(check.path("command"), "deterministic check", values);
				ProcessExecution.runStructured(command, workspace, "", output, diagnostic);
			}
			validate(output, testCase, suitePath);
			ObjectNode actual = object(object(read(output), "deterministic check result").path("assertions"),
				"deterministic check assertions");
			ObjectNode expected = object(control.path("expected_assertions"), "deterministic check control expectations");
			for (String assertionId : deterministicAssertionIds(testCase, suitePath))
			{
				String expectedStatus = text(expected, assertionId, suitePath);
				String actualStatus = text(object(actual.path(assertionId),
					"deterministic check assertion " + assertionId), "status", output);
				if (!actualStatus.equals(expectedStatus))
				{
					throw new IOException("Deterministic check rejected its " + controlName(expectedStatus) +
						" control fixture for " + caseId + ": expected " + expectedStatus + " but observed " +
						actualStatus + " at " + project);
				}
			}
		}
	}

	/**
	 * Materializes evaluator-owned JSONL evidence from a scenario-authored JSON event array.
	 *
	 * @param json the preflight operation's JSON mapper
	 * @param eventSource the scenario-owned event array
	 * @param receipt the preflight-owned JSONL receipt
	 * @return the materialized receipt
	 * @throws IOException if the source is not an array of event objects or the receipt cannot be written
	 */
	private static Path materializeControlEvents(JsonMapper json, Path eventSource, Path receipt) throws IOException
	{
		JsonNode events = read(eventSource);
		if (!events.isArray())
			throw new IOException("Check control events must be a JSON array: " + eventSource);
		StringBuilder contents = new StringBuilder();
		for (JsonNode event : events)
		{
			if (!event.isObject())
				throw new IOException("Check control events must contain JSON objects: " + eventSource);
			contents.append(json.writeValueAsString(event)).append(System.lineSeparator());
		}
		Files.writeString(receipt, contents);
		return receipt;
	}

	/**
	 * Returns the reader-visible control-fixture label for an expected assertion status.
	 *
	 * @param expectedStatus the expected deterministic assertion status
	 * @return {@code known-good} or {@code known-bad}
	 */
	private static String controlName(String expectedStatus)
	{
		if (expectedStatus.equals("passed"))
			return "known-good";
		return "known-bad";
	}
}
