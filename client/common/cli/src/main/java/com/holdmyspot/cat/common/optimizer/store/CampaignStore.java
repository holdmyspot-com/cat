package com.holdmyspot.cat.common.optimizer.store;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.execution.VerdictKey;
import com.holdmyspot.cat.common.optimizer.execution.VerdictRecord;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.node.ObjectNode;

/**
 * Persists campaign snapshots and logical-trial records.
 * <p>
 * The driver follows a write-ahead order: it writes each assignment before the trial's session starts, replaces the
 * snapshot that holds the assignment before the session starts, writes each outcome before adopting it, and replaces
 * the snapshot after adoption. Reconciliation adopts the outcomes that {@link #listUnadoptedOutcomes(Campaign)} returns
 * and reconciles the other unadopted trials with their recorded launch attempts.
 * <p>
 * Loading a campaign reads only its snapshot. Trial records are read only for the snapshot's unadopted trials.
 */
public interface CampaignStore
{
	/**
	 * Loads a campaign's snapshot.
	 *
	 * @param campaignId the campaign's identifier
	 * @return the campaign, or empty if it has no snapshot
	 * @throws NullPointerException if {@code campaignId} is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment
	 * @throws IOException if the snapshot cannot be read or is malformed
	 */
	Optional<Campaign> load(String campaignId) throws IOException;

	/**
	 * Records a trial's assignment before its session starts. A record that has no outcome is replaced.
	 *
	 * @param campaignId the campaign's identifier
	 * @param trial the assigned trial
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment or {@code trial} is not
	 *   {@code ASSIGNED}
	 * @throws IllegalStateException if the trial's record already holds an outcome
	 * @throws IOException if the record cannot be read or written
	 */
	void writeAssignment(String campaignId, LogicalTrial trial) throws IOException;

	/**
	 * Reads the recovery state for the named launch attempt.
	 *
	 * @param campaignId the campaign's identifier
	 * @param key the trial's key
	 * @param attemptOrdinal the one-based launch attempt number
	 * @return the recovery state, or empty if no recovery state belongs to that attempt
	 * @throws NullPointerException if a required argument is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment or
	 *   {@code attemptOrdinal} is less than 1
	 * @throws IOException if recovery state cannot be read or is malformed
	 */
	default Optional<TrialRecovery> readTrialRecovery(String campaignId, TrialKey key, int attemptOrdinal)
		throws IOException
	{
		return Optional.empty();
	}

	/**
	 * Atomically records the original request before publishing a launch, or the observer's next decision before
	 * sending another turn. Repeating the current record does nothing.
	 *
	 * @param campaignId the campaign's identifier
	 * @param recovery the trial's original request and observer progress
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment
	 * @throws IllegalStateException if {@code recovery} replaces a newer attempt, changes a recorded request or
	 *   decision, or begins an attempt with recorded progress
	 * @throws IOException if recovery state cannot be read or written, or the store does not persist it
	 */
	default void writeTrialRecovery(String campaignId, TrialRecovery recovery) throws IOException
	{
		throw new IOException("This campaign store does not persist trial recovery state");
	}

	/**
	 * Lists the most recent recorded launch for each trial key, including trials the campaign has removed from its
	 * queue. The caller checks each request with the server to determine whether it still owns a retained session.
	 *
	 * @param campaignId the campaign's identifier
	 * @return the recorded requests in this campaign
	 * @throws NullPointerException if {@code campaignId} is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment
	 * @throws IOException if recovery state cannot be read or is malformed
	 */
	default List<TrialRecovery> listTrialRecoveries(String campaignId) throws IOException
	{
		return List.of();
	}

	/**
	 * Appends the record of a finished trial session to the trial's assignment record.
	 *
	 * @param campaignId the campaign's identifier
	 * @param key the trial's key
	 * @param session the session's record
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment
	 * @throws IllegalStateException if the trial has no assignment record
	 * @throws IOException if the record cannot be read or written
	 */
	void writeTrialSession(String campaignId, TrialKey key, TrialSession session) throws IOException;

	/**
	 * Records a trial's outcome before the campaign adopts it. Writing the outcome that the record already holds does
	 * nothing.
	 *
	 * @param campaignId the campaign's identifier
	 * @param outcome the judged outcome
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment
	 * @throws IllegalStateException if the trial has no assignment record, or its record holds a different outcome
	 * @throws IOException if the record cannot be read or written
	 */
	void writeOutcome(String campaignId, CampaignEvent.Complete outcome) throws IOException;

	/**
	 * Atomically replaces a campaign's snapshot.
	 *
	 * @param campaign the campaign's current state
	 * @throws NullPointerException if {@code campaign} is null
	 * @throws IllegalArgumentException if the campaign's identifier is not a single path segment
	 * @throws IOException if the snapshot cannot be written
	 */
	void replaceSnapshot(Campaign campaign) throws IOException;

	/**
	 * Returns the recorded outcomes that a snapshot has not adopted yet. Only the records of the snapshot's
	 * {@code ASSIGNED} and {@code RUNNING} trials are read.
	 *
	 * @param snapshot the loaded campaign
	 * @return the outcomes, in the snapshot's trial order
	 * @throws NullPointerException if {@code snapshot} is null
	 * @throws IllegalArgumentException if the campaign's identifier is not a single path segment
	 * @throws IOException if a record cannot be read, is malformed or does not match the snapshot's trial
	 */
	List<CampaignEvent.Complete> listUnadoptedOutcomes(Campaign snapshot) throws IOException;

	/**
	 * Returns the headers of every campaign that has a snapshot.
	 *
	 * @return the headers, ordered by campaign identifier
	 * @throws IOException if a snapshot cannot be read or is malformed
	 */
	List<Header> listHeaders() throws IOException;

	/**
	 * Loads a campaign's delivery journal.
	 *
	 * @param campaignId the campaign's identifier
	 * @return the journal, or empty if the campaign has not started a delivery
	 * @throws NullPointerException if {@code campaignId} is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment
	 * @throws IOException if the journal cannot be read or is malformed
	 */
	Optional<DeliveryJournal> loadJournal(String campaignId) throws IOException;

	/**
	 * Atomically replaces a campaign's delivery journal. A campaign has at most one delivery: once its journal exists,
	 * only the progress of the same planned changes may be recorded.
	 *
	 * @param campaignId the campaign's identifier
	 * @param journal the delivery's current progress
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment
	 * @throws IllegalStateException if the campaign's journal records a different candidate or different changes, or
	 *   more applied changes than {@code journal}
	 * @throws IOException if the journal cannot be read or written
	 */
	void writeJournal(String campaignId, DeliveryJournal journal) throws IOException;

	/**
	 * Reads the verdict stored under a key of a campaign.
	 *
	 * @param campaignId the campaign's identifier
	 * @param key the key of a judged assertion's evidence
	 * @return the verdict, or empty if the campaign stores none under the key
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment
	 * @throws IOException if the verdict cannot be read or is malformed
	 */
	Optional<VerdictRecord> readVerdict(String campaignId, VerdictKey key) throws IOException;

	/**
	 * Stores a verdict under a key of a campaign unless the campaign already stores one under it: the first verdict
	 * stored under a key is final, and a later one is dropped.
	 *
	 * @param campaignId the campaign's identifier
	 * @param key the key of a judged assertion's evidence
	 * @param verdict the verdict
	 * @return the verdict that the key holds afterwards: {@code verdict}, or the verdict that was stored first
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment
	 * @throws IOException if the verdict cannot be read or written
	 */
	VerdictRecord writeVerdict(String campaignId, VerdictKey key, VerdictRecord verdict) throws IOException;

	/**
	 * Reads a campaign's assertion check. Changes to the returned object do not affect stored data.
	 *
	 * @param campaignId the campaign's identifier
	 * @param key        the digest identifying the assertion check
	 * @return an independent copy of the check, or empty if none has been stored under the key
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment
	 * @throws IOException              if the check cannot be read or is malformed
	 */
	Optional<ObjectNode> readAssertionCheck(String campaignId, Sha256 key) throws IOException;

	/**
	 * Stores a campaign's assertion check. The first write under a key wins, including when writes overlap.
	 * Changes to the supplied or returned object after this call do not affect stored data.
	 *
	 * @param campaignId the campaign's identifier
	 * @param key        the digest identifying the assertion check
	 * @param record     the validated assertion check to store
	 * @return an independent copy of the check that won the first write
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment
	 * @throws IOException              if the check cannot be read or written
	 */
	ObjectNode writeAssertionCheck(String campaignId, Sha256 key, ObjectNode record) throws IOException;
}
