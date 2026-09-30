package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * C9's complete retained native call and write-denial inventory for one generation turn.
 *
 * @param schemaVersion durable schema version
 * @param logicalCallId C1 logical call identity
 * @param runRequestRef C0R request
 * @param runId native run identity
 * @param nativeEventRootRef complete retained event root
 * @param orderedNativeCallIdAndRawEvidenceRefs call callbacks in observed order
 * @param orderedNativeWriteDenialRefs denied direct-write events in observed order
 */
public record GenerationNativeCallbackIndex(int schemaVersion, String logicalCallId, EvidenceRef runRequestRef,
	String runId, EvidenceRef nativeEventRootRef, List<NativeCallEvidence> orderedNativeCallIdAndRawEvidenceRefs,
	List<EvidenceRef> orderedNativeWriteDenialRefs)
{
	/** The only supported native callback index schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates the complete callback index's shape and unique native identities. */
	public GenerationNativeCallbackIndex
	{
		if (schemaVersion != SCHEMA_VERSION || logicalCallId == null || logicalCallId.isBlank() ||
			runId == null || runId.isBlank())
			throw new IllegalArgumentException("Generation native callback identity is invalid");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		Objects.requireNonNull(nativeEventRootRef, "nativeEventRootRef");
		if (!runRequestRef.collection().equals("run-requests"))
			throw new IllegalArgumentException("Native callback index request collection is invalid");
		orderedNativeCallIdAndRawEvidenceRefs = List.copyOf(Objects.requireNonNull(
			orderedNativeCallIdAndRawEvidenceRefs, "orderedNativeCallIdAndRawEvidenceRefs"));
		orderedNativeWriteDenialRefs = List.copyOf(Objects.requireNonNull(orderedNativeWriteDenialRefs,
			"orderedNativeWriteDenialRefs"));
		Set<String> ids = new HashSet<>();
		for (NativeCallEvidence call: orderedNativeCallIdAndRawEvidenceRefs)
		{
			if (!ids.add(call.nativeCallId()))
				throw new IllegalArgumentException("Generation native callback index contains duplicate call id");
		}
	}
}
