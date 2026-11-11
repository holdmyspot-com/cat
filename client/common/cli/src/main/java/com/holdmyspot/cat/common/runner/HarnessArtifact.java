package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.installation.PluginVersion;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Validates that an unpacked CAT plugin directory contains the required version file, manifest, and executable
 * launchers.
 */
public final class HarnessArtifact
{
	private static final int MAXIMUM_VERSION_BYTES = 1_048_576;

	/**
	 * Validates a complete unpacked plugin directory, built for the platform that this JVM runs on, for a caller-supplied
	 * plugin manifest. The plugin must state the full plugin version of the plugin that holds the running code, which
	 * that plugin's {@code bin/VERSION} states.
	 *
	 * @param root the unpacked plugin directory
	 *
	 * @param manifestPath the plugin manifest path relative to {@code root}
	 *
	 * @return the normalized unpacked plugin directory
	 *
	 * @throws IOException if the running code is not loaded from a CAT plugin, or the artifact is incomplete, is of
	 *   another plugin version, or does not satisfy the required filesystem layout
	 */
	public static Path validate(Path root, Path manifestPath) throws IOException
	{
		Path ownPlugin;
		try
		{
			ownPlugin = PluginDirectory.of(HarnessArtifact.class);
		}
		catch (IllegalStateException e)
		{
			throw new IOException(e.getMessage(), e);
		}
		return validate(root, manifestPath, version(ownPlugin), Platform.current());
	}

	/**
	 * Returns the full plugin version that a plugin's {@code bin/VERSION} states.
	 *
	 * @param plugin the plugin directory
	 *
	 * @return the plugin version
	 *
	 * @throws NullPointerException if {@code plugin} is null
	 * @throws IOException if the version file cannot be read or does not state a full plugin version
	 */
	public static PluginVersion version(Path plugin) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		Path file = plugin.toAbsolutePath().normalize().resolve(PluginDirectory.VERSION);
		String text = BoundedInput.readUtf8(file, MAXIMUM_VERSION_BYTES, "Artifact version").strip();
		try
		{
			return PluginVersion.parse(text);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Artifact version file " + file + " does not state a CAT plugin version, such as " +
				PluginInfo.CAT_VERSION + PluginVersion.BUILD_SEPARATOR + "0123456789ab: \"" + text + "\"", e);
		}
	}

	/**
	 * Validates a complete unpacked plugin directory, built for a platform, for a caller-supplied plugin manifest. The
	 * required launchers are {@code bin/plugin-info}, which agents run, and the hook launcher {@code bin/session-start}.
	 * On Windows, they are the batch launchers {@code bin/plugin-info.cmd} and {@code bin/session-start.cmd}, which
	 * {@code cmd.exe} and PowerShell run, because the Codex image there holds no POSIX launchers. Elsewhere, they are
	 * the executable POSIX launchers. The plugin's {@code bin/VERSION} must state {@code expected}, and its runtime
	 * binding must name a Java runtime whose {@code java} program the launchers can run.
	 *
	 * @param root the unpacked plugin directory
	 *
	 * @param manifestPath the plugin manifest path relative to {@code root}
	 *
	 * @param expected the full plugin version that the plugin must state
	 *
	 * @param platform the platform that the artifact was built for and runs on
	 *
	 * @return the normalized unpacked plugin directory
	 *
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the artifact is incomplete, is of another plugin version, does not satisfy the required
	 *   filesystem layout, or has no usable runtime binding
	 */
	public static Path validate(Path root, Path manifestPath, PluginVersion expected, Platform platform)
		throws IOException
	{
		requireThat(expected, "expected").isNotNull();
		Path normalized = validateLayout(root, manifestPath, platform);
		PluginVersion actual = version(normalized);
		if (!actual.equals(expected))
			throw new IOException("Artifact version " + actual + " does not match CAT " + expected);
		RuntimeBinding.readUsable(normalized, platform);
		return normalized;
	}

	/**
	 * Validates the files of an unpacked plugin directory, except its version and runtime binding.
	 *
	 * @param root the unpacked plugin directory
	 *
	 * @param manifestPath the plugin manifest path relative to {@code root}
	 *
	 * @param platform the platform that the artifact was built for and runs on
	 *
	 * @return the normalized unpacked plugin directory
	 *
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the artifact is incomplete or does not satisfy the required filesystem layout
	 */
	private static Path validateLayout(Path root, Path manifestPath, Platform platform) throws IOException
	{
		requireThat(root, "root").isNotNull();
		requireThat(manifestPath, "manifestPath").isNotNull();
		requireThat(platform, "platform").isNotNull();
		if (PortablePaths.isRooted(manifestPath) || manifestPath.normalize().startsWith(".."))
		{
			throw new IOException(
				"Artifact manifest path must be relative to the artifact directory and must not escape it: " +
				manifestPath +
					". Use a relative path below the artifact directory.");
		}
		Path normalized = root.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(normalized))
		{
			throw new IOException("Artifact directory is a symbolic link: " + normalized +
				". Use an extracted artifact directory, not a symbolic link.");
		}
		if (!Files.isDirectory(normalized, NOFOLLOW_LINKS))
		{
			throw new IOException("Artifact directory does not exist or is not a directory: " + normalized +
				". Extract the CAT artifact and use its directory.");
		}
		Path version = normalized.resolve(PluginDirectory.VERSION);
		Path manifest = normalized.resolve(manifestPath).normalize();
		if (!manifest.startsWith(normalized))
		{
			throw new IOException("Artifact manifest path resolves outside the artifact directory: " + manifestPath +
				". Use a relative path below the artifact directory.");
		}
		Path pluginInfo;
		Path sessionStart;
		if (platform.isWindows())
		{
			pluginInfo = normalized.resolve("bin/plugin-info.cmd");
			sessionStart = normalized.resolve("bin/session-start.cmd");
		}
		else
		{
			pluginInfo = normalized.resolve("bin/plugin-info");
			sessionStart = normalized.resolve("bin/session-start");
		}
		if (!Files.isRegularFile(version, NOFOLLOW_LINKS) ||
			!Files.isRegularFile(manifest, NOFOLLOW_LINKS) ||
			!Files.isRegularFile(pluginInfo, NOFOLLOW_LINKS) ||
			!Files.isRegularFile(sessionStart, NOFOLLOW_LINKS) || !Files.isExecutable(pluginInfo) ||
			!Files.isExecutable(sessionStart))
		{
			throw new IOException("Artifact is missing required CAT runtime files");
		}
		return normalized;
	}

	/**
	 * Validates the plugin that holds the running code, built for a platform, and returns its runner launcher. The
	 * plugin's {@code bin/VERSION} must name the CAT release that the running code was built for; the plugin must
	 * otherwise satisfy {@link #validate(Path, Path, PluginVersion, Platform)} for the version that it states.
	 *
	 * @param root the unpacked plugin directory
	 *
	 * @param manifestPath the plugin manifest path relative to {@code root}
	 *
	 * @param runner the runner launcher's name in the artifact's {@code bin} directory, without the {@code .cmd}
	 *   extension that it has on Windows
	 *
	 * @param platform the platform that the artifact was built for and runs on
	 *
	 * @return the runner launcher's path in the normalized plugin directory
	 *
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code runner} is blank
	 * @throws IOException if the artifact is of another CAT release, does not satisfy
	 *   {@link #validate(Path, Path, PluginVersion, Platform)} or lacks the executable runner launcher. The message
	 *   names the artifact directory when the launcher is not what is missing.
	 */
	public static Path runnerLauncher(Path root, Path manifestPath, String runner, Platform platform)
		throws IOException
	{
		requireThat(root, "root").isNotNull();
		requireThat(runner, "runner").isNotBlank();
		PluginVersion expected;
		try
		{
			validateLayout(root, manifestPath, platform);
			expected = version(root);
			if (!expected.release().equals(PluginInfo.CAT_VERSION))
			{
				throw new IOException("Artifact version " + expected + " is not of CAT release " +
					PluginInfo.CAT_VERSION);
			}
		}
		catch (IOException e)
		{
			throw new IOException(e.getMessage() + ": " + root.toAbsolutePath().normalize(), e);
		}
		return runnerLauncher(root, manifestPath, runner, expected, platform);
	}

	/**
	 * Validates a complete unpacked plugin directory, built for a platform, that holds a runner launcher, and returns
	 * that launcher.
	 *
	 * @param root the unpacked plugin directory
	 *
	 * @param manifestPath the plugin manifest path relative to {@code root}
	 *
	 * @param runner the runner launcher's name in the artifact's {@code bin} directory, without the {@code .cmd}
	 *   extension that it has on Windows
	 *
	 * @param expected the full plugin version that the plugin must state
	 *
	 * @param platform the platform that the artifact was built for and runs on
	 *
	 * @return the runner launcher's path in the normalized plugin directory
	 *
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code runner} is blank
	 * @throws IOException if the artifact does not satisfy {@link #validate(Path, Path, PluginVersion, Platform)} or
	 *   lacks the executable runner launcher. The message names the artifact directory when the launcher is not what is
	 *   missing.
	 */
	public static Path runnerLauncher(Path root, Path manifestPath, String runner, PluginVersion expected,
		Platform platform) throws IOException
	{
		requireThat(root, "root").isNotNull();
		requireThat(runner, "runner").isNotBlank();
		Path normalized;
		try
		{
			normalized = validate(root, manifestPath, expected, platform);
		}
		catch (IOException e)
		{
			throw new IOException(e.getMessage() + ": " + root.toAbsolutePath().normalize(), e);
		}
		String launcher = runner;
		if (platform.isWindows())
			launcher += ".cmd";
		Path runnerPath = normalized.resolve("bin").resolve(launcher);
		if (!Files.isRegularFile(runnerPath, NOFOLLOW_LINKS) || !Files.isExecutable(runnerPath))
			throw new IOException("The artifact lacks the executable runner launcher " + runnerPath);
		return runnerPath;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessArtifact()
	{
	}
}
