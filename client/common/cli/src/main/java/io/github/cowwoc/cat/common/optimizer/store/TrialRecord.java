package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Allocation;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.InfrastructureAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

import java.util.List;

/**
 * The persisted record of one logical trial: its assignment, every infrastructure attempt and, once judged, its
 * outcome.
 *
 * @param key the trial's key
 * @param instance the assigned instance
 * @param identity the measurement conditions at assignment
 * @param allocation the allocation decision that assigned the trial
 * @param infrastructureAttempts every run that ended without an outcome, in the order the runs ended
 * @param outcome the judged outcome, or null if the trial has none yet
 */
record TrialRecord(TrialKey key, TrialInstance instance, MeasurementIdentity identity, Allocation allocation,
	List<InfrastructureAttempt> infrastructureAttempts, CampaignEvent.Complete outcome)
{
	/**
	 * Creates a record.
	 *
	 * @param key the trial's key
	 * @param instance the assigned instance
	 * @param identity the measurement conditions at assignment
	 * @param allocation the allocation decision that assigned the trial
	 * @param infrastructureAttempts every run that ended without an outcome, in the order the runs ended
	 * @param outcome the judged outcome, or null if the trial has none yet
	 * @throws NullPointerException if any argument other than {@code outcome} is null
	 * @throws IllegalArgumentException if {@code outcome} belongs to a different trial
	 */
	TrialRecord
	{
		requireThat(key, "key").isNotNull();
		requireThat(instance, "instance").isNotNull();
		requireThat(identity, "identity").isNotNull();
		requireThat(allocation, "allocation").isNotNull();
		requireThat(infrastructureAttempts, "infrastructureAttempts").isNotNull();
		if (outcome != null)
			requireThat(outcome.key(), "outcome.key()").isEqualTo(key, "key");
		infrastructureAttempts = List.copyOf(infrastructureAttempts);
	}

	/**
	 * Returns this record with an outcome.
	 *
	 * @param judged the outcome
	 * @return the updated record
	 * @throws NullPointerException if {@code judged} is null
	 * @throws IllegalArgumentException if {@code judged} belongs to a different trial
	 */
	TrialRecord withOutcome(CampaignEvent.Complete judged)
	{
		requireThat(judged, "judged").isNotNull();
		return new TrialRecord(key, instance, identity, allocation, infrastructureAttempts, judged);
	}
}
