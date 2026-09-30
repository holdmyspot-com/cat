package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Binds the selected target's source relationship to exact initial bytes and the pinned Git inventory.
 *
 * @param schemaVersion the capture schema
 * @param campaignId the campaign that owns the capture
 * @param preparation the preparation scope that selected the target
 * @param projectRoot the registered base worktree root
 * @param targetPath the project-relative target prompt path
 * @param resolvedSourcePath the project-relative regular file supplying the selected target's bytes
 * @param selectedLinkTarget the selected link's exact destination text, or empty for a regular target
 * @param selectedDirectoryLinks directory links traversed to reach the selected regular file
 * @param sourceSha256 SHA-256 of the selected target's captured file bytes
 * @param inventoryRef the before-call Git inventory
 * @param snapshotId the exact-byte project snapshot containing the resolved source
 */
public record InitialTargetCapture(int schemaVersion, String campaignId, PreparationAuthority preparation,
	Path projectRoot, Path targetPath, Path resolvedSourcePath, String selectedLinkTarget,
	List<PromptSource.DirectoryLink> selectedDirectoryLinks, String sourceSha256, EvidenceRef inventoryRef,
	String snapshotId)
{
	/** The supported capture schema. */
	public static final int SCHEMA_VERSION = 3;

	/**
	 * Validates the capture's immutable identity.
	 *
	 * @param schemaVersion the capture schema
	 * @param campaignId the campaign that owns the capture
	 * @param preparation the preparation scope that selected the target
	 * @param projectRoot the registered base worktree root
	 * @param targetPath the project-relative target prompt path
	 * @param resolvedSourcePath the project-relative regular file supplying the selected target's bytes
	 * @param selectedLinkTarget the selected link's exact destination text, or empty for a regular target
	 * @param selectedDirectoryLinks directory links traversed to reach the selected regular file
	 * @param sourceSha256 SHA-256 of the selected target's captured file bytes
	 * @param inventoryRef the before-call Git inventory
	 * @param snapshotId the exact-byte project snapshot containing the resolved source
	 */
	public InitialTargetCapture
	{
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank())
			throw new IllegalArgumentException("Initial target capture identity is invalid");
		Objects.requireNonNull(preparation, "preparation");
		Objects.requireNonNull(projectRoot, "projectRoot");
		Objects.requireNonNull(targetPath, "targetPath");
		Objects.requireNonNull(resolvedSourcePath, "resolvedSourcePath");
		Objects.requireNonNull(selectedLinkTarget, "selectedLinkTarget");
		selectedDirectoryLinks = List.copyOf(selectedDirectoryLinks);
		Objects.requireNonNull(inventoryRef, "inventoryRef");
		if (!preparation.campaignId().equals(campaignId) ||
			preparation.kind() != PreparationAuthority.Kind.INITIAL)
			throw new IllegalArgumentException("Initial target capture preparation is foreign");
		if (!projectRoot.isAbsolute() || !projectRoot.equals(projectRoot.normalize()))
			throw new IllegalArgumentException("Initial target capture project root is invalid: " + projectRoot);
		if (targetPath.isAbsolute() || !targetPath.equals(targetPath.normalize()) ||
			targetPath.toString().isEmpty() || targetPath.startsWith(".."))
			throw new IllegalArgumentException("Initial target capture path is invalid: " + targetPath);
		if (resolvedSourcePath.isAbsolute() || !resolvedSourcePath.equals(resolvedSourcePath.normalize()) ||
			resolvedSourcePath.toString().isEmpty() || resolvedSourcePath.startsWith(".."))
			throw new IllegalArgumentException("Initial target resolved source path is invalid: " + resolvedSourcePath);
		if ((selectedLinkTarget.isEmpty() && selectedDirectoryLinks.isEmpty()) !=
			targetPath.equals(resolvedSourcePath))
			throw new IllegalArgumentException("Initial target link relationship is invalid");
		if (sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Initial target source hash is invalid");
		if (!inventoryRef.collection().equals("initial-git-inventories"))
			throw new IllegalArgumentException("Initial target capture inventory collection is invalid");
		if (snapshotId == null || !snapshotId.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Initial target capture snapshot identity is invalid");
	}

	/**
	 * Returns the captured selected source with its complete retained link relationship.
	 *
	 * @return selected logical path, resolved file, link identity, and byte hash
	 */
	public PromptSource source()
	{
		return new PromptSource(targetPath, resolvedSourcePath, selectedLinkTarget,
			selectedDirectoryLinks, sourceSha256);
	}
}
