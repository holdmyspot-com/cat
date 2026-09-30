package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, admitted live generation result.
 *
 * @param schemaVersion exact record schema
 * @param id unique capture identity
 * @param checkpointRef pinned generation checkpoint
 * @param logicalCallId C1 logical call identity
 * @param incumbentSnapshotRef applied incumbent identity
 * @param candidateSnapshotRef captured candidate identity
 * @param beforeInventoryRef pre-call Git inventory
 * @param afterInventoryRef captured Git inventory
 * @param toolCallIndexRef sealed call evidence
 * @param completionRef typed native completion
 * @param gitDiffEvidenceRef immutable worktree diff
 * @param affectedPaths paths changed by this generation
 */
public record LiveCandidateCapture(int schemaVersion, String id, EvidenceRef checkpointRef,
	String logicalCallId, String incumbentSnapshotRef, String candidateSnapshotRef,
	EvidenceRef beforeInventoryRef, EvidenceRef afterInventoryRef, EvidenceRef toolCallIndexRef,
	EvidenceRef completionRef, EvidenceRef gitDiffEvidenceRef, List<Path> affectedPaths)
{
	public static final int SCHEMA_VERSION = 1;

	/** Validates the immutable capture shape. */
	public LiveCandidateCapture
	{
		if (schemaVersion != SCHEMA_VERSION || id == null || id.isBlank() || logicalCallId == null ||
			logicalCallId.isBlank() || !PromptSnapshot.isSha256(incumbentSnapshotRef) ||
			!PromptSnapshot.isSha256(candidateSnapshotRef))
			throw new IllegalArgumentException("Invalid live candidate capture identity");
		check(checkpointRef, "live-generation-checkpoints");
		check(beforeInventoryRef, "git-file-inventories");
		check(afterInventoryRef, "git-file-inventories");
		check(toolCallIndexRef, "generation-tool-call-indexes");
		check(completionRef, "native-run-completions");
		check(gitDiffEvidenceRef, "live-generation-diffs");
		affectedPaths = List.copyOf(Objects.requireNonNull(affectedPaths, "affectedPaths"));
		for (Path path: affectedPaths)
			DeliveryPath.validateRelative(path);
	}

	/** Validates one typed evidence reference. */
	private static void check(EvidenceRef ref, String collection)
	{
		if (!Objects.requireNonNull(ref, collection).collection().equals(collection))
			throw new IllegalArgumentException("Invalid " + collection + " reference");
	}
}
