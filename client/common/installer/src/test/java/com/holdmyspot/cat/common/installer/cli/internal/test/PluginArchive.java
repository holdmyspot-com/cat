package com.holdmyspot.cat.common.installer.cli.internal.test;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Creates the extracted plugin archive of {@code harness-a}'s plugin, from which install runs.
 */
final class PluginArchive
{
	/**
	 * The plugin version of the archive.
	 */
	static final String VERSION = "1.2+b0123456789ab";

	/**
	 * Prevents construction.
	 */
	private PluginArchive()
	{
	}

	/**
	 * Creates the extracted archive: the plugin's manifest, version, installer launcher, plugin information launcher,
	 * migration registry, Java runtime and default policy.
	 *
	 * @param parent the directory that receives the archive
	 * @param keys   the release keys of the archive's default policy
	 * @return the plugin folder, the archive's top directory
	 * @throws IOException if the archive cannot be written
	 */
	static Path create(Path parent, List<TrustedKey> keys) throws IOException
	{
		Path plugin = Files.createDirectories(parent.resolve("cat-harness-a-linux-x64-v1.2"));
		Files.createDirectories(plugin.resolve(".harness-a"));
		Files.writeString(plugin.resolve(".harness-a/plugin.json"), "{\"version\":\"" + VERSION + "\"}");
		Files.createDirectories(plugin.resolve("bin/runtime/bin"));
		Files.writeString(plugin.resolve("bin/runtime/bin/java"), "java");
		Files.writeString(plugin.resolve("bin/installer"), "#!/bin/sh\n");
		Path pluginInfo = Files.writeString(plugin.resolve("bin/plugin-info"), "#!/bin/sh\n");
		if (!pluginInfo.toFile().setExecutable(true))
			throw new IOException("Cannot make " + pluginInfo + " executable");
		Files.createDirectories(plugin.resolve("install/migrations"));
		Files.writeString(plugin.resolve("install/migrations/registry.tsv"), "# No migration\n");
		Files.writeString(plugin.resolve("bin/VERSION"), VERSION + "\n");
		writeDefaultPolicy(plugin, keys);
		return plugin;
	}

	/**
	 * Replaces the archive's default policy.
	 *
	 * @param plugin the plugin folder
	 * @param keys   the release keys
	 * @throws IOException if the policy cannot be written
	 */
	static void writeDefaultPolicy(Path plugin, List<TrustedKey> keys) throws IOException
	{
		try
		{
			TrustPolicy defaults = TrustPolicy.of(keys, URI.create("https://releases.example/cat/releases/download"),
				ReleaseVersion.parse("1.0"), 1, Map.of());
			Path file = plugin.resolve("bin/conf/installer/default-policy.json");
			Files.createDirectories(file.getParent());
			defaults.write(file, new FileReplacer(Platform.current(), () ->
			{
			}, Clock.systemUTC()));
		}
		catch (TrustException e)
		{
			throw new AssertionError(e);
		}
	}
}
