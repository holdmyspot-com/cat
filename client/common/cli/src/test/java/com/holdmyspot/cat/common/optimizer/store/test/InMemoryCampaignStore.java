package com.holdmyspot.cat.common.optimizer.store.test;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.store.AbstractCampaignStore;
import com.holdmyspot.cat.common.optimizer.store.CampaignStore;
import com.holdmyspot.cat.common.optimizer.store.DeliveryJournal;
import com.holdmyspot.cat.common.optimizer.store.TrialRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A {@link CampaignStore} that holds snapshots, trial records and delivery journals in memory.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
public final class InMemoryCampaignStore extends AbstractCampaignStore
{
	private final Map<String, Campaign> snapshots = new TreeMap<>();
	private final Map<String, Map<TrialKey, TrialRecord>> records = new HashMap<>();
	private final Map<String, DeliveryJournal> journals = new HashMap<>();

	/**
	 * Creates an empty store.
	 */
	public InMemoryCampaignStore()
	{
	}

	@Override
	public Optional<Campaign> readSnapshot(String campaignId)
	{
		return Optional.ofNullable(snapshots.get(campaignId));
	}

	@Override
	public void writeSnapshot(Campaign campaign)
	{
		snapshots.put(campaign.header().id(), campaign);
	}

	@Override
	public Optional<TrialRecord> readRecord(String campaignId, TrialKey key)
	{
		Map<TrialKey, TrialRecord> campaignRecords = records.get(campaignId);
		if (campaignRecords == null)
			return Optional.empty();
		return Optional.ofNullable(campaignRecords.get(key));
	}

	@Override
	public void writeRecord(String campaignId, TrialRecord record)
	{
		records.computeIfAbsent(campaignId, _ -> new HashMap<>()).put(record.key(), record);
	}

	@Override
	public List<Header> listHeaders()
	{
		List<Header> headers = new ArrayList<>();
		for (Campaign campaign : snapshots.values())
			headers.add(campaign.header());
		return headers;
	}

	@Override
	public Optional<DeliveryJournal> readJournal(String campaignId)
	{
		return Optional.ofNullable(journals.get(campaignId));
	}

	@Override
	public void replaceJournal(String campaignId, DeliveryJournal journal)
	{
		journals.put(campaignId, journal);
	}
}
