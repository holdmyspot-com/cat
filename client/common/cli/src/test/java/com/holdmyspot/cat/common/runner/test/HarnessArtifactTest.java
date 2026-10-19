package com.holdmyspot.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
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
			Path artifact = createArtifact(cleanup, false);

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
			Path artifact = createArtifact(cleanup, true);

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
			Path artifact = createArtifact(cleanup, false);

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
			Path artifact = createArtifact(cleanup, false);
			Files.delete(artifact.resolve("bin/plugin-info"));

			HarnessArtifact.validate(artifact, Path.of(".harness/plugin.json"), Platform.LINUX_X64);
		}
	}

	/**
	 * Creates a complete minimal harness artifact.
	 *
	 * @param cleanup   the temporary-directory cleanup scope
	 * @param batch     {@code true} for the batch launchers that an artifact built for Windows holds, {@code false}
	 *                  for POSIX launchers
	 *
	 * @return the unpacked plugin directory
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	private static Path createArtifact(TemporaryDirectories cleanup, boolean batch) throws IOException
	{
		Path artifact = cleanup.add(Files.createTempDirectory("cat-artifact-"));
		Files.createDirectories(artifact.resolve("bin"));
		Files.createDirectories(artifact.resolve("client/bin"));
		Files.createDirectories(artifact.resolve(".harness"));
		Files.writeString(artifact.resolve("client/VERSION"), PluginInfo.CAT_VERSION);
		Files.writeString(artifact.resolve(".harness/plugin.json"), "{}");
		Set<PosixFilePermission> permissions = Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
		Path pluginInfo;
		Path sessionStart;
		if (batch)
		{
			pluginInfo = artifact.resolve("bin/plugin-info.cmd");
			sessionStart = artifact.resolve("client/bin/session-start.bat");
		}
		else
		{
			pluginInfo = artifact.resolve("bin/plugin-info");
			sessionStart = artifact.resolve("client/bin/session-start");
		}
		Files.writeString(pluginInfo, "#!/bin/sh\n");
		Files.writeString(sessionStart, "#!/bin/sh\n");
		PortableFiles.setMode(pluginInfo, permissions);
		PortableFiles.setMode(sessionStart, permissions);
		return artifact;
	}
}
