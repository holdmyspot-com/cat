package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * C3-retained native gateway call, published before its first edit.
 *
 * @param schemaVersion exact call schema
 * @param checkpointRef registered live checkpoint
 * @param runRequestRef pinned C0R request
 * @param logicalCallId C1 logical call
 * @param slotClaimRef retained C1 slot claim
 * @param nativeRunId native run identity
 * @param nativeSessionId native session identity
 * @param nativeTurnId native turn identity
 * @param nativeCallId unique callback identity
 * @param rawArgumentsRef immutable raw callback arguments
 */
public record GenerationToolCall(int schemaVersion, EvidenceRef checkpointRef, EvidenceRef runRequestRef,
	String logicalCallId, EvidenceRef slotClaimRef, String nativeRunId, String nativeSessionId,
	String nativeTurnId, String nativeCallId, EvidenceRef rawArgumentsRef)
{
	/** The only supported tool-call schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates complete call identity and reference collections. */
	public GenerationToolCall
	{
		if (schemaVersion != SCHEMA_VERSION)
			throw new IllegalArgumentException("Unsupported generation tool-call schema");
		Objects.requireNonNull(checkpointRef, "checkpointRef");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		Objects.requireNonNull(rawArgumentsRef, "rawArgumentsRef");
		if (!checkpointRef.collection().equals("live-generation-checkpoints") ||
			!runRequestRef.collection().equals("run-requests") ||
			!slotClaimRef.collection().equals("call-slot-claims"))
			throw new IllegalArgumentException("Generation tool-call reference collection is invalid");
		for (String identity: new String[]{logicalCallId, nativeRunId, nativeSessionId, nativeTurnId, nativeCallId})
		{
			if (identity == null || identity.isBlank())
				throw new IllegalArgumentException("Generation tool-call identity must be nonblank");
		}
	}
}
