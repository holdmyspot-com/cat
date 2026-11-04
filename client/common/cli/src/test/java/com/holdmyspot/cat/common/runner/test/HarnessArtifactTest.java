package com.holdmyspot.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
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
	 * The plugin manifest, relative to the artifact.
	 */
	private static final Path MANIFEST = Path.of(".harness/plugin.json");
	/**
	 * The full plugin version that the artifacts state.
	 */
	private static final PluginVersion VERSION = PluginVersion.parse("1.0+b0123456789ab");

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

			requireThat(HarnessArtifact.validate(artifact, MANIFEST, VERSION, Platform.LINUX_X64),
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

			requireThat(HarnessArtifact.validate(artifact, MANIFEST, VERSION, Platform.WINDOWS_X64),
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

			HarnessArtifact.validate(artifact, MANIFEST, VERSION, Platform.WINDOWS_X64);
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

			HarnessArtifact.validate(artifact, MANIFEST, VERSION, Platform.LINUX_X64);
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

			HarnessArtifact.validate(artifact, MANIFEST, VERSION, Platform.LINUX_X64);
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

			HarnessArtifact.validate(artifact, MANIFEST, VERSION, Platform.LINUX_X64);
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

			HarnessArtifact.validate(artifact, MANIFEST, VERSION, Platform.LINUX_X64);
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

			HarnessArtifact.validate(artifact, MANIFEST, VERSION, Platform.LINUX_X64);
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

			HarnessArtifact.validate(artifact, MANIFEST, VERSION, Platform.LINUX_X64);
		}
	}

	/**
	 * Verifies that an artifact of another build of the same release is refused, because a runner accepts only an
	 * artifact of its own full plugin version.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void rejectsArtifactOfAnotherBuild() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, false);
			PluginVersion other = PluginVersion.parse("1.0+bfedcba987654");
			try
			{
				HarnessArtifact.validate(artifact, MANIFEST, other, Platform.LINUX_X64);
				throw new AssertionError("An artifact of another build was accepted");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").isEqualTo("Artifact version " + VERSION +
					" does not match CAT " + other);
			}
		}
	}

	/**
	 * Verifies that an artifact whose version file states only the release, without the build, is refused.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		"Artifact version file .*VERSION does not state a CAT plugin version, such as .*: \"1\\.0\"")
	public void rejectsArtifactWithReleaseOnlyVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, false);
			Files.writeString(artifact.resolve("bin/VERSION"), "1.0\n");

			HarnessArtifact.validate(artifact, MANIFEST, VERSION, Platform.LINUX_X64);
		}
	}

	/**
	 * Verifies that the runner launcher of the plugin that holds the running code is found from that plugin's own full
	 * version, whatever its build.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void findsRunnerOfOwnPlugin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, false);
			Path runner = artifact.resolve("bin/harness-runner");
			Files.writeString(runner, "#!/bin/sh\n");
			PortableFiles.setMode(runner, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));

			requireThat(HarnessArtifact.runnerLauncher(artifact, MANIFEST, "harness-runner", Platform.LINUX_X64),
				"runner").isEqualTo(runner);
			requireThat(HarnessArtifact.version(artifact), "version").isEqualTo(VERSION);
		}
	}

	/**
	 * Verifies that the plugin that holds the running code is refused when its version names another release than the
	 * one that its code was built for.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		"Artifact version 2\\.0\\+b0123456789ab is not of CAT release 1\\.0: .*")
	public void rejectsOwnPluginOfAnotherRelease() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = createArtifact(cleanup, false);
			Files.writeString(artifact.resolve("bin/VERSION"), "2.0+b0123456789ab\n");

			HarnessArtifact.runnerLauncher(artifact, MANIFEST, "harness-runner", Platform.LINUX_X64);
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
		Files.writeString(artifact.resolve("bin/VERSION"), VERSION + "\n");
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
