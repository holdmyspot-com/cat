package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Records one create-once pre-start authority for the original scheduler claim.
 *
 * @param schemaVersion exactly one
 * @param campaignId the pinned campaign
 * @param logicalCallId the claimed scheduler call
 * @param runRequestRef the immutable native request
 * @param slotClaimRef the original scheduler claim
 * @param preStartRef the indexed pre-start record
 * @param contentSha256 the content identity of the indexed record
 */
public record CallPreStartIndexEntry(int schemaVersion, String campaignId, String logicalCallId,
	EvidenceRef runRequestRef, EvidenceRef slotClaimRef, EvidenceRef preStartRef, String contentSha256)
{
	/**
	 * Validates the create-once index envelope.
	 *
	 * @param schemaVersion exactly one
	 * @param campaignId the pinned campaign
	 * @param logicalCallId the claimed scheduler call
	 * @param runRequestRef the immutable native request
	 * @param slotClaimRef the original scheduler claim
	 * @param preStartRef the indexed pre-start record
	 * @param contentSha256 the content identity of the indexed record
	 * @throws NullPointerException if a required value is null
	 * @throws IllegalArgumentException if an identity or collection is invalid
	 */
	public CallPreStartIndexEntry
	{
		if (schemaVersion != 1 || campaignId == null || campaignId.isBlank() ||
			logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Invalid call pre-start index identity");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		Objects.requireNonNull(preStartRef, "preStartRef");
		Objects.requireNonNull(contentSha256, "contentSha256");
		if (!runRequestRef.collection().equals("run-requests") ||
			!slotClaimRef.collection().equals("call-slot-claims") ||
			!preStartRef.collection().equals("call-pre-starts") ||
			!contentSha256.equals(preStartRef.evidenceId()))
			throw new IllegalArgumentException("Invalid call pre-start index evidence");
	}
}
