package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;

import java.nio.file.Path;
import java.time.Instant;

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
	 * @param concurrency the number of trial sessions that ran once this one started
	 * @param handle the session
	 */
	record Trial(TrialKey key, TrialInstance instance, Instant startedAt, int concurrency, SessionHandle handle)
		implements InFlight
	{
	}

	/**
	 * A judge session.
	 *
	 * @param judging the trial that the judge judges
	 * @param handle the session
	 */
	record Judge(Judging judging, SessionHandle handle) implements InFlight
	{
	}

	/**
	 * An author session.
	 *
	 * @param category the category that the attempt targets
	 * @param working the working candidate that the attempt edits
	 * @param overlay the working candidate's overlay
	 * @param copy the author's copy of the working candidate
	 * @param before the copy's inventory before the session
	 * @param handle the session
	 */
	record Author(String category, Candidate working, Overlay overlay, Path copy, FileInventory before,
		SessionHandle handle) implements InFlight
	{
	}

	/**
	 * A completed trial session that waits for, or is in, its judge session.
	 *
	 * @param key the logical trial
	 * @param instance the trial's instance
	 * @param evidence the trial session's result, which every judge attempt receives unchanged
	 * @param attempt the judge attempt, from 1
	 * @param concurrency the number of trial sessions that ran once the trial's session started
	 */
	record Judging(TrialKey key, TrialInstance instance, SessionResult.Completed evidence, int attempt,
		int concurrency)
	{
		/**
		 * Returns the next judge attempt with the same evidence.
		 *
		 * @return the attempt
		 */
		Judging retry()
		{
			return new Judging(key, instance, evidence, attempt + 1, concurrency);
		}
	}
}
