package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Binds a selected project root to an immutable starting snapshot manifest.
 *
 * @param projectRoot the absolute normalized project root
 * @param snapshotManifestRef the immutable starting snapshot manifest
 */
public record ProjectExecutionSnapshot(Path projectRoot, EvidenceRef snapshotManifestRef)
{
	/**
	 * Validates a pinned project root and its starting snapshot identity.
	 *
	 * @param projectRoot the absolute normalized project root
	 * @param snapshotManifestRef the immutable starting snapshot manifest
	 * @throws NullPointerException if {@code projectRoot} or {@code snapshotManifestRef} are null
	 * @throws IllegalArgumentException if {@code projectRoot} is relative or not normalized
	 */
	public ProjectExecutionSnapshot
	{
		Objects.requireNonNull(projectRoot, "projectRoot");
		Objects.requireNonNull(snapshotManifestRef, "snapshotManifestRef");
		if (!projectRoot.isAbsolute() || !projectRoot.equals(projectRoot.normalize()))
			throw new IllegalArgumentException("projectRoot must be absolute and normalized");
	}
}
