package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

import java.util.List;

/**
 * One assigned test instance whose result is not yet included in campaign evidence. Retries run the same instance
 * under the same key rather than count as additional trials.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param key the trial's identity
 * @param instance the assigned instance, which every retry reuses
 * @param identity the measurement conditions that the trial belongs to
 * @param allocation the allocation decision that assigned the trial
 * @param state whether the trial is assigned, running or awaiting inclusion in evidence
 * @param verdict the judged outcome, or {@code null} if the trial is not judged
 * @param rationale the judge's explanation of {@code verdict} or, when no judge ran, the record of the trial's
 *   deterministic checks, or {@code null} if the trial is not judged
 * @param infrastructureFailures the runs that ended without an outcome and count toward
 *   {@link Campaign#INFRASTRUCTURE_FAILURE_LIMIT}; a {@link Choice#RETRY} resets it to 0
 * @param infrastructureAttempts every run that ended without an outcome, in the order the runs ended
 * @param observations the runs that ended under conditions that differ from the trial's identity
 */
public record LogicalTrial(TrialKey key, TrialInstance instance, MeasurementIdentity identity, Allocation allocation,
	TrialState state, Verdict verdict, String rationale, int infrastructureFailures,
	List<InfrastructureAttempt> infrastructureAttempts, int observations)
{
	/**
	 * Creates a trial.
	 *
	 * @param key the trial's identity
	 * @param instance the assigned instance, which every retry reuses
	 * @param identity the measurement conditions that the trial belongs to
	 * @param allocation the allocation decision that assigned the trial
	 * @param state whether the trial is assigned, running or awaiting inclusion in evidence
	 * @param verdict the judged outcome, or {@code null} if the trial is not judged
	 * @param rationale the judge's explanation of {@code verdict} or, when no judge ran, the record of the trial's
	 *   deterministic checks, or {@code null} if the trial is not judged
	 * @param infrastructureFailures the runs that ended without an outcome and count toward
	 *   {@link Campaign#INFRASTRUCTURE_FAILURE_LIMIT}
	 * @param infrastructureAttempts every run that ended without an outcome, in the order the runs ended
	 * @param observations the runs that ended under conditions that differ from the trial's identity
	 * @throws NullPointerException if {@code key}, {@code instance}, {@code identity}, {@code allocation},
	 *   {@code state} or {@code infrastructureAttempts} are null
	 * @throws IllegalArgumentException if a count is negative, the instance's cell differs from the key's, the
	 *   allocation rule does not assign the key's purpose, a verdict exists without the {@link TrialState#JUDGED} state
	 *   or that state lacks a verdict, only one of {@code rationale} and {@code verdict} has a value, or
	 *   {@code infrastructureFailures} exceeds the number of {@link RunFailure#INFRASTRUCTURE} attempts
	 */
	public LogicalTrial
	{
		requireThat(key, "key").isNotNull();
		requireThat(instance, "instance").isNotNull();
		requireThat(identity, "identity").isNotNull();
		requireThat(allocation, "allocation").isNotNull();
		requireThat(state, "state").isNotNull();
		requireThat(infrastructureFailures, "infrastructureFailures").isNotNegative();
		requireThat(infrastructureAttempts, "infrastructureAttempts").isNotNull();
		requireThat(observations, "observations").isNotNegative();
		requireThat(instance.cellId(), "instance.cellId()").isEqualTo(key.category(), "key.category()");
		requireThat(allocation.rule().assigns(key.purpose()), "allocation.rule().assigns(key.purpose())").isTrue();
		requireThat(verdict != null, "hasVerdict").isEqualTo(state == TrialState.JUDGED, "isJudged");
		requireThat(rationale != null, "hasRationale").isEqualTo(verdict != null, "hasVerdict");
		infrastructureAttempts = List.copyOf(infrastructureAttempts);
		long infrastructure = infrastructureAttempts.stream().
			filter(attempt -> attempt.failure() == RunFailure.INFRASTRUCTURE).
			count();
		requireThat((long) infrastructureFailures, "infrastructureFailures").
			isLessThanOrEqualTo(infrastructure, "infrastructureAttempts(INFRASTRUCTURE)");
	}

	/**
	 * Returns a trial that was just assigned.
	 *
	 * @param key the trial's identity
	 * @param instance the assigned instance
	 * @param identity the measurement conditions that the trial belongs to
	 * @param allocation the allocation decision that assigned the trial
	 * @return the trial
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the instance's cell differs from the key's, or the allocation rule does
	 *   not assign the key's purpose
	 */
	public static LogicalTrial assigned(TrialKey key, TrialInstance instance, MeasurementIdentity identity,
		Allocation allocation)
	{
		return new LogicalTrial(key, instance, identity, allocation, TrialState.ASSIGNED, null, null, 0, List.of(), 0);
	}

	/**
	 * Returns this trial in another state, without a verdict.
	 *
	 * @param newState {@link TrialState#ASSIGNED} or {@link TrialState#RUNNING}
	 * @return the updated trial
	 * @throws NullPointerException if {@code newState} is null
	 * @throws IllegalArgumentException if {@code newState} is {@link TrialState#JUDGED}
	 */
	LogicalTrial withState(TrialState newState)
	{
		return new LogicalTrial(key, instance, identity, allocation, newState, null, null, infrastructureFailures,
			infrastructureAttempts, observations);
	}
}
