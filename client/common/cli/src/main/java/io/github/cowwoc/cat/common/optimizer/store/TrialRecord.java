package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

/**
 * The persisted record of one logical trial: its assignment and, once judged, its outcome.
 *
 * @param key the trial's identity
 * @param instance the assigned instance
 * @param identity the measurement conditions at assignment
 * @param outcome the judged outcome, or null if the trial has none yet
 */
record TrialRecord(TrialKey key, TrialInstance instance, MeasurementIdentity identity,
	CampaignEvent.Complete outcome)
{
	/**
	 * Creates a record.
	 *
	 * @param key the trial's identity
	 * @param instance the assigned instance
	 * @param identity the measurement conditions at assignment
	 * @param outcome the judged outcome, or null if the trial has none yet
	 * @throws NullPointerException if {@code key}, {@code instance} or {@code identity} is null
	 * @throws IllegalArgumentException if {@code outcome} belongs to a different trial
	 */
	TrialRecord
	{
		requireThat(key, "key").isNotNull();
		requireThat(instance, "instance").isNotNull();
		requireThat(identity, "identity").isNotNull();
		if (outcome != null)
			requireThat(outcome.key(), "outcome.key()").isEqualTo(key, "key");
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
		return new TrialRecord(key, instance, identity, judged);
	}
}
