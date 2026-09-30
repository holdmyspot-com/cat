package io.github.cowwoc.cat.common.optimizer.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;

import org.testng.annotations.Test;

/**
 * Tests sterile workspace input path and evidence constraints.
 */
public final class SterileWorkspaceEntryTest
{
	/**
	 * Creates a test instance.
	 */
	public SterileWorkspaceEntryTest()
	{
	}

	/**
	 * Verifies an ordinary relative input retains its declared role.
	 */
	@Test
	public void acceptsRelativeRegularInput()
	{
		SterileWorkspaceEntry entry = entry(Path.of("guidance/prompt.md"), "a".repeat(64));
		requireThat(entry.relativePath(), "relativePath").isEqualTo(Path.of("guidance/prompt.md"));
		requireThat(entry.role(), "role").isEqualTo(SterileWorkspaceInputRole.INERT_EVIDENCE);
	}

	/**
	 * Verifies an absolute input cannot escape the sterile workspace root.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsAbsolutePath()
	{
		entry(Path.of("/outside.md"), "a".repeat(64));
	}

	/**
	 * Verifies an unnormalized parent traversal cannot be admitted.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsParentTraversal()
	{
		entry(Path.of("guidance/../outside.md"), "a".repeat(64));
	}

	/**
	 * Verifies a nested host instruction filename cannot enter a sterile manifest.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsNestedHostInstructions()
	{
		entry(Path.of("guidance/AGENTS.md"), "a".repeat(64));
	}

	/**
	 * Verifies the recorded byte identity must be a lowercase SHA-256 value.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsShortContentHash()
	{
		entry(Path.of("guidance/prompt.md"), "a".repeat(63));
	}

	/**
	 * Creates one sterile input fixture.
	 *
	 * @param path the path being tested
	 * @param hash the supplied content identity
	 * @return the input entry
	 */
	private static SterileWorkspaceEntry entry(Path path, String hash)
	{
		return new SterileWorkspaceEntry(path, SterileWorkspaceInputRole.INERT_EVIDENCE,
			new EvidenceRef("sterile-inputs", "a".repeat(64)), hash);
	}
}
