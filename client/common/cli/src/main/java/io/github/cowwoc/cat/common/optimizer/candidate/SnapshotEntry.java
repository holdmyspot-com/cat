package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One immutable prompt artifact in a snapshot manifest.
 *
 * @param relativePath normalized project-relative artifact path
 * @param kind supported artifact kind
 * @param contentSha256 exact-byte content identity
 */
public record SnapshotEntry(Path relativePath, PromptSnapshot.FileKind kind, String contentSha256)
{
	/** Validates one portable manifest entry. */
	public SnapshotEntry
	{
		Objects.requireNonNull(relativePath, "relativePath");
		Objects.requireNonNull(kind, "kind");
		if (relativePath.isAbsolute() || relativePath.toString().isEmpty() ||
			!relativePath.equals(relativePath.normalize()))
			throw new IllegalArgumentException("Snapshot path must be a normalized project-relative path: " +
				relativePath);
		if (!PromptSnapshot.isSha256(contentSha256))
			throw new IllegalArgumentException("Snapshot content hash must be a SHA-256 identity");
	}
}
