package io.github.cowwoc.cat.common.optimizer.store;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignHeader;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;

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
	 * @param campaignId the campaign's identity
	 * @return the campaign, or empty if it has no snapshot
	 * @throws NullPointerException if {@code campaignId} is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment
	 * @throws IOException if the snapshot cannot be read or is malformed
	 */
	Optional<Campaign> load(String campaignId) throws IOException;

	/**
	 * Records a trial's assignment before its session starts. A record that has no outcome is replaced.
	 *
	 * @param campaignId the campaign's identity
	 * @param trial the assigned trial
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code campaignId} is not a single path segment or {@code trial} is not
	 *   {@code ASSIGNED}
	 * @throws IllegalStateException if the trial's record already holds an outcome
	 * @throws IOException if the record cannot be read or written
	 */
	void writeAssignment(String campaignId, LogicalTrial trial) throws IOException;

	/**
	 * Records a trial's outcome before the campaign adopts it. Writing the outcome that the record already holds does
	 * nothing.
	 *
	 * @param campaignId the campaign's identity
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
	 * @throws IllegalArgumentException if the campaign's identity is not a single path segment
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
	 * @throws IllegalArgumentException if the campaign's identity is not a single path segment
	 * @throws IOException if a record cannot be read, is malformed or does not match the snapshot's trial
	 */
	List<CampaignEvent.Complete> listUnadoptedOutcomes(Campaign snapshot) throws IOException;

	/**
	 * Returns the headers of every campaign that has a snapshot.
	 *
	 * @return the headers, ordered by campaign identity
	 * @throws IOException if a snapshot cannot be read or is malformed
	 */
	List<CampaignHeader> listHeaders() throws IOException;
}
