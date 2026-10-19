package com.holdmyspot.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;

import java.util.List;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a failing test case and the evidence that explains its failure as the JSON that the role prompts show.
 */
public final class FailureJson
{
	/**
	 * Prevents instantiation.
	 */
	private FailureJson()
	{
	}

	/**
	 * Writes a failing test case and the evidence that explains its failure.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param failure the test case and rationale to show the session
	 * @param id the scoped identifier of the supplied failure
	 * @param evidenceSection the failing trial's evidence section, or {@code null} if the trial's harness events are
	 *   no longer retained
	 * @return the failing test case with the reason for its failure in {@code rationale}: the judge's rationale, or
	 *   the record of each failed built-in tool; each assertion that it failed with its behavior in
	 *   {@code failed_assertions}; the failure's {@code impact}, or {@code null} if no triage
	 *   decided one; and the trial's {@code evidence_section}, or {@code null} if its events are no longer retained
	 * @throws NullPointerException if any of the arguments other than {@code evidenceSection} are null
	 */
	public static ObjectNode encode(JsonMapper json, Failure failure, String id, String evidenceSection)
	{
		requireThat(json, "json").isNotNull();
		requireThat(failure, "failure").isNotNull();
		requireThat(id, "id").isNotNull();
		ObjectNode node = TestCaseJson.encode(json, failure.testCase());
		node.put("id", id);
		node.put("cell", failure.testCase().cellId());
		node.put("rationale", failure.rationale());
		ArrayNode failedAssertions = node.putArray("failed_assertions");
		for (FailedAssertion failed : failure.failedAssertions())
			failedAssertions.addObject().put("assertion_id", failed.assertionId()).put("behavior", failed.behavior());
		if (failure.triage() == null)
			node.putNull("impact");
		else
			node.put("impact", failure.triage().impact().label());
		ArrayNode responses = node.putArray("responses");
		for (List<String> turn : failure.responses())
		{
			ArrayNode messages = responses.addArray();
			turn.forEach(messages::add);
		}
		// A missing section is written as null rather than left out, so the session reads that the trial's events or
		// file changes are no longer retained instead of assuming that the trial made no tool calls
		node.put("evidence_section", evidenceSection);
		return node;
	}
}
