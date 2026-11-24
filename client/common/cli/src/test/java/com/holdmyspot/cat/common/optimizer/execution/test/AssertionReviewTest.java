package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview.Finding;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview.Kind;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;

import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests validation of a reviewer's answer about a prompt test's assertions.
 */
public final class AssertionReviewTest
{
	private static final MappedAssertion FILE = new MappedAssertion(new Assertion("saved_file", AssertionKind.EFFECT,
		null, OnToolFailure.FAIL, "The agent saved the file.", "Requires meaning.", EvidenceScope.RESPONSE), "save");
	private static final MappedAssertion REPLY = new MappedAssertion(new Assertion("explained", AssertionKind.EFFECT,
		"pattern backup", OnToolFailure.JUDGE, "The assistant explains why it needs a backup.", null,
		EvidenceScope.ACTIONS), "explain");
	private static final MappedAssertion COMMAND = new MappedAssertion(new Assertion("ran_command",
		AssertionKind.EFFECT, null, "The agent ran the backup tool."), "save");
	private static final MappedAssertion MECHANICAL = new MappedAssertion(new Assertion("left_file",
		AssertionKind.EFFECT, "file-exists backup.txt", "The backup file exists."), "save");

	private final AssertionReview review = new AssertionReview(JsonMapper.builder().build());

	/**
	 * Creates the test.
	 */
	public AssertionReviewTest()
	{
	}

	/**
	 * Checks that each finding has the correct effect and that the tool warning carries the CAT built-in's name.
	 */
	@Test
	public void classifiesRefusalAndWarnings()
	{
		// Requirement: R-L16
		// Requirement: R-L19
		// Requirement: R-L20
		String output = output(
			item("saved_file", "response_depends_on_actions", "Creating a file needs project evidence.", null),
			item("explained", "actions_depends_only_on_replies", "Only the explanation is checked.", null),
			item("ran_command", "action_decidable_by_tool", "A tool call identifies the action.", "tool-used backup"));

		List<Finding> findings = review.findings(completed(output), List.of(FILE, REPLY, COMMAND, MECHANICAL)).
			orElseThrow();
		requireThat(findings, "findings").isEqualTo(List.of(
			new Finding("saved_file", Kind.RESPONSE_DEPENDS_ON_ACTIONS, "Creating a file needs project evidence.", null),
			new Finding("explained", Kind.ACTIONS_DEPENDS_ONLY_ON_REPLIES, "Only the explanation is checked.", null),
			new Finding("ran_command", Kind.ACTION_DECIDABLE_BY_TOOL, "A tool call identifies the action.", "tool-used")));
		requireThat(findings.get(0).kind().refuses(), "refusal").isTrue();
		requireThat(findings.get(1).kind().refuses(), "replyOnlyWarning").isFalse();
		requireThat(findings.get(2).kind().refuses(), "builtInToolWarning").isFalse();
	}

	/**
	 * Accepts a complete review that finds nothing when the judge only decides meaning from action evidence.
	 */
	@Test
	public void acceptsReviewWithoutFindings()
	{
		// Requirement: R-L20
		MappedAssertion sufficient = new MappedAssertion(new Assertion("sufficient_brief", AssertionKind.EFFECT,
			null, "The brief in the tool call explains the change."), "briefing");
		MappedAssertion delegated = new MappedAssertion(new Assertion("delegated", AssertionKind.EFFECT,
			"tool-used delegate", "The delegation happened."), "briefing");
		String output = output(item("sufficient_brief", "no_finding", "The tool input needs interpretation.", null));
		SessionResult.Completed result = completed("```json\n" + output + "\n```");
		List<Finding> findings = review.findings(result, List.of(sufficient, delegated)).orElseThrow();
		requireThat(findings, "findings").isEmpty();
	}

	/**
	 * Rejects a report that cannot establish a complete and admissible decision for every judged assertion.
	 *
	 * @param description the reason CAT rejects the output
	 * @param output the reviewer's final response
	 */
	@Test(dataProvider = "invalidReports")
	public void rejectsInvalidReview(String description, String output)
	{
		requireThat(review.findings(completed(output), List.of(COMMAND, MECHANICAL)).isEmpty(), "rejected").
			withContext(description, "reason").isTrue();
	}

	/**
	 * Returns independent ways a reviewer's response can be invalid.
	 *
	 * @return the rejection names and responses
	 */
	@DataProvider
	public Object[][] invalidReports()
	{
		String clean = item("ran_command", "no_finding", "The scope is suitable.", null);
		return new Object[][]
		{
			{"missing judged assertion", output()},
			{"duplicate judged assertion", output(clean, clean)},
			{"unknown assertion", output(clean, item("unknown", "no_finding", "Suitable.", null))},
			{"mechanical assertion", output(clean, item("left_file", "no_finding", "Suitable.", null))},
			{"finding contradicts scope",
				output(item("ran_command", "response_depends_on_actions", "Needs actions.", null))},
			{"finding lacks tool", output(item("ran_command", "action_decidable_by_tool", "Observable.", null))},
			{"warning names unknown tool",
				output(item("ran_command", "action_decidable_by_tool", "Observable.", "unknown-tool"))},
			{"warning names reply tool",
				output(item("ran_command", "action_decidable_by_tool", "Observable.", "pattern backup"))},
			{"unneeded tool", output(item("ran_command", "actions_depends_only_on_replies", "Reply.", "tool-used backup"))},
			{"unknown finding", output(item("ran_command", "other", "Unrecognized.", null))},
			{"blank rationale", output(item("ran_command", "no_finding", "   ", null))},
			{"unstructured response", "No finding."},
			{"extra field", output(clean).replace("]}", "],\"extra\":true}")}
		};
	}

	/**
	 * Formats one reviewer's decision.
	 *
	 * @param assertion the reviewed assertion's identifier
	 * @param kind the reviewer's finding, or no finding
	 * @param rationale what the reviewer based its decision on
	 * @param tool the proposed built-in tool line, or {@code null}
	 * @return the review row as JSON
	 */
	private static String item(String assertion, String kind, String rationale, String tool)
	{
		String toolJson = "null";
		if (tool != null)
			toolJson = "\"" + tool + "\"";
		return """
			{"assertion_id":"%s","kind":"%s","rationale":"%s","tool":%s}
			""".formatted(assertion, kind, rationale, toolJson);
	}

	/**
	 * Packages the reviewer's rows as its output.
	 *
	 * @param rows the review rows as JSON
	 * @return the review as JSON
	 */
	private static String output(String... rows)
	{
		return "{\"reviews\":[" + String.join(",", rows) + "]}";
	}

	/**
	 * Shapes a reviewer's response as a completed session.
	 *
	 * @param output the reviewer's final message
	 * @return the completed session
	 */
	private static SessionResult.Completed completed(String output)
	{
		return new SessionResult.Completed(Path.of("review/transcript.jsonl"), List.of(List.of(output)),
			Path.of("review/effects"), Path.of("review/initial"), null, null, null, List.of(), "review", List.of());
	}
}
