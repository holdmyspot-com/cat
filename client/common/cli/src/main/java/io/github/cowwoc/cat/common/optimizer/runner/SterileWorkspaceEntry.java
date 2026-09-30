package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Names one regular-file input in a sterile workspace manifest.
 *
 * @param relativePath the normalized relative location in the sterile workspace
 * @param role whether the input carries authority or inert evidence
 * @param contentEvidence the immutable bytes to materialize
 * @param sha256 the lowercase SHA-256 identity of those bytes
 */
public record SterileWorkspaceEntry(Path relativePath, SterileWorkspaceInputRole role,
	EvidenceRef contentEvidence, String sha256)
{
	/**
	 * Validates the location and immutable byte identity of a sterile input.
	 *
	 * @param relativePath the normalized relative location in the sterile workspace
	 * @param role whether the input carries authority or inert evidence
	 * @param contentEvidence the immutable bytes to materialize
	 * @param sha256 the lowercase SHA-256 identity of those bytes
	 * @throws NullPointerException if any parameter is null
	 * @throws IllegalArgumentException if the path or hash is invalid
	 */
	public SterileWorkspaceEntry
	{
		Objects.requireNonNull(relativePath, "relativePath");
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(contentEvidence, "contentEvidence");
		Objects.requireNonNull(sha256, "sha256");
		if (relativePath.isAbsolute() || relativePath.toString().isEmpty() ||
			relativePath.toString().equals(".") ||
			!relativePath.equals(relativePath.normalize()))
		{
			throw new IllegalArgumentException("relativePath must be a nonempty normalized relative path");
		}
		for (Path segment : relativePath)
		{
			if (segment.toString().equals(".."))
				throw new IllegalArgumentException("relativePath must not traverse outside the workspace");
			switch (segment.toString())
			{
				case ".cat", ".claude", ".codex", "AGENTS.md", "CLAUDE.md" ->
					throw new IllegalArgumentException("relativePath must not contain host instruction or state paths");
				default ->
				{
				}
			}
		}
		if (!sha256.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("sha256 must contain exactly 64 lowercase hexadecimal characters");
	}
}
