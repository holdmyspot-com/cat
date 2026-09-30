package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Complete C3 call and disposition seal for one C1 generation slot.
 *
 * @param schemaVersion durable schema version
 * @param checkpointRef exact live generation checkpoint
 * @param runRequestRef C0R request
 * @param logicalCallId C1 logical call identity
 * @param slotClaimRef retained C1 slot claim
 * @param nativeRunId native run identity
 * @param nativeCallbackIndexRef complete C9 callback evidence
 * @param orderedToolCallAndDispositionRefs calls and dispositions in native order
 */
public record GenerationToolCallIndex(int schemaVersion, EvidenceRef checkpointRef, EvidenceRef runRequestRef,
	String logicalCallId, EvidenceRef slotClaimRef, String nativeRunId, EvidenceRef nativeCallbackIndexRef,
	List<ToolCallDispositionPair> orderedToolCallAndDispositionRefs)
{
	/** The only supported tool-call index schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates C3 index identity and unique retained tool calls. */
	public GenerationToolCallIndex
	{
		if (schemaVersion != SCHEMA_VERSION || logicalCallId == null || logicalCallId.isBlank() ||
			nativeRunId == null || nativeRunId.isBlank())
			throw new IllegalArgumentException("Generation tool-call index identity is invalid");
		Objects.requireNonNull(checkpointRef, "checkpointRef");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		Objects.requireNonNull(nativeCallbackIndexRef, "nativeCallbackIndexRef");
		if (!checkpointRef.collection().equals("live-generation-checkpoints") ||
			!runRequestRef.collection().equals("run-requests") ||
			!slotClaimRef.collection().equals("call-slot-claims") ||
			!nativeCallbackIndexRef.collection().equals("generation-native-callback-indexes"))
			throw new IllegalArgumentException("Generation tool-call index reference collections are invalid");
		orderedToolCallAndDispositionRefs = List.copyOf(Objects.requireNonNull(orderedToolCallAndDispositionRefs,
			"orderedToolCallAndDispositionRefs"));
		Set<EvidenceRef> calls = new HashSet<>();
		for (ToolCallDispositionPair pair: orderedToolCallAndDispositionRefs)
		{
			if (!calls.add(pair.toolCallRef()))
				throw new IllegalArgumentException("Generation tool-call index has a duplicate call");
		}
	}
}
