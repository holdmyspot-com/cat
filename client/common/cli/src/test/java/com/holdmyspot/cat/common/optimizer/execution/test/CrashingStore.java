package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.store.CampaignStore;
import com.holdmyspot.cat.common.optimizer.store.DeliveryJournal;
import com.holdmyspot.cat.common.optimizer.store.TrialSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link CampaignStore} that simulates a crash: once armed, it fails the first snapshot replacement after the next
 * write of a kind, and every write after that, until the process restarts.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
public final class CrashingStore implements CampaignStore
{
	private final CampaignStore delegate;
	private Write trigger;
	private boolean written;
	private boolean crashed;
	private final List<LogicalTrial> assignments = new ArrayList<>();
	private final Map<TrialKey, List<TrialSession>> trialSessions = new HashMap<>();

	/**
	 * Creates a store that does not crash.
	 *
	 * @param delegate the store that holds the data
	 * @throws NullPointerException if {@code delegate} is null
	 */
	public CrashingStore(CampaignStore delegate)
	{
		requireThat(delegate, "delegate").isNotNull();
		this.delegate = delegate;
	}

	/**
	 * Crashes at the first snapshot replacement after the next write of a kind.
	 *
	 * @param kind the kind of write
	 * @throws NullPointerException if {@code kind} is null
	 */
	public void crashAfter(Write kind)
	{
		requireThat(kind, "kind").isNotNull();
		trigger = kind;
		written = false;
	}

	/**
	 * Stops crashing, as a restarted process would.
	 */
	public void restart()
	{
		trigger = null;
		written = false;
		crashed = false;
	}

	/**
	 * Returns every assignment record written, including rewrites.
	 *
	 * @return the records, oldest first
	 */
	public List<LogicalTrial> assignments()
	{
		return List.copyOf(assignments);
	}

	/**
	 * Returns the session records appended to a trial's record.
	 *
	 * @param key the trial's key
	 * @return the records, oldest first; empty if none was appended
	 * @throws NullPointerException if {@code key} is null
	 */
	public List<TrialSession> trialSessions(TrialKey key)
	{
		requireThat(key, "key").isNotNull();
		return List.copyOf(trialSessions.getOrDefault(key, List.of()));
	}

	/**
	 * Fails if the store crashed.
	 *
	 * @throws IOException if the store crashed
	 */
	private void requireAlive() throws IOException
	{
		if (crashed)
			throw new IOException("The process crashed");
	}

	@Override
	public Optional<Campaign> load(String campaignId) throws IOException
	{
		return delegate.load(campaignId);
	}

	@Override
	public void writeAssignment(String campaignId, LogicalTrial trial) throws IOException
	{
		requireAlive();
		delegate.writeAssignment(campaignId, trial);
		assignments.add(trial);
		if (trigger == Write.ASSIGNMENT)
			written = true;
	}

	@Override
	public void writeTrialSession(String campaignId, TrialKey key, TrialSession session) throws IOException
	{
		requireAlive();
		delegate.writeTrialSession(campaignId, key, session);
		trialSessions.computeIfAbsent(key, _ -> new ArrayList<>()).add(session);
	}

	@Override
	public void writeOutcome(String campaignId, CampaignEvent.Complete outcome) throws IOException
	{
		requireAlive();
		delegate.writeOutcome(campaignId, outcome);
		if (trigger == Write.OUTCOME)
			written = true;
	}

	@Override
	public void replaceSnapshot(Campaign campaign) throws IOException
	{
		if (trigger == Write.WORKING_CANDIDATE && !delegate.load(campaign.header().id()).orElseThrow().
			development().working().equals(campaign.development().working()))
		{
			written = true;
		}
		if (written)
		{
			trigger = null;
			written = false;
			crashed = true;
		}
		requireAlive();
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
		requireAlive();
		delegate.writeJournal(campaignId, journal);
	}

	/**
	 * A kind of write that a crash follows.
	 */
	public enum Write
	{
		/**
		 * A trial's assignment record.
		 */
		ASSIGNMENT,
		/**
		 * A trial's outcome.
		 */
		OUTCOME,
		/**
		 * A snapshot replacement that promotes a different working candidate.
		 */
		WORKING_CANDIDATE
	}
}
