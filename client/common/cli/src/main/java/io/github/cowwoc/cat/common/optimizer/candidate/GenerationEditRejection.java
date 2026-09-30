package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable call-bound gateway denial, including raw attempted path text.
 *
 * @param schemaVersion exact rejection schema
 * @param checkpointRef registered live checkpoint
 * @param logicalCallId C1 logical call
 * @param operationId call-local idempotency key
 * @param nativeToolCallRef retained C3 tool call
 * @param kind requested edit kind
 * @param rawSourcePath untrusted source path text
 * @param rawDestinationPath untrusted destination path text if supplied
 * @param reason precise denial reason
 * @param observedLiveStates inspected authorized endpoint states only
 */
public record GenerationEditRejection(int schemaVersion, EvidenceRef checkpointRef, String logicalCallId,
	String operationId, EvidenceRef nativeToolCallRef, GenerationEditOperation.Kind kind,
	String rawSourcePath, Optional<String> rawDestinationPath, String reason,
	List<GenerationPathState> observedLiveStates)
{
	/** The only supported edit-rejection schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates denial identity while retaining the untrusted attempted path verbatim. */
	public GenerationEditRejection
	{
		if (schemaVersion != SCHEMA_VERSION || logicalCallId == null || logicalCallId.isBlank() ||
			operationId == null || operationId.isBlank() || reason == null || reason.isBlank())
			throw new IllegalArgumentException("Generation edit rejection identity is invalid");
		Objects.requireNonNull(checkpointRef, "checkpointRef");
		Objects.requireNonNull(nativeToolCallRef, "nativeToolCallRef");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(rawSourcePath, "rawSourcePath");
		Objects.requireNonNull(rawDestinationPath, "rawDestinationPath");
		if (!checkpointRef.collection().equals("live-generation-checkpoints") ||
			!nativeToolCallRef.collection().equals("generation-tool-calls"))
			throw new IllegalArgumentException("Generation edit rejection reference collection is invalid");
		observedLiveStates = List.copyOf(Objects.requireNonNull(observedLiveStates, "observedLiveStates"));
	}
}
