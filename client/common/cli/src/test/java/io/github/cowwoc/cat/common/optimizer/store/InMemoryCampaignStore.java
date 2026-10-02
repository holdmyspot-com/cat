package io.github.cowwoc.cat.common.optimizer.store;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignHeader;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;

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
	Optional<Campaign> readSnapshot(String campaignId)
	{
		return Optional.ofNullable(snapshots.get(campaignId));
	}

	@Override
	void writeSnapshot(Campaign campaign)
	{
		snapshots.put(campaign.header().id(), campaign);
	}

	@Override
	Optional<TrialRecord> readRecord(String campaignId, TrialKey key)
	{
		Map<TrialKey, TrialRecord> campaignRecords = records.get(campaignId);
		if (campaignRecords == null)
			return Optional.empty();
		return Optional.ofNullable(campaignRecords.get(key));
	}

	@Override
	void writeRecord(String campaignId, TrialRecord record)
	{
		records.computeIfAbsent(campaignId, _ -> new HashMap<>()).put(record.key(), record);
	}

	@Override
	public List<CampaignHeader> listHeaders()
	{
		List<CampaignHeader> headers = new ArrayList<>();
		for (Campaign campaign : snapshots.values())
			headers.add(campaign.header());
		return headers;
	}

	@Override
	Optional<DeliveryJournal> readJournal(String campaignId)
	{
		return Optional.ofNullable(journals.get(campaignId));
	}

	@Override
	void replaceJournal(String campaignId, DeliveryJournal journal)
	{
		journals.put(campaignId, journal);
	}
}
