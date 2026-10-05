package io.github.cowwoc.cat.common.optimizer.store;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Persists campaign snapshots and logical-trial records.
 * <p>
 * The driver follows a write-ahead order: it writes each assignment before the trial's session starts, replaces the
 * snapshot that holds the assignment before the session starts, writes each outcome before adopting it, and replaces
 * the snapshot after adoption. Reconciliation adopts the outcomes that {@link #listUnadoptedOutcomes(Campaign)} returns
 * and reruns the snapshot's other unadopted trials.
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
}
