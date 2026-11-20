package com.holdmyspot.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;

import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

/**
 * The original launch request and the observer's recorded progress for one attempt of a logical trial.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param key the trial's identity
 * @param attemptOrdinal the trial's one-based launch attempt number
 * @param requestId the UUID published with the original launch request
 * @param observerTurn the latest turn for which the observer recorded a decision, or zero before its first decision
 * @param settledAssertions each settled assertion's first settled turn
 * @param endedEarly whether the observer decided to end the trial
 */
public record TrialRecovery(TrialKey key, int attemptOrdinal, UUID requestId, int observerTurn,
	Map<String, Integer> settledAssertions, boolean endedEarly)
{
	/**
	 * Returns the launch attempt number for a trial. Both infrastructure attempts and runs discarded for measurement
	 * changes precede this launch.
	 *
	 * @param trial the logical trial being launched or resumed
	 * @return the one-based attempt number
	 * @throws NullPointerException if {@code trial} is null
	 */
	public static int attemptOrdinal(LogicalTrial trial)
	{
		return trial.infrastructureAttempts().size() + trial.observations() + 1;
	}

	/**
	 * Creates recovery state for a trial attempt.
	 *
	 * @param key the trial's identity
	 * @param attemptOrdinal the trial's one-based launch attempt number
	 * @param requestId the UUID published with the original launch request
	 * @param observerTurn the latest turn for which the observer recorded a decision, or zero before its first decision
	 * @param settledAssertions each settled assertion's first settled turn
	 * @param endedEarly whether the observer decided to end the trial
	 * @throws NullPointerException if {@code key}, {@code requestId}, {@code settledAssertions} or a map entry is null
	 * @throws IllegalArgumentException if the attempt ordinal is less than 1, the observer turn is negative, an
	 *   assertion name is blank, a settlement turn falls outside the recorded turns, or the observer ended at turn zero
	 */
	public TrialRecovery
	{
		requireThat(key, "key").isNotNull();
		requireThat(attemptOrdinal, "attemptOrdinal").isPositive();
		requireThat(requestId, "requestId").isNotNull();
		requireThat(observerTurn, "observerTurn").isNotNegative();
		settledAssertions = Map.copyOf(settledAssertions);
		for (Entry<String, Integer> entry : settledAssertions.entrySet())
		{
			requireThat(entry.getKey(), "assertion").isNotBlank();
			requireThat(entry.getValue().intValue(), "settledTurn").isPositive().
				isLessThanOrEqualTo(observerTurn, "observerTurn");
		}
		if (endedEarly)
			requireThat(observerTurn, "observerTurn").isPositive();
	}

	/**
	 * Checks that the broker's retained turn count fits this trial's assignment and recorded observer decision.
	 * At most one newly completed turn can await the observer. An ended trial cannot have any subsequent turns.
	 *
	 * @param trial the persisted trial assignment
	 * @param completed the number of completed retained turns
	 * @return whether the assignment and observer permit claiming the retained prefix
	 * @throws NullPointerException if {@code trial} is null
	 */
	public boolean canResumePrefix(LogicalTrial trial, int completed)
	{
		return completed <= trial.testCase().turns().size() && completed >= observerTurn &&
			completed - observerTurn <= 1 && (!endedEarly || completed == observerTurn);
	}

	/**
	 * Checks a record that would replace the current one. A new launch starts with a fresh request and no observer
	 * decision. The current launch can append only its next decision.
	 *
	 * @param previous the current record for the same trial, or null if no request has been recorded
	 * @param next the replacement record
	 * @throws IllegalStateException if the replacement changes a recorded decision or original request, names an
	 *   earlier attempt, or begins a new attempt with observer progress
	 */
	static void requireSuccessor(TrialRecovery previous, TrialRecovery next)
	{
		if (previous == null || next.attemptOrdinal > previous.attemptOrdinal)
		{
			if (next.observerTurn != 0 || previous != null && next.requestId.equals(previous.requestId))
				throw new IllegalStateException("A new trial attempt requires a fresh request before its first turn");
			return;
		}
		if (next.attemptOrdinal < previous.attemptOrdinal)
			throw new IllegalStateException("A newer trial attempt is already recorded for " + next.key);
		if (!next.requestId.equals(previous.requestId))
			throw new IllegalStateException("Trial " + next.key + " already has an original request for attempt " +
				next.attemptOrdinal);
		if (previous.endedEarly)
		{
			throw new IllegalStateException("The observer already ended trial " + next.key + " at turn " +
				previous.observerTurn);
		}
		if (next.observerTurn != previous.observerTurn + 1)
		{
			throw new IllegalStateException("The next observer decision for trial " + next.key +
				" must be for turn " + (previous.observerTurn + 1));
		}
		if (!next.settledAssertions.entrySet().containsAll(previous.settledAssertions.entrySet()))
		{
			throw new IllegalStateException("Cannot remove or change a recorded assertion settlement for trial " +
				next.key);
		}
		for (Entry<String, Integer> entry : next.settledAssertions.entrySet())
		{
			if (!previous.settledAssertions.containsKey(entry.getKey()) && entry.getValue() != next.observerTurn)
			{
				throw new IllegalStateException("Trial " + next.key + " first settled " + entry.getKey() +
					" at turn " + next.observerTurn + ", but the new decision records turn " + entry.getValue());
			}
		}
	}
}
