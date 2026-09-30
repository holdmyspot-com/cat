package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable bytes of a competing user target retained before transaction cleanup.
 *
 * @param schemaVersion exact evidence schema
 * @param transactionId owning transaction
 * @param target project-relative competing target
 * @param contentSha256 inspected content identity
 * @param contentBase64 exact no-follow bytes
 */
record DeliveryPreservedEvidence(int schemaVersion, String transactionId, Path target,
	String contentSha256, String contentBase64)
{
	static final int SCHEMA_VERSION = 1;

	/** Validates the bounded exact preservation record. */
	DeliveryPreservedEvidence
	{
		if (schemaVersion != SCHEMA_VERSION)
			throw new IllegalArgumentException("Unsupported preserved-evidence schema");
		Objects.requireNonNull(transactionId, "transactionId");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(contentSha256, "contentSha256");
		Objects.requireNonNull(contentBase64, "contentBase64");
		byte[] bytes = java.util.Base64.getDecoder().decode(contentBase64);
		if (!PromptSnapshot.sha256(bytes).equals(contentSha256))
			throw new IllegalArgumentException("Preserved target hash differs from exact bytes");
	}
}
