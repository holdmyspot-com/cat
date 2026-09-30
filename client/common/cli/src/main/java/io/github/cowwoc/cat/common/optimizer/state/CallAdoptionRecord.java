package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Immutable C1 identity envelope for one producer's adopted call outcome.
 *
 * @param schemaVersion schema version
 * @param campaignId campaign identity
 * @param logicalCallId logical call identity
 * @param runRequestRef request evidence
 * @param slotClaimRef retained slot claim
 * @param completionRef indexed completion evidence
 * @param producerRecordRef complete owner record evidence
 */
public record CallAdoptionRecord(int schemaVersion, String campaignId, String logicalCallId,
	EvidenceRef runRequestRef, EvidenceRef slotClaimRef, EvidenceRef completionRef,
	EvidenceRef producerRecordRef)
{
	/** Validates the adoption envelope. */
	public CallAdoptionRecord
	{
		if (schemaVersion != 1 || campaignId == null || campaignId.isBlank() || logicalCallId == null ||
			logicalCallId.isBlank())
			throw new IllegalArgumentException("Invalid call adoption identity");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		Objects.requireNonNull(completionRef, "completionRef");
		Objects.requireNonNull(producerRecordRef, "producerRecordRef");
		if (!slotClaimRef.collection().equals("call-slot-claims") ||
			!completionRef.collection().equals("native-run-completions"))
			throw new IllegalArgumentException("Invalid call adoption collection");
	}
}
