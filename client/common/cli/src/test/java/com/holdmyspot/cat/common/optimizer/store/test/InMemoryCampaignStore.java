package com.holdmyspot.cat.common.optimizer.store.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.execution.VerdictKey;
import com.holdmyspot.cat.common.optimizer.execution.VerdictRecord;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
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
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.databind.node.ObjectNode;

/**
 * A {@link CampaignStore} that holds snapshots, trial records, stored verdicts, assertion checks and delivery journals
 * in memory.
 * <p>
 * The driver tests use it instead of {@code FileCampaignStore}, because the file store forces every write to the
 * storage device and a driver test writes thousands of times. The record rules are the real ones, those of
 * {@link AbstractCampaignStore}, and {@code InMemoryCampaignStoreTest} runs the store contract against it.
 */
public final class InMemoryCampaignStore extends AbstractCampaignStore
{
	private final Map<String, Campaign> snapshots = new TreeMap<>();
	private final Map<String, Map<TrialKey, TrialRecord>> records = new HashMap<>();
	private final Map<String, DeliveryJournal> journals = new HashMap<>();
	private final Map<String, Map<VerdictKey, VerdictRecord>> verdicts = new ConcurrentHashMap<>();
	private final Map<String, Map<Sha256, ObjectNode>> assertionChecks = new ConcurrentHashMap<>();

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

	@Override
	public Optional<VerdictRecord> readVerdict(String campaignId, VerdictKey key)
	{
		Map<VerdictKey, VerdictRecord> campaignVerdicts = verdicts.get(campaignId);
		if (campaignVerdicts == null)
			return Optional.empty();
		return Optional.ofNullable(campaignVerdicts.get(key));
	}

	@Override
	public VerdictRecord writeVerdict(String campaignId, VerdictKey key, VerdictRecord verdict)
	{
		VerdictRecord stored = verdicts.computeIfAbsent(campaignId, _ -> new ConcurrentHashMap<>()).
			putIfAbsent(key, verdict);
		if (stored == null)
			return verdict;
		return stored;
	}

	@Override
	public Optional<ObjectNode> readAssertionCheck(String campaignId, Sha256 key)
	{
		requireCampaignId(campaignId);
		requireThat(key, "key").isNotNull();
		ObjectNode stored = assertionChecks.getOrDefault(campaignId, Map.of()).get(key);
		return Optional.ofNullable(stored).map(ObjectNode::deepCopy);
	}

	@Override
	public ObjectNode writeAssertionCheck(String campaignId, Sha256 key, ObjectNode record)
	{
		requireCampaignId(campaignId);
		requireThat(key, "key").isNotNull();
		requireThat(record, "record").isNotNull();
		ObjectNode stored = assertionChecks.computeIfAbsent(campaignId, _ -> new ConcurrentHashMap<>()).
			computeIfAbsent(key, _ -> record.deepCopy());
		return stored.deepCopy();
	}

	/**
	 * Returns the verdicts that a campaign stores.
	 *
	 * @param campaignId the campaign's identifier
	 * @return the verdicts by key
	 */
	public Map<VerdictKey, VerdictRecord> verdicts(String campaignId)
	{
		return Map.copyOf(verdicts.getOrDefault(campaignId, Map.of()));
	}
}
