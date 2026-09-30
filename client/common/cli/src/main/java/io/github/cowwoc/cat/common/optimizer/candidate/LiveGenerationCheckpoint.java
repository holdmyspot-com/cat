package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable pre-dispatch identity and Git inventory for one live generation call.
 *
 * @param schemaVersion exact checkpoint schema
 * @param id unique checkpoint identity
 * @param logicalCallId fresh C1 logical call
 * @param projectRoot pinned real active Git worktree
 * @param incumbentSnapshotRef saved applied incumbent snapshot
 * @param confirmedEditableRootsRef exact C4 roots
 * @param beforeInventoryRef complete before-call Git file inventory
 * @param failedAttemptBaseRef prior failure capture only for a retry
 */
public record LiveGenerationCheckpoint(int schemaVersion, String id, String logicalCallId, Path projectRoot,
	String incumbentSnapshotRef, EvidenceRef confirmedEditableRootsRef, EvidenceRef beforeInventoryRef,
	Optional<EvidenceRef> failedAttemptBaseRef)
{
	/** The only supported checkpoint schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates complete immutable checkpoint identity. */
	public LiveGenerationCheckpoint
	{
		if (schemaVersion != SCHEMA_VERSION || id == null || id.isBlank() || logicalCallId == null ||
			logicalCallId.isBlank() || !PromptSnapshot.isSha256(incumbentSnapshotRef))
			throw new IllegalArgumentException("Live generation checkpoint identity is invalid");
		Objects.requireNonNull(projectRoot, "projectRoot");
		Objects.requireNonNull(confirmedEditableRootsRef, "confirmedEditableRootsRef");
		Objects.requireNonNull(beforeInventoryRef, "beforeInventoryRef");
		Objects.requireNonNull(failedAttemptBaseRef, "failedAttemptBaseRef");
		if (!projectRoot.isAbsolute() || !projectRoot.equals(projectRoot.normalize()) ||
			!confirmedEditableRootsRef.collection().equals("confirmed-editable-roots") ||
			!beforeInventoryRef.collection().equals("git-file-inventories") ||
			failedAttemptBaseRef.isPresent() &&
				!failedAttemptBaseRef.orElseThrow().collection().equals("live-generation-failures"))
			throw new IllegalArgumentException("Live generation checkpoint references are invalid");
	}

	/**
	 * Derives the content-addressed reference of this canonical checkpoint.
	 *
	 * @return immutable checkpoint reference
	 */
	public EvidenceRef checkpointRef()
	{
		return new EvidenceRef("live-generation-checkpoints",
			PromptSnapshot.sha256(SnapshotJson.mapper().writeValueAsBytes(this)));
	}
}
