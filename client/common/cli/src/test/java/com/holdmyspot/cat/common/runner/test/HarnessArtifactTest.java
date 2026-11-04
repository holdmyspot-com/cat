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
import com.holdmyspot.cat.common.release.fakes.test.FakeRelease;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.RuntimeBinding;
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
	 * Verifies that an artifact whose version and hook launcher are in a {@code client/} folder, where plugins kept
	 * them before {@code bin/}, is rejected.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsClientFolderLayout() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, false);
			Path client = Files.createDirectories(artifact.resolve("client/bin"));
			Files.move(artifact.resolve("bin/VERSION"), client.resolveSibling("VERSION"));
			Files.move(artifact.resolve("bin/session-start"), client.resolve("session-start"));

			HarnessArtifact.validate(artifact, Path.of(".harness/plugin.json"), Platform.LINUX_X64);
		}
	}

	/**
	 * Verifies that a plugin directory without a runtime binding is rejected with a message that names the binding,
	 * because its launchers cannot find Java.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*bin[/\\\\]runtime-binding.*")
	public void rejectsArtifactWithoutBinding() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, false);
			Files.delete(artifact.resolve(RuntimeBinding.PATH));

			HarnessArtifact.validate(artifact, Path.of(".harness/plugin.json"), Platform.LINUX_X64);
		}
	}

	/**
	 * Verifies that a binding that names a relative path is rejected, because a launcher resolves Java only from an
	 * absolute binding.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*bin[/\\\\]runtime-binding.*")
	public void rejectsRelativeBinding() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, false);
			Files.writeString(artifact.resolve(RuntimeBinding.PATH), "runtime\n");

			HarnessArtifact.validate(artifact, Path.of(".harness/plugin.json"), Platform.LINUX_X64);
		}
	}

	/**
	 * Verifies that a binding with a second line is rejected, because a launcher accepts exactly one line.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*bin[/\\\\]runtime-binding.*")
	public void rejectsMultiLineBinding() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, false);
			Path binding = artifact.resolve(RuntimeBinding.PATH);
			Files.writeString(binding, Files.readString(binding) + Files.readString(binding));

			HarnessArtifact.validate(artifact, Path.of(".harness/plugin.json"), Platform.LINUX_X64);
		}
	}

	/**
	 * Verifies that a binding to a directory without an executable {@code java} program is rejected, because the
	 * launchers could not run it.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*bin[/\\\\]runtime-binding.*")
	public void rejectsBindingWithoutJava() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, false);
			Files.delete(RuntimeBinding.read(artifact).resolve("bin/java"));

			HarnessArtifact.validate(artifact, Path.of(".harness/plugin.json"), Platform.LINUX_X64);
		}
	}

	/**
	 * Creates a complete minimal harness artifact, bound to a runtime of the platform that its launchers are built for.
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
		Files.createDirectories(artifact.resolve(".harness"));
		Files.writeString(artifact.resolve("bin/VERSION"), PluginInfo.CAT_VERSION);
		Files.writeString(artifact.resolve(".harness/plugin.json"), "{}");
		Set<PosixFilePermission> permissions = Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
		Path pluginInfo;
		Path sessionStart;
		if (batch)
		{
			pluginInfo = artifact.resolve("bin/plugin-info.cmd");
			sessionStart = artifact.resolve("bin/session-start.cmd");
		}
		else
		{
			pluginInfo = artifact.resolve("bin/plugin-info");
			sessionStart = artifact.resolve("bin/session-start");
		}
		Files.writeString(pluginInfo, "#!/bin/sh\n");
		Files.writeString(sessionStart, "#!/bin/sh\n");
		PortableFiles.setMode(pluginInfo, permissions);
		PortableFiles.setMode(sessionStart, permissions);
		Platform platform = Platform.LINUX_X64;
		if (batch)
			platform = Platform.WINDOWS_X64;
		RuntimeBinding.write(artifact, FakeRelease.runtime(cleanup.add(Files.createTempDirectory("cat-runtime-")),
			platform));
		return artifact;
	}
}
