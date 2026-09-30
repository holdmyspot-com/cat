package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Outcome of publishing an assessment at one locked latest-claim version.
 *
 * @param status publication outcome
 * @param key canonical project and target key
 * @param claimVersion exact claim sequence considered
 * @param assessmentRef published immutable assessment, if accepted
 * @param publicationId durable publication identity, if accepted
 */
public record ClaimPublication(Status status, CampaignLookupKey key, long claimVersion,
	EvidenceRef assessmentRef, String publicationId)
{
	/**
	 * Closed publication outcomes.
	 */
	public enum Status
	{
		PUBLISHED,
		CLAIM_CHANGED
	}

	/**
	 * Requires complete accepted receipts and empty changed-claim results.
	 */
	public ClaimPublication
	{
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(key, "key");
		if (claimVersion < 0 || status == Status.PUBLISHED &&
			(assessmentRef == null || publicationId == null || publicationId.isBlank()) ||
			status == Status.CLAIM_CHANGED && (assessmentRef != null || publicationId != null))
			throw new IllegalArgumentException("Claim publication fields are inconsistent");
	}
}
