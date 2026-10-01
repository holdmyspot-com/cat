package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

/**
 * A logical trial that is not yet adopted.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param key the trial's identity
 * @param instance the assigned instance, which every retry reuses
 * @param identity the measurement conditions that the trial belongs to
 * @param state where the trial is in its life
 * @param verdict the judged outcome, or {@code null} if the trial is not judged
 * @param infrastructureFailures the runs that ended without an outcome and count toward the limit
 * @param observations the runs that ended under conditions that differ from the trial's identity
 */
public record LogicalTrial(TrialKey key, TrialInstance instance, MeasurementIdentity identity, TrialState state,
	Verdict verdict, int infrastructureFailures, int observations)
{
	/**
	 * Creates a trial.
	 *
	 * @param key the trial's identity
	 * @param instance the assigned instance, which every retry reuses
	 * @param identity the measurement conditions that the trial belongs to
	 * @param state where the trial is in its life
	 * @param verdict the judged outcome, or {@code null} if the trial is not judged
	 * @param infrastructureFailures the runs that ended without an outcome and count toward the limit
	 * @param observations the runs that ended under conditions that differ from the trial's identity
	 * @throws NullPointerException if {@code key}, {@code instance}, {@code identity} or {@code state} are null
	 * @throws IllegalArgumentException if a count is negative, the instance's category differs from the key's, or
	 *   {@code verdict} is null exactly when {@code state} is {@link TrialState#JUDGED}
	 */
	public LogicalTrial
	{
		requireThat(key, "key").isNotNull();
		requireThat(instance, "instance").isNotNull();
		requireThat(identity, "identity").isNotNull();
		requireThat(state, "state").isNotNull();
		requireThat(infrastructureFailures, "infrastructureFailures").isNotNegative();
		requireThat(observations, "observations").isNotNegative();
		requireThat(instance.categoryId(), "instance.categoryId()").isEqualTo(key.category(), "key.category()");
		requireThat(verdict != null, "hasVerdict").isEqualTo(state == TrialState.JUDGED, "isJudged");
	}

	/**
	 * Returns a trial that was just assigned.
	 *
	 * @param key the trial's identity
	 * @param instance the assigned instance
	 * @param identity the measurement conditions that the trial belongs to
	 * @return the trial
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the instance's category differs from the key's
	 */
	public static LogicalTrial assigned(TrialKey key, TrialInstance instance, MeasurementIdentity identity)
	{
		return new LogicalTrial(key, instance, identity, TrialState.ASSIGNED, null, 0, 0);
	}

	/**
	 * Returns this trial in another state, without a verdict.
	 *
	 * @param newState {@link TrialState#ASSIGNED} or {@link TrialState#RUNNING}
	 * @return the updated trial
	 */
	LogicalTrial withState(TrialState newState)
	{
		return new LogicalTrial(key, instance, identity, newState, null, infrastructureFailures, observations);
	}
}
