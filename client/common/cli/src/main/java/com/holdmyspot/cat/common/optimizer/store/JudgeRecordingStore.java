package com.holdmyspot.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * A campaign store that records each prompt test's development judge whenever a campaign ends: when a snapshot moves
 * the campaign from active, or from another end, to cancelled, closed or finished, the judge records are written
 * before the snapshot, so that a failed write leaves the campaign as it was.
 * <p>
 * <b>Thread Safety:</b> This class is as thread-safe as the store it wraps.
 */
public final class JudgeRecordingStore implements CampaignStore
{
	private final CampaignStore delegate;
	private final DevelopmentJudgeRecords records;

	/**
	 * Creates a store.
	 *
	 * @param delegate the store that persists the campaigns
	 * @param records  the development judge records that each campaign end updates
	 * @throws NullPointerException if any of the arguments are null
	 */
	public JudgeRecordingStore(CampaignStore delegate, DevelopmentJudgeRecords records)
	{
		requireThat(delegate, "delegate").isNotNull();
		requireThat(records, "records").isNotNull();
		this.delegate = delegate;
		this.records = records;
	}

	@Override
	public Optional<Campaign> load(String campaignId) throws IOException
	{
		return delegate.load(campaignId);
	}

	@Override
	public void writeAssignment(String campaignId, LogicalTrial trial) throws IOException
	{
		delegate.writeAssignment(campaignId, trial);
	}

	@Override
	public void writeTrialSession(String campaignId, TrialKey key, TrialSession session) throws IOException
	{
		delegate.writeTrialSession(campaignId, key, session);
	}

	@Override
	public void writeOutcome(String campaignId, CampaignEvent.Complete outcome) throws IOException
	{
		delegate.writeOutcome(campaignId, outcome);
	}

	@Override
	public void replaceSnapshot(Campaign campaign) throws IOException
	{
		requireThat(campaign, "campaign").isNotNull();
		Lifecycle lifecycle = campaign.header().lifecycle();
		if (lifecycle != Lifecycle.ACTIVE)
		{
			Optional<Lifecycle> before = delegate.load(campaign.header().id()).map(stored ->
				stored.header().lifecycle());
			if (before.isEmpty() || before.get() != lifecycle)
				records.campaignEnded(campaign);
		}
		delegate.replaceSnapshot(campaign);
	}

	@Override
	public List<CampaignEvent.Complete> listUnadoptedOutcomes(Campaign snapshot) throws IOException
	{
		return delegate.listUnadoptedOutcomes(snapshot);
	}

	@Override
	public List<Header> listHeaders() throws IOException
	{
		return delegate.listHeaders();
	}

	@Override
	public Optional<DeliveryJournal> loadJournal(String campaignId) throws IOException
	{
		return delegate.loadJournal(campaignId);
	}

	@Override
	public void writeJournal(String campaignId, DeliveryJournal journal) throws IOException
	{
		delegate.writeJournal(campaignId, journal);
	}
}
