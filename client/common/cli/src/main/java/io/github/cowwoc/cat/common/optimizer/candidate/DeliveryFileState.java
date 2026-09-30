package io.github.cowwoc.cat.common.optimizer.candidate;

import java.util.Objects;
import java.util.Optional;

/**
 * Exact visible file state recorded before or after a transaction path.
 *
 * @param presence absent or regular
 * @param kind regular-file kind only when present
 * @param contentSha256 exact-byte identity only when present
 */
public record DeliveryFileState(DeliveryPresence presence, Optional<PromptSnapshot.FileKind> kind,
	Optional<String> contentSha256)
{
	/** Validates presence, kind, and hash as one state. */
	public DeliveryFileState
	{
		Objects.requireNonNull(presence, "presence");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(contentSha256, "contentSha256");
		if (presence == DeliveryPresence.ABSENT && (kind.isPresent() || contentSha256.isPresent()))
			throw new IllegalArgumentException("ABSENT delivery path cannot carry a kind or content hash");
		if (presence == DeliveryPresence.REGULAR && (kind.isEmpty() ||
			kind.orElseThrow() != PromptSnapshot.FileKind.REGULAR || contentSha256.isEmpty() ||
			!PromptSnapshot.isSha256(contentSha256.orElseThrow())))
			throw new IllegalArgumentException("REGULAR delivery path requires a regular kind and SHA-256 hash");
	}

	/**
	 * Creates an absent state.
	 *
	 * @return absent file state
	 */
	public static DeliveryFileState absent()
	{
		return new DeliveryFileState(DeliveryPresence.ABSENT, Optional.empty(), Optional.empty());
	}

	/**
	 * Creates a regular state.
	 *
	 * @param hash exact-byte SHA-256
	 * @return regular file state
	 */
	public static DeliveryFileState regular(String hash)
	{
		return new DeliveryFileState(DeliveryPresence.REGULAR, Optional.of(PromptSnapshot.FileKind.REGULAR),
			Optional.of(hash));
	}
}
