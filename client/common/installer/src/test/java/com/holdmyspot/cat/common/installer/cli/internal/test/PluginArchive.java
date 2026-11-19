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
 * Creates the extracted plugin archive of a harness's plugin, from which install runs: {@code harness-a}'s unless a
 * test names another harness.
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
	 * migration registry, Java runtime and default policy. On Windows the archive also holds the Windows launchers.
	 *
	 * @param parent the directory that receives the archive
	 * @param keys   the release keys of the archive's default policy
	 * @return the plugin folder, the archive's top directory
	 * @throws IOException if the archive cannot be written
	 */
	static Path create(Path parent, List<TrustedKey> keys) throws IOException
	{
		return create(parent, keys, "harness-a");
	}

	/**
	 * Creates the extracted archive of a harness's plugin, as {@link #create(Path, List)} describes.
	 *
	 * @param parent  the directory that receives the archive
	 * @param keys    the release keys of the archive's default policy
	 * @param harness the harness whose plugin the archive holds
	 * @return the plugin folder, the archive's top directory, {@link #topDirectory(String)}
	 * @throws IOException if the archive cannot be written
	 */
	static Path create(Path parent, List<TrustedKey> keys, String harness) throws IOException
	{
		Path plugin = Files.createDirectories(parent.resolve(topDirectory(harness)));
		Files.createDirectories(plugin.resolve("." + harness));
		Files.writeString(plugin.resolve("." + harness + "/plugin.json"), "{\"version\":\"" + VERSION + "\"}");
		Files.createDirectories(plugin.resolve("bin/runtime/bin"));
		Files.writeString(plugin.resolve("bin/runtime/bin/java"), "java");
		Files.writeString(plugin.resolve("bin/installer"), "#!/bin/sh\n");
		Path pluginInfo = Files.writeString(plugin.resolve("bin/plugin-info"), "#!/bin/sh\n");
		if (!pluginInfo.toFile().setExecutable(true))
			throw new IOException("Cannot make " + pluginInfo + " executable");
		if (Platform.current().isWindows())
		{
			// A Windows archive's launchers are a PowerShell installer launcher and batch launchers
			Files.writeString(plugin.resolve("bin/runtime/bin/java.exe"), "java");
			Files.writeString(plugin.resolve("bin/installer.ps1"), "exit 0\r\n");
			Files.writeString(plugin.resolve("bin/plugin-info.cmd"), "@exit /b 0\r\n");
		}
		Files.createDirectories(plugin.resolve("install/migrations"));
		Files.writeString(plugin.resolve("install/migrations/registry.tsv"), "# No migration\n");
		Files.writeString(plugin.resolve("bin/VERSION"), VERSION + "\n");
		writeDefaultPolicy(plugin, keys);
		return plugin;
	}

	/**
	 * Returns the top directory of the archive of a harness's plugin for the platform that runs the test, which the
	 * release's signed manifest names.
	 *
	 * @param harness the harness
	 * @return the directory's name
	 */
	static String topDirectory(String harness)
	{
		return "cat-" + harness + "-" + Platform.current().id() + "-v1.2";
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
