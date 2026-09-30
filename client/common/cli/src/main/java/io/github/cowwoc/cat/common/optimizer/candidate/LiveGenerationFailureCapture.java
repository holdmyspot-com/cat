package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable noncandidate evidence of an exactly reconciled failed generation.
 *
 * @param schemaVersion exact record schema
 * @param id unique failure capture identity
 * @param checkpointRef pinned generation checkpoint
 * @param logicalCallId C1 logical call identity
 * @param incumbentSnapshotRef applied incumbent identity
 * @param toolCallIndexRef sealed call evidence
 * @param completionRef typed native completion
 * @param acceptedEditReceiptRefs accepted gateway edits
 * @param denialRefs gateway rejections and native write denials
 * @param beforeInventoryRef pre-call Git inventory
 * @param currentInventoryRef retained failed-state inventory
 * @param gitDiffEvidenceRef immutable worktree diff
 * @param affectedPaths paths changed by this generation
 */
public record LiveGenerationFailureCapture(int schemaVersion, String id, EvidenceRef checkpointRef,
	String logicalCallId, String incumbentSnapshotRef, EvidenceRef toolCallIndexRef, EvidenceRef completionRef,
	List<EvidenceRef> acceptedEditReceiptRefs, List<EvidenceRef> denialRefs, EvidenceRef beforeInventoryRef,
	EvidenceRef currentInventoryRef, EvidenceRef gitDiffEvidenceRef, List<Path> affectedPaths)
{
	public static final int SCHEMA_VERSION = 1;

	/** Validates the immutable noncandidate record. */
	public LiveGenerationFailureCapture
	{
		if (schemaVersion != SCHEMA_VERSION || id == null || id.isBlank() || logicalCallId == null ||
			logicalCallId.isBlank() || !PromptSnapshot.isSha256(incumbentSnapshotRef))
			throw new IllegalArgumentException("Invalid live generation failure identity");
		check(checkpointRef, "live-generation-checkpoints");
		check(toolCallIndexRef, "generation-tool-call-indexes");
		check(completionRef, "native-run-completions");
		check(beforeInventoryRef, "git-file-inventories");
		check(currentInventoryRef, "git-file-inventories");
		check(gitDiffEvidenceRef, "live-generation-diffs");
		acceptedEditReceiptRefs = List.copyOf(Objects.requireNonNull(acceptedEditReceiptRefs,
			"acceptedEditReceiptRefs"));
		denialRefs = List.copyOf(Objects.requireNonNull(denialRefs, "denialRefs"));
		for (EvidenceRef ref: acceptedEditReceiptRefs)
			check(ref, "generation-edit-receipts");
		for (EvidenceRef ref: denialRefs)
			Objects.requireNonNull(ref, "denialRef");
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
