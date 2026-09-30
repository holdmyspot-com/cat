package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of inspecting and resolving an interrupted delivery transaction.
 *
 * @param disposition terminal recovery outcome
 * @param transactionId recovered transaction identity when present
 * @param receiptId archived receipt identity when present
 * @param affectedPaths ordered paths considered by the transaction
 */
public record DeliveryRecovery(Disposition disposition, Optional<String> transactionId,
	Optional<String> receiptId, List<Path> affectedPaths)
{
	/** Terminal recovery outcome. */
	public enum Disposition
	{
		NONE,
		APPLIED,
		ROLLED_BACK,
		CONCURRENT_EDIT_PRESERVED
	}

	/** Validates the complete recovery result. */
	public DeliveryRecovery
	{
		Objects.requireNonNull(disposition, "disposition");
		transactionId = Objects.requireNonNull(transactionId, "transactionId");
		receiptId = Objects.requireNonNull(receiptId, "receiptId");
		affectedPaths = List.copyOf(Objects.requireNonNull(affectedPaths, "affectedPaths"));
		if ((disposition == Disposition.NONE) != (transactionId.isEmpty() && receiptId.isEmpty() &&
			affectedPaths.isEmpty()))
			throw new IllegalArgumentException("Recovery identity and disposition disagree");
		if (disposition != Disposition.NONE && (transactionId.isEmpty() || receiptId.isEmpty()))
			throw new IllegalArgumentException("Terminal recovery requires transaction and receipt identities");
	}

	/** Returns an empty result when no transaction needs recovery. */
	public static DeliveryRecovery none()
	{
		return new DeliveryRecovery(Disposition.NONE, Optional.empty(), Optional.empty(), List.of());
	}
}
