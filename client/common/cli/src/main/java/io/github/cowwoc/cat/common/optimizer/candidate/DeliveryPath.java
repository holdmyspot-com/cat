package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One user-visible path and its deterministic transaction-owned private siblings.
 *
 * @param ordinal zero-based path plan order
 * @param target project-relative user path
 * @param selectedStage private selected-content stage
 * @param predecessorCapture private captured predecessor
 * @param rollbackCapture private captured rollback target
 * @param before expected predecessor state
 * @param after selected candidate state
 * @param selectedLinkSha256 expected linked selected hash after publication
 * @param restoreLinkSha256 expected linked predecessor hash during restore
 * @param preservedEvidenceSha256 retained divergent bytes when present
 * @param phase durable per-path phase
 */
public record DeliveryPath(int ordinal, Path target, Path selectedStage, Path predecessorCapture,
	Path rollbackCapture, DeliveryFileState before, DeliveryFileState after, Optional<String> selectedLinkSha256,
	Optional<String> restoreLinkSha256, Optional<String> preservedEvidenceSha256, DeliveryPathPhase phase)
{
	/** Validates every pathname and current phase. */
	public DeliveryPath
	{
		if (ordinal < 0)
			throw new IllegalArgumentException("Delivery path ordinal must be nonnegative");
		validateRelative(target);
		validateRelative(selectedStage);
		validateRelative(predecessorCapture);
		validateRelative(rollbackCapture);
		if (target.equals(selectedStage) || target.equals(predecessorCapture) || target.equals(rollbackCapture) ||
			selectedStage.equals(predecessorCapture) || selectedStage.equals(rollbackCapture) ||
			predecessorCapture.equals(rollbackCapture))
			throw new IllegalArgumentException("Delivery private siblings must be distinct from all other paths");
		Objects.requireNonNull(before, "before");
		Objects.requireNonNull(after, "after");
		Objects.requireNonNull(selectedLinkSha256, "selectedLinkSha256");
		Objects.requireNonNull(restoreLinkSha256, "restoreLinkSha256");
		Objects.requireNonNull(preservedEvidenceSha256, "preservedEvidenceSha256");
		Objects.requireNonNull(phase, "phase");
		for (String hash: selectedLinkSha256.stream().toList())
			validateHash(hash);
		for (String hash: restoreLinkSha256.stream().toList())
			validateHash(hash);
		for (String hash: preservedEvidenceSha256.stream().toList())
			validateHash(hash);
		if ((phase == DeliveryPathPhase.SELECTED_LINKED || phase == DeliveryPathPhase.SELECTED_DEALIASED ||
			phase == DeliveryPathPhase.PATH_APPLIED && after.presence() == DeliveryPresence.REGULAR) &&
			(selectedLinkSha256.isEmpty() || !selectedLinkSha256.orElseThrow().equals(
				after.contentSha256().orElseThrow())))
			throw new IllegalArgumentException("Selected linked phase requires its exact after hash");
		if ((phase == DeliveryPathPhase.RESTORE_LINKED || phase == DeliveryPathPhase.RESTORED_DEALIASED) &&
			(restoreLinkSha256.isEmpty() || !restoreLinkSha256.orElseThrow().equals(
				before.contentSha256().orElseThrow())))
			throw new IllegalArgumentException("Restore linked phase requires its exact before hash");
	}

	/**
	 * Validates a persisted project-relative pathname without normalization loss.
	 *
	 * @param path persisted path
	 */
	static void validateRelative(Path path)
	{
		Objects.requireNonNull(path, "path");
		if (path.isAbsolute() || path.toString().isEmpty() || !path.equals(path.normalize()))
			throw new IllegalArgumentException("Delivery path must be normalized and project-relative: " + path);
	}

	/**
	 * Derives a deterministic private sibling in the CAT-owned transaction namespace.
	 *
	 * @param target user-visible path
	 * @param transactionId validated transaction identity
	 * @param ordinal path plan index
	 * @param role stage, predecessor, or rollback
	 * @return private sibling path
	 */
	static Path privateSibling(Path target, String transactionId, int ordinal, String role)
	{
		validateRelative(target);
		return target.resolveSibling(".cat-delivery-" + transactionId + "-" + ordinal + "-" + role);
	}

	/**
	 * Validates a recorded hash.
	 *
	 * @param hash content identity
	 */
	private static void validateHash(String hash)
	{
		if (!PromptSnapshot.isSha256(hash))
			throw new IllegalArgumentException("Delivery linked or preserved hash is invalid");
	}
}
