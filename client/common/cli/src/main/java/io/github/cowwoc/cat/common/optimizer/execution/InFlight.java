package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.FailedRequirement;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.contract.CheckResult;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
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
	 * @param instance the trial's instance
	 * @param source the staged source copied at the start of the trial
	 * @param startedAt when the session started
	 * @param concurrency the number of trial sessions running immediately after this one started
	 * @param handle the session
	 */
	record Trial(TrialKey key, TrialInstance instance, Path source, Instant startedAt, int concurrency,
		SessionHandle handle)
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
	 * An author session.
	 *
	 * @param number the reserved launch-order attempt number
	 * @param cell the identifier of the cell (one category under one trial profile) that the attempt targets
	 * @param working the working candidate that the attempt edits
	 * @param overlay the working candidate's overlay
	 * @param copy the new project directory containing the working candidate, prepared for the author session
	 * @param before the copy's inventory before the session
	 * @param brief the exact evidence supplied to this session
	 * @param handle the session
	 */
	record Author(int number, String cell, Candidate working, Overlay overlay, Path copy, FileInventory before,
		AuthorBrief brief, SessionHandle handle) implements InFlight
	{
	}

	/**
	 * A session that writes the key-value combinations of a formal failure group's reproduction round.
	 *
	 * @param attempt the index of the certification attempt whose failure group the values address
	 * @param group the group's 1-based position in the attempt's groups
	 * @param handle the session
	 */
	record Values(int attempt, int group, SessionHandle handle) implements InFlight
	{
	}

	/**
	 * A completed trial session whose deterministic checks passed and that waits for, or is in, its judge session.
	 *
	 * @param key the logical trial
	 * @param instance the trial's instance
	 * @param source the staged source copied at the start of the trial
	 * @param evidence the trial session's result, which every judge attempt receives unchanged
	 * @param evidenceSection the rendered trial activity and file changes, unchanged across judge attempts; empty
	 *   when the frozen judge template does not name it
	 * @param requirements the semantic requirements of the trial's test, which the judge decides
	 * @param results the results of the test's deterministic checks, which the judge receives as evidence
	 * @param attempt the judge attempt, from 1
	 * @param concurrency the number of trial sessions running immediately after the trial's session started
	 */
	record Judging(TrialKey key, TrialInstance instance, Path source, SessionResult.Completed evidence,
		String evidenceSection, List<Requirement> requirements, List<CheckResult> results, int attempt, int concurrency)
	{
		/**
		 * Returns the next judge attempt with the same evidence.
		 *
		 * @return the attempt
		 */
		Judging retry()
		{
			return new Judging(key, instance, source, evidence, evidenceSection, requirements, results, attempt + 1,
				concurrency);
		}
	}

	/**
	 * A failed development trial that waits for, or is in, its triage session, which decides the failure's impact
	 * before the outcome is recorded.
	 *
	 * @param key the logical trial
	 * @param instance the trial's instance
	 * @param evidence the trial session's result, which every triage attempt receives unchanged
	 * @param evidenceSection the rendered trial activity and file changes, unchanged across triage attempts
	 * @param results the results of the test's deterministic checks
	 * @param rationale the judge's rationale or, when no judge ran, the record of the failed deterministic checks
	 * @param failures the requirements that the trial failed, each with its behavior
	 * @param attempt the triage attempt, from 1
	 * @param concurrency the number of trial sessions running immediately after the trial's session started
	 */
	record Triaging(TrialKey key, TrialInstance instance, SessionResult.Completed evidence, String evidenceSection,
		List<CheckResult> results, String rationale, List<FailedRequirement> failures, int attempt, int concurrency)
	{
		/**
		 * Returns the next triage attempt with the same evidence.
		 *
		 * @return the attempt
		 */
		Triaging retry()
		{
			return new Triaging(key, instance, evidence, evidenceSection, results, rationale, failures, attempt + 1,
				concurrency);
		}
	}
}
