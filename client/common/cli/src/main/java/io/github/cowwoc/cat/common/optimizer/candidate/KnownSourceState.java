package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact state of one known prompt-system source at confirmation.
 *
 * @param path normalized project-relative source path
 * @param presence file presence
 * @param contentSha256 exact-byte hash only for a regular file
 */
public record KnownSourceState(Path path, KnownSourcePresence presence, Optional<String> contentSha256)
{
	/** Validates path shape and the presence/hash relationship. */
	public KnownSourceState
	{
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(presence, "presence");
		Objects.requireNonNull(contentSha256, "contentSha256");
		if (path.isAbsolute() || path.toString().isEmpty() || !path.equals(path.normalize()))
			throw new IllegalArgumentException("Known source path must be normalized and project-relative: " + path);
		if (presence == KnownSourcePresence.ABSENT && contentSha256.isPresent() ||
			presence == KnownSourcePresence.REGULAR &&
				(contentSha256.isEmpty() || !PromptSnapshot.isSha256(contentSha256.orElseThrow())))
			throw new IllegalArgumentException("Known source hash must exist exactly for REGULAR presence");
	}
}
