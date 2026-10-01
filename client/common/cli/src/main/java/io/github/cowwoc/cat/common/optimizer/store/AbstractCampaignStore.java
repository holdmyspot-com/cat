package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignHeader;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The record rules that every {@link CampaignStore} shares, over storage primitives that each implementation provides.
 */
abstract class AbstractCampaignStore implements CampaignStore
{
	/**
	 * Creates a store.
	 */
	AbstractCampaignStore()
	{
	}

	@Override
	public final Optional<Campaign> load(String campaignId) throws IOException
	{
		requireCampaignId(campaignId);
		return readSnapshot(campaignId);
	}

	@Override
	public final void writeAssignment(String campaignId, LogicalTrial trial) throws IOException
	{
		requireCampaignId(campaignId);
		requireThat(trial, "trial").isNotNull();
		requireThat(trial.state(), "trial.state()").isEqualTo(TrialState.ASSIGNED);
		Optional<TrialRecord> existing = readRecord(campaignId, trial.key());
		if (existing.isPresent() && existing.get().outcome() != null)
		{
			throw new IllegalStateException("Trial " + trial.key() + " already has an outcome: " +
				existing.get().outcome());
		}
		// A trial that returns to ASSIGNED after a run without an outcome is written again before its next session,
		// which adds that run's infrastructure attempt to the record
		writeRecord(campaignId, new TrialRecord(trial.key(), trial.instance(), trial.identity(), trial.allocation(),
			trial.infrastructureAttempts(), null));
	}

	@Override
	public final void writeOutcome(String campaignId, CampaignEvent.Complete outcome) throws IOException
	{
		requireCampaignId(campaignId);
		requireThat(outcome, "outcome").isNotNull();
		Optional<TrialRecord> existing = readRecord(campaignId, outcome.key());
		if (existing.isEmpty())
			throw new IllegalStateException("Trial " + outcome.key() + " has no assignment record");
		TrialRecord record = existing.get();
		if (record.outcome() != null)
		{
			if (record.outcome().equals(outcome))
				return;
			throw new IllegalStateException("Trial " + outcome.key() + " already has a different outcome.\n" +
				"Recorded: " + record.outcome() + "\n" +
				"New     : " + outcome);
		}
		writeRecord(campaignId, record.withOutcome(outcome));
	}

	@Override
	public final void replaceSnapshot(Campaign campaign) throws IOException
	{
		requireThat(campaign, "campaign").isNotNull();
		requireCampaignId(campaign.header().id());
		writeSnapshot(campaign);
	}

	@Override
	public final List<CampaignEvent.Complete> listUnadoptedOutcomes(Campaign snapshot) throws IOException
	{
		requireThat(snapshot, "snapshot").isNotNull();
		String campaignId = snapshot.header().id();
		requireCampaignId(campaignId);
		List<CampaignEvent.Complete> outcomes = new ArrayList<>();
		for (LogicalTrial trial : snapshot.trials())
		{
			if (trial.state() == TrialState.JUDGED)
				continue;
			Optional<TrialRecord> record = readRecord(campaignId, trial.key());
			if (record.isEmpty() || record.get().outcome() == null)
				continue;
			TrialRecord found = record.get();
			if (!found.instance().equals(trial.instance()))
			{
				throw new IOException("The record of trial " + trial.key() + " in campaign " + campaignId +
					" holds a different instance than the snapshot.\n" +
					"Record  : " + found.instance() + "\n" +
					"Snapshot: " + trial.instance());
			}
			outcomes.add(found.outcome());
		}
		return outcomes;
	}

	@Override
	public final List<CampaignHeader> listHeaders() throws IOException
	{
		return readHeaders();
	}

	/**
	 * Checks that a campaign identity can name a directory.
	 *
	 * @param campaignId the campaign's identity
	 * @throws NullPointerException if {@code campaignId} is null
	 * @throws IllegalArgumentException if {@code campaignId} is blank, {@code .}, {@code ..}, or contains a path
	 *   separator or a NUL character
	 */
	static void requireCampaignId(String campaignId)
	{
		requireThat(campaignId, "campaignId").isNotBlank().doesNotContain("/").doesNotContain("\\").
			doesNotContain("\0").isNotEqualTo(".").isNotEqualTo("..");
	}

	/**
	 * Reads a campaign's snapshot.
	 *
	 * @param campaignId the campaign's identity, which {@link #requireCampaignId(String)} accepts
	 * @return the campaign, or empty if it has no snapshot
	 * @throws IOException if the snapshot cannot be read or is malformed
	 */
	abstract Optional<Campaign> readSnapshot(String campaignId) throws IOException;

	/**
	 * Atomically replaces a campaign's snapshot.
	 *
	 * @param campaign the campaign, whose identity {@link #requireCampaignId(String)} accepts
	 * @throws IOException if the snapshot cannot be written
	 */
	abstract void writeSnapshot(Campaign campaign) throws IOException;

	/**
	 * Reads a trial's record.
	 *
	 * @param campaignId the campaign's identity, which {@link #requireCampaignId(String)} accepts
	 * @param key the trial's identity
	 * @return the record, or empty if the trial has none
	 * @throws IOException if the record cannot be read, is malformed or belongs to a different trial
	 */
	abstract Optional<TrialRecord> readRecord(String campaignId, TrialKey key) throws IOException;

	/**
	 * Atomically replaces a trial's record.
	 *
	 * @param campaignId the campaign's identity, which {@link #requireCampaignId(String)} accepts
	 * @param record the record
	 * @throws IOException if the record cannot be written
	 */
	abstract void writeRecord(String campaignId, TrialRecord record) throws IOException;

	/**
	 * Reads the header of every campaign that has a snapshot.
	 *
	 * @return the headers, ordered by campaign identity
	 * @throws IOException if a snapshot cannot be read or is malformed
	 */
	abstract List<CampaignHeader> readHeaders() throws IOException;
}
