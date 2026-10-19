package com.holdmyspot.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The record rules that every {@link CampaignStore} shares, over storage primitives that each implementation provides.
 */
public abstract class AbstractCampaignStore implements CampaignStore
{
	/**
	 * Creates a store.
	 */
	public AbstractCampaignStore()
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
		writeRecord(campaignId, new TrialRecord(trial.key(), trial.testCase(), trial.identity(), trial.allocation(),
			trial.infrastructureAttempts(), null, existing.map(TrialRecord::sessions).orElse(List.of())));
	}

	@Override
	public final void writeTrialSession(String campaignId, TrialKey key, TrialSession session) throws IOException
	{
		requireCampaignId(campaignId);
		requireThat(key, "key").isNotNull();
		requireThat(session, "session").isNotNull();
		TrialRecord record = readRecord(campaignId, key).orElseThrow(() ->
			new IllegalStateException("Trial " + key + " has no assignment record"));
		writeRecord(campaignId, record.withSession(session));
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
			if (!found.testCase().equals(trial.testCase()))
			{
				throw new IOException("The record of trial " + trial.key() + " in campaign " + campaignId +
					" holds a different test case than the snapshot.\n" +
					"Record  : " + found.testCase() + "\n" +
					"Snapshot: " + trial.testCase());
			}
			outcomes.add(found.outcome());
		}
		return outcomes;
	}

	@Override
	public final Optional<DeliveryJournal> loadJournal(String campaignId) throws IOException
	{
		requireCampaignId(campaignId);
		return readJournal(campaignId);
	}

	@Override
	public final void writeJournal(String campaignId, DeliveryJournal journal) throws IOException
	{
		requireCampaignId(campaignId);
		requireThat(journal, "journal").isNotNull();
		Optional<DeliveryJournal> existing = readJournal(campaignId);
		if (existing.isPresent())
		{
			DeliveryJournal recorded = existing.get();
			if (!recorded.candidate().equals(journal.candidate()) || !recorded.changes().equals(journal.changes()))
			{
				throw new IllegalStateException("Campaign " + campaignId + " already journals a different delivery.\n" +
					"Recorded: candidate " + recorded.candidate().hex() + ", changes " + recorded.changes() + "\n" +
					"New     : candidate " + journal.candidate().hex() + ", changes " + journal.changes());
			}
			if (journal.applied() < recorded.applied())
			{
				throw new IllegalStateException("The delivery journal of campaign " + campaignId + " already records " +
					recorded.applied() + " applied changes, but the new journal records " + journal.applied() + ".");
			}
		}
		replaceJournal(campaignId, journal);
	}

	/**
	 * Checks that a campaign identifier can name a directory.
	 *
	 * @param campaignId the campaign's identifier
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
	 * @param campaignId the campaign's identifier, which {@link #requireCampaignId(String)} accepts
	 * @return the campaign, or empty if it has no snapshot
	 * @throws IOException if the snapshot cannot be read or is malformed
	 */
	public abstract Optional<Campaign> readSnapshot(String campaignId) throws IOException;

	/**
	 * Atomically replaces a campaign's snapshot.
	 *
	 * @param campaign the campaign, whose identifier {@link #requireCampaignId(String)} accepts
	 * @throws IOException if the snapshot cannot be written
	 */
	public abstract void writeSnapshot(Campaign campaign) throws IOException;

	/**
	 * Reads a trial's record.
	 *
	 * @param campaignId the campaign's identifier, which {@link #requireCampaignId(String)} accepts
	 * @param key the trial's key
	 * @return the record, or empty if the trial has none
	 * @throws IOException if the record cannot be read, is malformed or belongs to a different trial
	 */
	public abstract Optional<TrialRecord> readRecord(String campaignId, TrialKey key) throws IOException;

	/**
	 * Atomically replaces a trial's record.
	 *
	 * @param campaignId the campaign's identifier, which {@link #requireCampaignId(String)} accepts
	 * @param record the record
	 * @throws IOException if the record cannot be written
	 */
	public abstract void writeRecord(String campaignId, TrialRecord record) throws IOException;

	/**
	 * Reads a campaign's delivery journal.
	 *
	 * @param campaignId the campaign's identifier, which {@link #requireCampaignId(String)} accepts
	 * @return the journal, or empty if the campaign has none
	 * @throws IOException if the journal cannot be read or is malformed
	 */
	public abstract Optional<DeliveryJournal> readJournal(String campaignId) throws IOException;

	/**
	 * Atomically replaces a campaign's delivery journal.
	 *
	 * @param campaignId the campaign's identifier, which {@link #requireCampaignId(String)} accepts
	 * @param journal the journal
	 * @throws IOException if the journal cannot be written
	 */
	public abstract void replaceJournal(String campaignId, DeliveryJournal journal) throws IOException;
}
