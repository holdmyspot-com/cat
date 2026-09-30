package io.github.cowwoc.cat.common.optimizer.candidate;

import java.util.List;
import java.util.Objects;

/**
 * The versioned durable manifest for one prompt candidate.
 *
 * @param schemaVersion durable schema version
 * @param candidateId independently derived candidate identity
 * @param entries ordered immutable prompt entries
 */
public record SnapshotManifest(int schemaVersion, String candidateId, List<SnapshotEntry> entries)
{
	/** The only supported durable manifest schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates the immutable manifest shape. */
	public SnapshotManifest
	{
		if (schemaVersion != SCHEMA_VERSION)
			throw new IllegalArgumentException("Unsupported snapshot manifest schema: " + schemaVersion);
		if (!PromptSnapshot.isSha256(candidateId))
			throw new IllegalArgumentException("Snapshot candidate id must be a SHA-256 identity");
		entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
	}
}
