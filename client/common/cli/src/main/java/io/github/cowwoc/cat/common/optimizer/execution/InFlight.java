package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.FailedAssertion;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.contract.BuiltInToolResult;
import io.github.cowwoc.cat.common.optimizer.contract.JudgedAssertion;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * A nested session that a {@link Driver} started and that has not ended yet.
 */
sealed interface InFlight
{
	/**
	 * Returns the session.
	 *
	 * @return the session's handle
	 */
	SessionHandle handle();

	/**
	 * A trial session.
	 *
	 * @param key the logical trial
	 * @param testCase the trial's test case
	 * @param startedAt when the session started
	 * @param concurrency the number of trial sessions running immediately after this one started
	 * @param handle the session
	 */
	record Trial(TrialKey key, DrawnTestCase testCase, Instant startedAt, int concurrency, SessionHandle handle)
		implements InFlight
	{
	}

	/**
	 * A judge session.
	 *
	 * @param judging the completed trial whose evidence the judge evaluates
	 * @param handle the session
	 */
	record Judge(Judging judging, SessionHandle handle) implements InFlight
	{
	}

	/**
	 * A triage session.
	 *
	 * @param triaging the failed development trial whose impact the triage decides
	 * @param handle the session
	 */
	record Triage(Triaging triaging, SessionHandle handle) implements InFlight
	{
	}

	/**
	 * A prompt author session.
	 *
	 * @param number the reserved launch-order attempt number
	 * @param lineage the number of the lineage whose contract the session received
	 * @param cell the identifier of the cell (one prompt test under one trial profile) that the attempt targets
	 * @param working the working candidate that the attempt edits
	 * @param overlay the working candidate's overlay
	 * @param copy the new project directory containing the working candidate, prepared for the prompt author session
	 * @param before the copy's inventory before the session
	 * @param brief the exact evidence supplied to this session
	 * @param handle the session
	 */
	record PromptAuthor(int number, int lineage, String cell, Candidate working, Overlay overlay, Path copy,
		FileInventory before, PromptAuthorBrief brief, SessionHandle handle) implements InFlight
	{
	}

	/**
	 * A session that writes the test cases of a formal failure group's reproduction round.
	 *
	 * @param attempt the index of the certification attempt whose failure group the values address
	 * @param group the group's 1-based position in the attempt's groups
	 * @param handle the session
	 */
	record Values(int attempt, int group, SessionHandle handle) implements InFlight
	{
	}

	/**
	 * A completed trial session whose built-in tools passed and that waits for, or is in, its judge session.
	 *
	 * @param key the logical trial
	 * @param testCase the trial's test case
	 * @param evidence the trial session's result, which every judge attempt receives unchanged
	 * @param evidenceSection the rendered trial activity and file changes, unchanged across judge attempts; empty
	 *   when the frozen judge template does not name it
	 * @param assertions the assertions of the trial's test that the judge decides
	 * @param results the results of the test's built-in tools, which the judge receives as evidence
	 * @param attempt the judge attempt, from 1
	 * @param concurrency the number of trial sessions running immediately after the trial's session started
	 */
	record Judging(TrialKey key, DrawnTestCase testCase, SessionResult.Completed evidence,
		String evidenceSection, List<JudgedAssertion> assertions, List<BuiltInToolResult> results, int attempt,
		int concurrency)
	{
		/**
		 * Returns the next judge attempt with the same evidence.
		 *
		 * @return the attempt
		 */
		Judging retry()
		{
			return new Judging(key, testCase, evidence, evidenceSection, assertions, results, attempt + 1,
				concurrency);
		}
	}

	/**
	 * A failed development trial that waits for, or is in, its triage session, which decides the failure's impact
	 * before the outcome is recorded.
	 *
	 * @param key the logical trial
	 * @param testCase the trial's test case
	 * @param evidence the trial session's result, which every triage attempt receives unchanged
	 * @param evidenceSection the rendered trial activity and file changes, unchanged across triage attempts
	 * @param results the results of the test's built-in tools
	 * @param rationale the judge's rationale or, when a built-in tool failed, the record of the failed tools
	 * @param failures the assertions that the trial failed, each with its behavior
	 * @param attempt the triage attempt, from 1
	 * @param concurrency the number of trial sessions running immediately after the trial's session started
	 */
	record Triaging(TrialKey key, DrawnTestCase testCase, SessionResult.Completed evidence, String evidenceSection,
		List<BuiltInToolResult> results, String rationale, List<FailedAssertion> failures, int attempt,
		int concurrency)
	{
		/**
		 * Returns the next triage attempt with the same evidence.
		 *
		 * @return the attempt
		 */
		Triaging retry()
		{
			return new Triaging(key, testCase, evidence, evidenceSection, results, rationale, failures, attempt + 1,
				concurrency);
		}
	}
}
