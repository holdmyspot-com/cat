package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.secrets.EvaluatorSecrets;
import io.github.cowwoc.cat.evaluation.ScenarioJavaChecks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs deterministic checks and validates the receipts that determine their pass or failure outcome.
 */
public final class DeterministicChecks
{
	/**
	 * Supplies coordinator-owned record, evidence, and local-process operations.
	 */
	public interface Access
	{
		/**
		 * Validates a JSON value as an object.
		 *
		 * @param value the value to validate
		 * @param description the value role in malformed-content diagnostics
		 * @return the object value
		 * @throws IOException if the value is not an object
		 */
		ObjectNode object(JsonNode value, String description) throws IOException;

		/**
		 * Reads a record as an object.
		 *
		 * @param source the record location
		 * @param description the record role in malformed-content diagnostics
		 * @return the parsed record
		 * @throws IOException if the record cannot be read or is malformed
		 */
		ObjectNode readObject(Path source, String description) throws IOException;

		/**
		 * Validates a JSON value as an array.
		 *
		 * @param value the value to validate
		 * @param description the value role in malformed-content diagnostics
		 * @param source the record location used in diagnostics
		 * @return the array value
		 * @throws IOException if the value is not an array
		 */
		ArrayNode array(JsonNode value, String description, Path source) throws IOException;

		/**
		 * Reads a required text field.
		 *
		 * @param record the parsed record
		 * @param field the required field name
		 * @param source the record location used in diagnostics
		 * @return the non-blank field value
		 * @throws IOException if the field is unavailable or malformed
		 */
		String text(ObjectNode record, String field, Path source) throws IOException;

		/**
		 * Normalizes a persisted path.
		 *
		 * @param value the persisted path text
		 * @return the normalized path
		 */
		Path path(String value);

		/**
		 * Persists one deterministic-check receipt.
		 *
		 * @param destination the receipt location
		 * @param receipt the replacement receipt
		 * @throws IOException if the receipt cannot be persisted
		 */
		void write(Path destination, ObjectNode receipt) throws IOException;

		/**
		 * Prepares a writable project copy for a deterministic check.
		 *
		 * @param retainedProject the immutable executor project
		 * @param rawResult the retained executor result
		 * @param workspace the initially absent writable copy location
		 * @return the prepared project and raw-result paths
		 * @throws IOException if evidence cannot be prepared
		 */
		ObjectNode prepareWritableEvidence(Path retainedProject, Path rawResult, Path workspace) throws IOException;

		/**
		 * Executes the declared local deterministic command and retains its receipt and diagnostics.
		 *
		 * @param command the explicit command arguments
		 * @param workspace the check workspace
		 * @param output the receipt destination
		 * @param diagnostic the diagnostic destination
		 * @throws IOException if the command cannot complete successfully
		 */
		void runProcess(java.util.List<String> command, Path workspace, Path output, Path diagnostic)
			throws IOException;
	}

	private static volatile Access access;

	/**
	 * Prevents construction of this static check owner.
	 */
	private DeterministicChecks()
	{
	}

	/**
	 * Registers coordinator-owned collaborators.
	 *
	 * @param access the coordinator operations
	 */
	public static void configure(Access access)
	{
		DeterministicChecks.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Runs the declared deterministic check and returns its validated receipt, or {@code null} when none is declared.
	 *
	 * @param json the mapper that creates deterministic-check receipts
	 * @param testCase the testcase contract
	 * @param suitePath the immutable suite location
	 * @param retainedProject the executor project retained as evidence
	 * @param executorResult the executor result retained as evidence
	 * @param workspace the owned check workspace
	 * @param candidate the candidate route, or {@code null} for the package default
	 * @return the validated receipt, or {@code null} when the testcase has no deterministic check
	 * @throws IOException if the declared check or receipt is invalid
	 */
	public static Path run(JsonMapper json, ObjectNode testCase, Path suitePath, Path retainedProject,
		Path executorResult,
		Path workspace, RoutingEvidence.CandidateRoute candidate) throws IOException
	{
		JsonNode declared = testCase.path("deterministic_check");
		Path output = workspace.resolve("deterministic-check.json");
		if (declared.isMissingNode())
		{
			if (!testCase.has("java_check_class"))
				return null;
			ObjectNode result = json.createObjectNode();
			result.putObject("assertions");
			appendJavaChecks(output, result, testCase, retainedProject, executorResult);
			validate(output, testCase, suitePath);
			return output;
		}
		ObjectNode check = access().object(declared, "deterministic check");
		if (SuiteContract.deterministicCheckType(check, suitePath).equals("organic_route_delivery"))
		{
			access().write(output, RoutingEvidence.routeDeliveryCheck(json, testCase, executorResult,
				SuiteContract.organicRouteDeliveryAssertion(testCase, suitePath), candidate));
			appendJavaChecks(output, access().readObject(output, "deterministic check result"), testCase,
				retainedProject, executorResult);
			validate(output, testCase, suitePath);
			return output;
		}
		Path fixture = SuiteContract.requiredDirectory(suitePath, access().text(testCase, "fixture", suitePath),
			"Test fixture", access().text(testCase, "id", suitePath));
		Path artifact = SuiteContract.requiredFile(suitePath, access().text(check, "artifact", suitePath),
			"Deterministic check artifact", access().text(testCase, "id", suitePath));
		Path project = retainedProject;
		Path result = executorResult;
		if (SuiteContract.requiresWritableProject(check, suitePath))
		{
			ObjectNode copied = access().prepareWritableEvidence(retainedProject, executorResult,
				workspace.resolve("deterministic-check-project"));
			project = access().path(access().text(copied, "project", workspace));
			result = access().path(access().text(copied, "raw_result", workspace));
		}
		access().runProcess(CommandArguments.expand(check.path("command"), "deterministic check", Map.of(
			"fixture", fixture.toString(), "check", artifact.toString(), "project", project.toString(),
			"executor_result", result.toString(), "output", output.toString())), workspace, output,
			workspace.resolve("deterministic-check.stderr.txt"));
		appendJavaChecks(output, access().readObject(output, "deterministic check result"), testCase,
			retainedProject, executorResult);
		validate(output, testCase, suitePath);
		return output;
	}

	/**
	 * Indicates whether a retained deterministic receipt contains a failed applicable assertion.
	 *
	 * @param result the retained receipt
	 * @param testCase the testcase contract
	 * @param prompt the immutable testcase prompt
	 * @return whether an owned assertion failed
	 * @throws IOException if the receipt is malformed
	 */
	public static boolean failed(Path result, ObjectNode testCase, Path prompt) throws IOException
	{
		ObjectNode assertions = access().object(access().readObject(result, "deterministic check result").path(
			"assertions"), "deterministic check assertions");
		if (testCase.has("java_check_class") && !testCase.has("java_check_assertions"))
			return reloadedJavaCheckFailed(assertions, testCase, result, prompt);
		for (String assertionId : SuiteContract.deterministicAssertionIds(testCase, prompt))
			if (access().text(access().object(assertions.path(assertionId), "deterministic check assertion " + assertionId),
				"status", result).equals("failed"))
				return true;
		return false;
	}

	/**
	 * Validates exact deterministic assertion coverage before a receipt affects an evaluation outcome.
	 *
	 * @param result the retained receipt
	 * @param testCase the testcase contract
	 * @param suitePath the immutable suite location
	 * @throws IOException if coverage, status, or evidence is invalid
	 */
	public static void validate(Path result, ObjectNode testCase, Path suitePath) throws IOException
	{
		ObjectNode assertions = access().object(access().readObject(result, "deterministic check result").path(
			"assertions"), "deterministic check assertions");
		Set<String> expected = SuiteContract.deterministicAssertionIds(testCase, suitePath);
		Set<String> allowed = new HashSet<>(expected);
		allowed.addAll(SuiteContract.ignoredDeterministicAssertionIds(testCase, suitePath));
		Set<String> actual = new HashSet<>();
		assertions.propertyNames().forEach(actual::add);
		if (!actual.equals(allowed))
			throw new IOException("Deterministic check assertions do not match its declaration: " + result);
		for (String assertionId : expected)
		{
			ObjectNode assertion = access().object(assertions.path(assertionId),
				"deterministic check assertion " + assertionId);
			String status = access().text(assertion, "status", result);
			if (!status.equals("passed") && !status.equals("failed"))
				throw new IOException("Deterministic check has an invalid assertion status: " + assertionId);
			access().text(assertion, "evidence", result);
		}
	}

	/**
	 * Adds evaluator-owned Java assertion outcomes to a deterministic receipt.
	 *
	 * @param output the receipt location
	 * @param receipt the mutable receipt
	 * @param testCase the testcase contract
	 * @param retainedProject the retained executor project
	 * @param executorResult the retained executor result
	 * @throws IOException if Java assertion discovery or receipt persistence fails
	 */
	private static void appendJavaChecks(Path output, ObjectNode receipt, ObjectNode testCase, Path retainedProject,
		Path executorResult) throws IOException
	{
		if (!testCase.has("java_check_class"))
		{
			access().write(output, receipt);
			return;
		}
		ObjectNode assertions = access().object(receipt.path("assertions"), "deterministic check assertions");
		String className = access().text(testCase, "java_check_class", output);
		String scenarioId = access().text(testCase, "id", output);
		Map<String, ScenarioJavaChecks.Result> checks = new TreeMap<>(ScenarioJavaChecks.run(className, scenarioId,
			retainedProject.toString(), executorResult.toString()));
		ArrayNode javaAssertions = testCase.putArray("java_check_assertions");
		ArrayNode scenarioAssertions = access().array(testCase.path("assertions"), "scenario assertions", output);
		Set<String> ids = new HashSet<>();
		for (JsonNode scenarioAssertion : scenarioAssertions)
			ids.add(access().text(access().object(scenarioAssertion, "scenario assertion"), "id", output));
		for (Map.Entry<String, ScenarioJavaChecks.Result> entry : checks.entrySet())
		{
			String assertionId = entry.getKey();
			if (assertions.has(assertionId) || !ids.add(assertionId))
				throw new IOException("Java check conflicts with a deterministic assertion: " + assertionId);
			javaAssertions.add(assertionId);
			scenarioAssertions.addObject().put("id", assertionId).put("evaluation_kind", "deterministic").
				put("instruction", "The conventionally discovered Java check passes.");
			String status = "failed";
			if (entry.getValue().passed())
				status = "passed";
			assertions.putObject(assertionId).put("status", status).put("evidence", entry.getValue().evidence());
		}
		access().write(output, receipt);
	}

	/**
	 * Indicates whether a reloaded receipt records a failed Java assertion that the current scenario does not ignore.
	 *
	 * @param assertions the retained assertion results
	 * @param testCase the reloaded testcase contract
	 * @param result the receipt location
	 * @param prompt the immutable testcase prompt
	 * @return whether an applicable retained Java assertion failed
	 * @throws IOException if a retained Java assertion status is invalid
	 */
	private static boolean reloadedJavaCheckFailed(ObjectNode assertions, ObjectNode testCase, Path result, Path prompt)
		throws IOException
	{
		Set<String> ignored = SuiteContract.ignoredDeterministicAssertionIds(testCase, prompt);
		for (String assertionId : assertions.propertyNames())
		{
			if (ignored.contains(assertionId))
				continue;
			String status = access().text(access().object(assertions.path(assertionId),
				"deterministic check assertion " + assertionId), "status", result);
			if (!status.equals("passed") && !status.equals("failed"))
				throw new IOException("Deterministic check has an invalid assertion status: " + assertionId);
			if (status.equals("failed"))
				return true;
		}
		return false;
	}

	/**
	 * Returns the configured coordinator operations.
	 *
	 * @return the configured coordinator operations
	 */
	private static Access access()
	{
		EvaluatorSecrets.access().initialize();
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure deterministic-check access");
		return result;
	}
}
