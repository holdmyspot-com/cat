package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable receipt binding one captured candidate to journaled incumbent restoration.
 *
 * @param schemaVersion exact record schema
 * @param checkpointRef pinned generation checkpoint
 * @param captureRef admitted candidate capture
 * @param restoredSnapshotRef applied incumbent restored in the worktree
 * @param transactionReceiptRef journaled restoration receipt
 * @param affectedPaths restored candidate paths
 */
public record LiveGenerationRestore(int schemaVersion, EvidenceRef checkpointRef, EvidenceRef captureRef,
	String restoredSnapshotRef, EvidenceRef transactionReceiptRef, List<Path> affectedPaths)
{
	public static final int SCHEMA_VERSION = 1;

	/** Validates the immutable restore receipt. */
	public LiveGenerationRestore
	{
		if (schemaVersion != SCHEMA_VERSION || !PromptSnapshot.isSha256(restoredSnapshotRef))
			throw new IllegalArgumentException("Invalid restored snapshot identity");
		if (!Objects.requireNonNull(checkpointRef, "checkpointRef").collection().equals("live-generation-checkpoints") ||
			!Objects.requireNonNull(captureRef, "captureRef").collection().equals("live-generation-captures") ||
			!Objects.requireNonNull(transactionReceiptRef, "transactionReceiptRef").collection().equals("delivery-receipts"))
			throw new IllegalArgumentException("Invalid live restore evidence collection");
		affectedPaths = List.copyOf(Objects.requireNonNull(affectedPaths, "affectedPaths"));
		for (Path path: affectedPaths)
			DeliveryPath.validateRelative(path);
	}
}
