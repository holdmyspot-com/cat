package io.github.cowwoc.cat.common.optimizer.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Addresses immutable content by collection and SHA-256 identity.
 *
 * @param collection an evidence collection matching {@code [a-z][a-z0-9-]*}
 * @param evidenceId exactly 64 lowercase hexadecimal characters identifying content
 */
public record EvidenceRef(String collection, String evidenceId)
{
	private static final Pattern COLLECTION_NAME = Pattern.compile("[a-z][a-z0-9-]*");
	private static final Pattern EVIDENCE_ID = Pattern.compile("[0-9a-f]{64}");

	/**
	 * Validates an immutable evidence address.
	 *
	 * @param collection an evidence collection matching {@code [a-z][a-z0-9-]*}
	 * @param evidenceId exactly 64 lowercase hexadecimal characters identifying content
	 * @throws NullPointerException if {@code collection} or {@code evidenceId} are null
	 * @throws IllegalArgumentException if {@code collection} or {@code evidenceId} are malformed
	 */
	public EvidenceRef
	{
		Objects.requireNonNull(collection, "collection");
		Objects.requireNonNull(evidenceId, "evidenceId");
		if (!COLLECTION_NAME.matcher(collection).matches())
			throw new IllegalArgumentException("collection must match [a-z][a-z0-9-]*");
		if (!EVIDENCE_ID.matcher(evidenceId).matches())
			throw new IllegalArgumentException("evidenceId must contain exactly 64 lowercase hexadecimal characters");
	}
}
