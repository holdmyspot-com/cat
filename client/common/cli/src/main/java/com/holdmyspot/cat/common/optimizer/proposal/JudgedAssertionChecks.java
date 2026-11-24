package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.RecordedTrial;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TestValueJson;
import com.holdmyspot.cat.common.optimizer.evidence.ActivityFile;
import com.holdmyspot.cat.common.optimizer.execution.AssertionExampleEvidence;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview.Finding;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview.Kind;
import com.holdmyspot.cat.common.optimizer.execution.AssertionVerdict;
import com.holdmyspot.cat.common.optimizer.execution.JudgeProtocol;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.ExampleResult;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.JudgedExample;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.PreparedExample;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;
import com.holdmyspot.cat.common.optimizer.store.CampaignStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the judged checks of mechanically valid prompt tests through the caller's judge callback. The caller assigns
 * record keys to the unchanged inputs of each example or review and schedules new attempts when a check is incomplete.
 */
public final class JudgedAssertionChecks
{
	private static final int FORMAT_VERSION = 1;
	private final JsonMapper json;
	private final JudgeProtocol judge;
	private final AssertionExampleEvidence examples;
	private final AssertionReview reviews;
	private final CampaignStore store;
	private final String campaignId;
	private final JudgeCallback callback;

	/**
	 * Creates the proposal assertion checks.
	 *
	 * @param json the operation's mapper
	 * @param judge the frozen trial judge protocol
	 * @param store the campaign store
	 * @param campaignId the campaign whose held-out records the checks use
	 * @param callback the operation that runs a judge request
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the campaign identifier is blank
	 */
	public JudgedAssertionChecks(JsonMapper json, JudgeProtocol judge, CampaignStore store, String campaignId,
		JudgeCallback callback)
	{
		requireThat(json, "json").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(store, "store").isNotNull();
		requireThat(campaignId, "campaignId").isNotBlank();
		requireThat(callback, "callback").isNotNull();
		this.json = json;
		this.judge = judge;
		this.examples = new AssertionExampleEvidence(json, judge);
		this.reviews = new AssertionReview(json);
		this.store = store;
		this.campaignId = campaignId;
		this.callback = callback;
	}

	/**
	 * Retrieves a complete example judgment or attempts the example once.
	 *
	 * @param key the campaign record key for these example inputs
	 * @param profile the frozen judge profile
	 * @param prepared the example and its selected authored case
	 * @param selected the only assertion being decided
	 * @param project the staged base project
	 * @param scratch a private empty directory for a new attempt
	 * @return the example's result, or empty if an attempt gives no valid verdict
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if the prepared and selected examples differ, or the request is not a
	 *   held-out judge under the frozen profile
	 * @throws IOException if the evidence or stored result cannot be read, the session fails to start, or the result
	 *   cannot be written
	 * @throws InterruptedException if the operation is interrupted
	 */
	public Optional<ExampleResult> example(Sha256 key, JudgeProfile profile, PreparedExample prepared,
		JudgedExample selected, Path project, Path scratch) throws IOException, InterruptedException
	{
		requireThat(key, "key").isNotNull();
		requireThat(profile, "profile").isNotNull();
		requireThat(prepared, "prepared").isNotNull();
		requireThat(selected, "selected").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(scratch, "scratch").isNotNull();
		requireThat(selected.check(), "selected.check()").isEqualTo(prepared.check());
		requireThat(selected.test(), "selected.test()").isEqualTo(prepared.test());
		ObjectNode identity = identity("example", prepared.test().id()).
			put("assertion_id", prepared.check().assertionId()).put("example_id", prepared.check().exampleId());
		ObjectNode stored = store.readAssertionCheck(campaignId, key).orElse(null);
		if (stored != null)
			return Optional.of(readExample(stored, identity, selected));

		PromptTest test = prepared.test();
		DrawnTestCase testCase = new DrawnTestCase(test.id(), test.id(), prepared.testCase(),
			test.expandTurns(prepared.testCase()), test.expectedBehaviour(), "authored", DrawStream.Kind.FORMAL, 0, true);
		boolean actions = selected.assertion().scope() == EvidenceScope.ACTIONS;
		RecordedTrial rendered = prepared.render(json, project, actions, scratch);
		SessionRequest request = examples.request(profile, testCase, rendered, selected.assertion(), scratch);
		SessionResult.Completed completed = run(request, profile).orElse(null);
		if (completed == null)
			return Optional.empty();

		List<AssertionVerdict> verdicts = judge.verdicts(completed, List.of(selected.assertion())).orElse(List.of());
		if (verdicts.size() != 1 || !verdicts.getFirst().assertion().equals(selected.check().assertionId()))
			return Optional.empty();
		AssertionVerdict verdict = verdicts.getFirst();
		ObjectNode record = json.createObjectNode();
		record.set("identity", identity);
		record.set("filled_example", filled(prepared, testCase, rendered, actions, scratch));
		record.putObject("verdict").put("label", EnumNames.lowerCase(verdict.verdict())).
			put("assertion_id", verdict.assertion()).put("behavior", verdict.behavior()).
			put("rationale", verdict.rationale());
		ObjectNode first = store.writeAssertionCheck(campaignId, key, record);
		return Optional.of(readExample(first, identity, selected));
	}

	/**
	 * Retrieves a complete assertion review or attempts the review once.
	 *
	 * @param key the campaign record key for these review inputs
	 * @param profile the frozen judge profile
	 * @param test the mechanically valid test to review
	 * @param requirements contract requirements by ID
	 * @param judgeDirectory a directory that holds no prompt files
	 * @return the review findings, an empty list for a valid review without findings or a mechanical-only test,
	 *   or an empty optional when an attempt gives no complete review
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if the requirements omit one that a judged assertion checks
	 * @throws IOException if the stored result cannot be read, the session fails to start, or the result cannot be
	 *   written
	 * @throws InterruptedException if the operation is interrupted
	 */
	public Optional<List<Finding>> review(Sha256 key, JudgeProfile profile, PromptTest test,
		Map<String, Requirement> requirements, Path judgeDirectory) throws IOException, InterruptedException
	{
		requireThat(key, "key").isNotNull();
		requireThat(test, "test").isNotNull();
		SessionRequest request = reviews.request(judgeDirectory, profile, test.assertions(), requirements).orElse(null);
		if (request == null)
			return Optional.of(List.of());

		ObjectNode identity = identity("review", test.id());
		ObjectNode stored = store.readAssertionCheck(campaignId, key).orElse(null);
		if (stored != null)
			return Optional.of(readReview(stored, identity));

		Optional<List<Finding>> findings = run(request, profile).flatMap(result ->
			reviews.findings(result, test.assertions()));
		if (findings.isEmpty())
			return Optional.empty();
		ObjectNode record = json.createObjectNode();
		record.set("identity", identity);
		ArrayNode rows = record.putArray("findings");
		for (Finding finding : findings.orElseThrow())
		{
			rows.addObject().put("assertion_id", finding.assertionId()).put("kind", EnumNames.lowerCase(finding.kind())).
				put("rationale", finding.rationale()).put("tool", finding.tool());
		}
		ObjectNode first = store.writeAssertionCheck(campaignId, key, record);
		return Optional.of(readReview(first, identity));
	}

	/**
	 * Runs a held-out request and returns a completed result only if all its turns ran.
	 *
	 * @param request the judge request
	 * @param profile the frozen judge profile
	 * @return the completed result, or empty if the request did not finish
	 * @throws IllegalArgumentException if the request does not use the held-out judge profile
	 * @throws IOException if the judge cannot start
	 * @throws InterruptedException if the operation is interrupted
	 */
	private Optional<SessionResult.Completed> run(SessionRequest request, JudgeProfile profile)
		throws IOException, InterruptedException
	{
		requireThat(request.role(), "request.role()").isEqualTo(SessionRole.JUDGE);
		requireThat(request.part(), "request.part()").isEqualTo(Part.HELD_OUT);
		requireThat(request.profile(), "request.profile()").isEqualTo(profile.profile());
		SessionResult result = callback.run(request);
		if (result instanceof SessionResult.Completed completed && !completed.endedEarly() &&
			completed.requestedTurns() == request.turns().size() && completed.responses().size() == request.turns().size())
			return Optional.of(completed);
		return Optional.empty();
	}

	/**
	 * Identifies the kind of check and its test in the versioned held-out record.
	 *
	 * @param kind the kind of check
	 * @param testId the prompt test identifier
	 * @return the record identity
	 */
	private ObjectNode identity(String kind, String testId)
	{
		return json.createObjectNode().put("format_version", FORMAT_VERSION).put("kind", kind).put("test_id", testId);
	}

	/**
	 * Records the case and filled example, retaining the authored snapshots offered to an actions judge.
	 *
	 * @param prepared the example and its authored case
	 * @param testCase the case with expanded turns
	 * @param rendered the example as rendered for the judge
	 * @param actions whether to retain actions evidence
	 * @param scratch the private directory for the example
	 * @return the filled example with no temporary paths
	 * @throws IOException if the activity or an explicit snapshot cannot be read
	 */
	private ObjectNode filled(PreparedExample prepared, DrawnTestCase testCase, RecordedTrial rendered, boolean actions,
		Path scratch) throws IOException
	{
		ObjectNode filled = json.createObjectNode().put("case_position", prepared.casePosition()).
			put("expected_outcome", prepared.testCase().expectedOutcome()).
			put("boundary_class", prepared.testCase().boundaryClass()).
			put("expected_behavior", testCase.expectedBehaviour());
		TestValueJson.write(prepared.testCase().values(), filled.putArray("values"));
		filled.set("turns", json.valueToTree(testCase.turns()));
		filled.set("replies", json.valueToTree(rendered.replies()));
		if (actions)
		{
			Path activity = scratch.resolve("recorded-activity.json");
			ActivityFile.write(json, activity, rendered.activity());
			try (InputStream input = Files.newInputStream(activity))
			{
				filled.set("activity", json.readTree(input));
			}
			String prefix = prepared.check().assertionId() + "/" + prepared.check().exampleId() + "/";
			Set<String> directories = prepared.test().draft().orElseThrow().exampleDirectories();
			if (directories.contains(prefix + "initial"))
				filled.set("initial", snapshot(rendered.initialProject()));
			if (directories.contains(prefix + "project"))
				filled.set("project", snapshot(rendered.effects()));
		}
		return filled;
	}

	/**
	 * Captures an explicit authored snapshot, including empty directories and exact file bytes.
	 *
	 * @param root the rendered snapshot root
	 * @return the paths and contents within the snapshot
	 * @throws IOException if a path in the snapshot cannot be read
	 */
	private ObjectNode snapshot(Path root) throws IOException
	{
		ObjectNode snapshot = json.createObjectNode();
		ArrayNode directories = snapshot.putArray("directories");
		ObjectNode files = snapshot.putObject("files");
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.skip(1).sorted().toList())
			{
				String relative = PortablePaths.text(root.relativize(path));
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					directories.add(relative);
				else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					files.put(relative, Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
				else
					throw new IOException("Invalid path in a rendered assertion example: " + relative);
			}
		}
		return snapshot;
	}

	/**
	 * Reads a stored example judgment using the stored decision format.
	 *
	 * @param record the stored record
	 * @param identity the expected identity
	 * @param selected the assertion and example the judgment belongs to
	 * @return the stored result
	 * @throws IOException if the record does not contain a complete judgment for the example
	 */
	private static ExampleResult readExample(ObjectNode record, ObjectNode identity, JudgedExample selected)
		throws IOException
	{
		try
		{
			requireThat(record.path("identity"), "identity").isEqualTo(identity);
			requireThat(record.path("filled_example").isObject(), "filled_example").isTrue();
			JsonNode row = record.path("verdict");
			AssertionVerdict verdict = new AssertionVerdict(text(row, "assertion_id"), nullableText(row, "behavior"),
				text(row, "rationale"));
			requireThat(text(row, "label"), "label").isEqualTo(EnumNames.lowerCase(verdict.verdict()));
			return new ExampleResult(selected, verdict);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Malformed stored assertion example for " + selected.test().id(), e);
		}
	}

	/**
	 * Reads a stored review using the stored finding format.
	 *
	 * @param record the stored record
	 * @param identity the expected identity
	 * @return the stored findings
	 * @throws IOException if the record does not contain a complete review of the test
	 */
	private static List<Finding> readReview(ObjectNode record, ObjectNode identity) throws IOException
	{
		try
		{
			requireThat(record.path("identity"), "identity").isEqualTo(identity);
			JsonNode rows = record.path("findings");
			requireThat(rows.isArray(), "findings").isTrue();
			List<Finding> findings = new ArrayList<>();
			for (JsonNode row : rows)
			{
				Kind kind = EnumNames.parse(text(row, "kind"), Kind.class);
				findings.add(new Finding(text(row, "assertion_id"), kind, text(row, "rationale"), nullableText(row, "tool")));
			}
			return List.copyOf(findings);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Malformed stored assertion review for " + identity.path("test_id").asString(), e);
		}
	}

	/**
	 * Reads a required string field of a stored result.
	 *
	 * @param row the result object
	 * @param name the field name
	 * @return the string
	 * @throws IllegalArgumentException if the field is missing or is not a string
	 */
	private static String text(JsonNode row, String name)
	{
		JsonNode value = row.path(name);
		requireThat(value.isString(), name).isTrue();
		return value.asString();
	}

	/**
	 * Reads a required field that is either a string or null.
	 *
	 * @param row the result object
	 * @param name the field name
	 * @return the string, or null for an explicit JSON null
	 * @throws IllegalArgumentException if the field is missing or has another type
	 */
	private static String nullableText(JsonNode row, String name)
	{
		if (row.path(name).isNull())
			return null;
		return text(row, name);
	}

	/**
	 * Runs the proposal's judge request.
	 */
	@FunctionalInterface
	public interface JudgeCallback
	{
		/**
		 * Runs the judge and returns its result.
		 *
		 * @param request the judge request
		 * @return the session result
		 * @throws IOException if the session cannot run
		 * @throws InterruptedException if the operation is interrupted
		 */
		SessionResult run(SessionRequest request) throws IOException, InterruptedException;
	}
}
