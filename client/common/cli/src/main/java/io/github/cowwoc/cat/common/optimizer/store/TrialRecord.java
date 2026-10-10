package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Allocation;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.InfrastructureAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;

import java.util.ArrayList;
import java.util.List;

/**
 * The persisted record of one logical trial: its assignment, every infrastructure attempt and, once judged, its
 * outcome.
 *
 * @param key the trial's key
 * @param testCase the assigned test case
 * @param identity the measurement conditions at assignment
 * @param allocation the allocation decision that assigned the trial
 * @param infrastructureAttempts every run that ended without an outcome, in the order the runs ended
 * @param outcome the judged outcome, or null if the trial has none yet
 * @param sessions the record of each finished trial session, in completion order
 */
public record TrialRecord(TrialKey key, DrawnTestCase testCase, MeasurementIdentity identity, Allocation allocation,
	List<InfrastructureAttempt> infrastructureAttempts, CampaignEvent.Complete outcome, List<TrialSession> sessions)
{
	/**
	 * Creates a record.
	 *
	 * @param key the trial's key
	 * @param testCase the assigned test case
	 * @param identity the measurement conditions at assignment
	 * @param allocation the allocation decision that assigned the trial
	 * @param infrastructureAttempts every run that ended without an outcome, in the order the runs ended
	 * @param outcome the judged outcome, or null if the trial has none yet
	 * @param sessions the record of each finished trial session, in completion order
	 * @throws NullPointerException if any argument other than {@code outcome} is null
	 * @throws IllegalArgumentException if {@code outcome} belongs to a different trial
	 */
	public TrialRecord
	{
		requireThat(key, "key").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		requireThat(identity, "identity").isNotNull();
		requireThat(allocation, "allocation").isNotNull();
		requireThat(infrastructureAttempts, "infrastructureAttempts").isNotNull();
		requireThat(sessions, "sessions").isNotNull();
		if (outcome != null)
			requireThat(outcome.key(), "outcome.key()").isEqualTo(key, "key");
		infrastructureAttempts = List.copyOf(infrastructureAttempts);
		sessions = List.copyOf(sessions);
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
		return new TrialRecord(key, testCase, identity, allocation, infrastructureAttempts, judged, sessions);
	}

	/**
	 * Appends the record of one finished trial session.
	 *
	 * @param session the session's record
	 * @return the updated record
	 * @throws NullPointerException if {@code session} is null
	 */
	TrialRecord withSession(TrialSession session)
	{
		requireThat(session, "session").isNotNull();
		List<TrialSession> appended = new ArrayList<>(sessions);
		appended.add(session);
		return new TrialRecord(key, testCase, identity, allocation, infrastructureAttempts, outcome, appended);
	}
}
