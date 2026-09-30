package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Exact before/after Git worktree inventory identity for one generation call.
 *
 * @param schemaVersion exact record schema
 * @param headCommit unchanged Git HEAD
 * @param indexSha256 unchanged Git index listing hash
 * @param changedPaths paths with changed inventory states
 * @param beforeInventorySha256 before-call inventory evidence hash
 * @param afterInventorySha256 after-call inventory evidence hash
 */
public record LiveGenerationDiff(int schemaVersion, String headCommit, String indexSha256,
	List<Path> changedPaths, String beforeInventorySha256, String afterInventorySha256)
{
	public static final int SCHEMA_VERSION = 1;

	/** Validates exact immutable diff shape. */
	public LiveGenerationDiff
	{
		if (schemaVersion != SCHEMA_VERSION || headCommit == null || headCommit.isBlank() ||
			!PromptSnapshot.isSha256(indexSha256) || !PromptSnapshot.isSha256(beforeInventorySha256) ||
			!PromptSnapshot.isSha256(afterInventorySha256))
			throw new IllegalArgumentException("Invalid live generation diff identity");
		changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
		for (Path path: changedPaths)
			DeliveryPath.validateRelative(path);
	}
}
