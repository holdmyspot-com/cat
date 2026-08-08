package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.PluginInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Validates that an unpacked CAT plugin directory contains the required version file, manifest, and executable
 * launchers.
 */
public final class HarnessArtifact
{
	private static final int MAXIMUM_VERSION_BYTES = 1_048_576;

	/**
	 * Validates a complete unpacked plugin directory for a caller-supplied plugin manifest.
	 *
	 * @param root the unpacked plugin directory
	 * @param manifestPath the plugin manifest path relative to {@code root}
	 * @return the normalized unpacked plugin directory
	 * @throws IOException if the artifact is incomplete or does not satisfy the required filesystem layout
	 */
	public static Path validate(Path root, Path manifestPath) throws IOException
	{
		requireThat(root, "root").isNotNull();
		requireThat(manifestPath, "manifestPath").isNotNull();
		if (manifestPath.isAbsolute() || manifestPath.normalize().startsWith(".."))
		{
			throw new IOException(
				"Artifact manifest path must be relative to the artifact directory and must not escape it: " + manifestPath +
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
		Path version = normalized.resolve("client/VERSION");
		Path manifest = normalized.resolve(manifestPath).normalize();
		if (!manifest.startsWith(normalized))
		{
			throw new IOException("Artifact manifest path resolves outside the artifact directory: " + manifestPath +
				". Use a relative path below the artifact directory.");
		}
		Path pluginInfo = normalized.resolve("client/bin/plugin-info");
		Path sessionStart = normalized.resolve("client/bin/session-start");
		if (!Files.isRegularFile(version, NOFOLLOW_LINKS) ||
			!Files.isRegularFile(manifest, NOFOLLOW_LINKS) ||
			!Files.isRegularFile(pluginInfo, NOFOLLOW_LINKS) ||
			!Files.isRegularFile(sessionStart, NOFOLLOW_LINKS) || !Files.isExecutable(pluginInfo) ||
			!Files.isExecutable(sessionStart))
		{
			throw new IOException("Artifact is missing required CAT runtime files");
		}
		if (!BoundedInput.readUtf8(version, MAXIMUM_VERSION_BYTES, "Artifact version").strip().equals(
			PluginInfo.CAT_VERSION))
			throw new IOException("Artifact version does not match CAT " + PluginInfo.CAT_VERSION);
		return normalized;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessArtifact()
	{
	}
}
