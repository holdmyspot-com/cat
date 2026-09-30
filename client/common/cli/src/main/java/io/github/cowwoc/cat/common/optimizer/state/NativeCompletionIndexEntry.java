package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Canonical create-once pointer from a retained call claim to its native completion.
 *
 * @param schemaVersion schema version
 * @param campaignId campaign identity
 * @param logicalCallId logical call identity
 * @param runRequestRef request evidence
 * @param slotClaimRef retained slot claim
 * @param completionRef typed completion evidence
 */
public record NativeCompletionIndexEntry(int schemaVersion, String campaignId, String logicalCallId,
	EvidenceRef runRequestRef, EvidenceRef slotClaimRef, EvidenceRef completionRef)
{
	/** Validates the index envelope. */
	public NativeCompletionIndexEntry
	{
		if (schemaVersion != 1 || campaignId == null || campaignId.isBlank() || logicalCallId == null ||
			logicalCallId.isBlank())
			throw new IllegalArgumentException("Invalid native completion index identity");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		Objects.requireNonNull(completionRef, "completionRef");
		if (!slotClaimRef.collection().equals("call-slot-claims") ||
			!completionRef.collection().equals("native-run-completions"))
			throw new IllegalArgumentException("Invalid native completion index collection");
	}
}
