package io.github.cowwoc.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessArtifact;
import org.testng.annotations.Test;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests {@link HarnessArtifact}.
 */
public final class HarnessArtifactTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessArtifactTest()
	{
	}

	/**
	 * Verifies a complete harness artifact is accepted.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void acceptsCompleteArtifact() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, "");

			requireThat(HarnessArtifact.validate(artifact, Path.of(".harness/plugin.json"), Platform.LINUX_X64),
				"artifact").isEqualTo(artifact);
		}
	}

	/**
	 * Verifies that an artifact built for Windows is accepted with only batch launchers, as the Codex image holds there.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void acceptsWindowsArtifactWithBatchLaunchers() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, ".bat");

			requireThat(HarnessArtifact.validate(artifact, Path.of(".harness/plugin.json"), Platform.WINDOWS_X64),
				"artifact").isEqualTo(artifact);
		}
	}

	/**
	 * Verifies that an artifact without batch launchers is rejected on Windows, where they are the launchers that run.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void windowsArtifactNeedsBatchLaunchers() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, "");

			HarnessArtifact.validate(artifact, Path.of(".harness/plugin.json"), Platform.WINDOWS_X64);
		}
	}

	/**
	 * Verifies a missing executable launcher is rejected.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsMissingExecutableLauncher() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, "");
			Files.delete(artifact.resolve("client/bin/plugin-info"));

			HarnessArtifact.validate(artifact, Path.of(".harness/plugin.json"), Platform.LINUX_X64);
		}
	}

	/**
	 * Creates a complete minimal harness artifact.
	 *
	 * @param cleanup   the temporary-directory cleanup scope
	 * @param extension the launchers' file extension, such as {@code ".bat"}, or {@code ""} for POSIX launchers
	 *
	 * @return the unpacked plugin directory
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	private static Path createArtifact(TemporaryDirectories cleanup, String extension) throws IOException
	{
		Path artifact = cleanup.add(Files.createTempDirectory("cat-artifact-"));
		Files.createDirectories(artifact.resolve("client/bin"));
		Files.createDirectories(artifact.resolve(".harness"));
		Files.writeString(artifact.resolve("client/VERSION"), PluginInfo.CAT_VERSION);
		Files.writeString(artifact.resolve(".harness/plugin.json"), "{}");
		Set<PosixFilePermission> permissions = Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
		Path pluginInfo = artifact.resolve("client/bin/plugin-info" + extension);
		Path sessionStart = artifact.resolve("client/bin/session-start" + extension);
		Files.writeString(pluginInfo, "#!/bin/sh\n");
		Files.writeString(sessionStart, "#!/bin/sh\n");
		PortableFiles.setMode(pluginInfo, permissions);
		PortableFiles.setMode(sessionStart, permissions);
		return artifact;
	}
}
