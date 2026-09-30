package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.List;
import java.util.Objects;

/**
 * Immutable pre-mutation plan; its publication is the edit's durable write-ahead point.
 *
 * @param schemaVersion transaction schema
 * @param checkpointRef pinned checkpoint
 * @param logicalCallId call identity
 * @param operation exact operation
 * @param nativeToolCallRef registered native call
 * @param beforeStates inspected prestates
 * @param afterStates planned poststates
 * @param predecessorCaptures immutable original bytes
 */
public record GenerationEditTransaction(int schemaVersion, EvidenceRef checkpointRef, String logicalCallId,
	GenerationEditOperation operation, EvidenceRef nativeToolCallRef, List<GenerationPathState> beforeStates,
	List<GenerationPathState> afterStates, List<EvidenceRef> predecessorCaptures)
{
	/** Supported schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates a complete transaction plan. */
	public GenerationEditTransaction
	{
		if (schemaVersion != SCHEMA_VERSION || logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Invalid generation edit transaction identity");
		Objects.requireNonNull(checkpointRef, "checkpointRef");
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(nativeToolCallRef, "nativeToolCallRef");
		beforeStates = List.copyOf(beforeStates);
		afterStates = List.copyOf(afterStates);
		predecessorCaptures = List.copyOf(predecessorCaptures);
		if (beforeStates.isEmpty() || beforeStates.size() != afterStates.size())
			throw new IllegalArgumentException("Generation edit endpoints are incomplete");
	}
}
