package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Create-once binding from one terminal transaction to its immutable archived receipt.
 *
 * @param schemaVersion exact index schema
 * @param transactionId owning transaction
 * @param receiptRef content-addressed receipt
 * @param finalTransactionSha256 terminal journal phase hash
 * @param outcome terminal outcome
 */
record DeliveryReceiptIndex(int schemaVersion, String transactionId, EvidenceRef receiptRef,
	String finalTransactionSha256, DeliveryReceiptOutcome outcome)
{
	static final int SCHEMA_VERSION = 1;

	/** Validates the immutable index shape. */
	DeliveryReceiptIndex
	{
		if (schemaVersion != SCHEMA_VERSION || transactionId == null ||
			!transactionId.matches("[a-z0-9-]{1,100}"))
			throw new IllegalArgumentException("Delivery receipt index identity is invalid");
		Objects.requireNonNull(receiptRef, "receiptRef");
		if (!receiptRef.collection().equals("delivery-receipts") ||
			!PromptSnapshot.isSha256(finalTransactionSha256))
			throw new IllegalArgumentException("Delivery receipt index content identity is invalid");
		Objects.requireNonNull(outcome, "outcome");
	}
}
