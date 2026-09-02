package io.github.cowwoc.cat.common.optimization.promptsystem;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Frozen optimization target identity.
 *
 * @param schemaVersion the document schema version
 * @param targetId the immutable target identifier
 * @param targetPath the target's project-relative path
 * @param targetSha256 the target content fingerprint
 * @param promptTestIds the target's prompt-test identifiers
 */
public record OptimizationTargetDocument(int schemaVersion, String targetId, String targetPath, String targetSha256,
	List<String> promptTestIds)
{
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	/**
	 * Validates one target.
	 *
	 * @param schemaVersion the document schema version
	 * @param targetId the immutable target identifier
	 * @param targetPath the target's project-relative path
	 * @param targetSha256 the target content fingerprint
	 * @param promptTestIds the target's prompt-test identifiers
	 */
	public OptimizationTargetDocument
	{
		if (schemaVersion != 1)
			throw new IllegalArgumentException("Unsupported optimization target schema version: " + schemaVersion);
		if (targetId == null || targetId.isBlank() || targetPath == null || targetPath.isBlank() ||
			targetPath.startsWith("/") || targetPath.contains(".."))
			throw new IllegalArgumentException("Invalid optimization target identity");
		if (targetSha256 == null || !SHA256.matcher(targetSha256).matches())
			throw new IllegalArgumentException("targetSha256 must be lowercase SHA-256");
		if (promptTestIds == null || promptTestIds.stream().anyMatch(id -> id == null || id.isBlank()))
			throw new IllegalArgumentException("promptTestIds must contain nonblank identifiers");
		promptTestIds = List.copyOf(promptTestIds);
	}
}
