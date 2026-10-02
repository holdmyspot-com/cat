package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
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
	 * @param startedAt when the session started
	 * @param concurrency the number of trial sessions running immediately after this one started
	 * @param handle the session
	 */
	record Trial(TrialKey key, TrialInstance instance, Instant startedAt, int concurrency, SessionHandle handle)
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
	 * A completed trial session whose deterministic checks passed and that waits for, or is in, its judge session.
	 *
	 * @param key the logical trial
	 * @param instance the trial's instance
	 * @param evidence the trial session's result, which every judge attempt receives unchanged
	 * @param requirements the semantic requirements of the trial's test, which the judge decides
	 * @param results the results of the test's deterministic checks, which the judge receives as evidence
	 * @param attempt the judge attempt, from 1
	 * @param concurrency the number of trial sessions running immediately after the trial's session started
	 */
	record Judging(TrialKey key, TrialInstance instance, SessionResult.Completed evidence,
		List<Requirement> requirements, List<CheckResult> results, int attempt, int concurrency)
	{
		/**
		 * Returns the next judge attempt with the same evidence.
		 *
		 * @return the attempt
		 */
		Judging retry()
		{
			return new Judging(key, instance, evidence, requirements, results, attempt + 1, concurrency);
		}
	}
}
