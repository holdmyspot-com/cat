package io.github.cowwoc.cat.common.optimizer.candidate;

import java.util.Objects;
import java.util.Optional;

/**
 * A model-supplied expected path state that C3 compares with freshly inspected live bytes.
 *
 * @param presence expected path presence
 * @param contentSha256 exact-byte hash only when regular
 */
public record GenerationExpectedState(Presence presence, Optional<String> contentSha256)
{
	/** Validates the expected presence/hash shape. */
	public GenerationExpectedState
	{
		Objects.requireNonNull(presence, "presence");
		Objects.requireNonNull(contentSha256, "contentSha256");
		if (presence == Presence.ABSENT && contentSha256.isPresent() ||
			presence == Presence.REGULAR &&
				(contentSha256.isEmpty() || !PromptSnapshot.isSha256(contentSha256.orElseThrow())))
			throw new IllegalArgumentException("Expected state hash must exist exactly for REGULAR presence");
	}

	/**
	 * Creates an absent expectation.
	 *
	 * @return absent expected state
	 */
	public static GenerationExpectedState absent()
	{
		return new GenerationExpectedState(Presence.ABSENT, Optional.empty());
	}

	/**
	 * Creates a regular-file expectation.
	 *
	 * @param hash expected SHA-256
	 * @return regular expected state
	 */
	public static GenerationExpectedState regular(String hash)
	{
		return new GenerationExpectedState(Presence.REGULAR, Optional.of(hash));
	}

	/** Expected path presence. */
	public enum Presence
	{
		/** Path absent. */
		ABSENT,
		/** Regular file present. */
		REGULAR
	}
}
