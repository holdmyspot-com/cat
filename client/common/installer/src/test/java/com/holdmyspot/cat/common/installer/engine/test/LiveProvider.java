package com.holdmyspot.cat.common.installer.engine.test;

import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Describes a generic harness {@code harness-a} whose plugin is the directory {@code live} in the home, with its
 * version in {@code live/version}, and whose data is the directory {@code plugin-data}.
 */
public class LiveProvider implements PluginActivation
{
	private final List<Path> companions = new ArrayList<>();

	/**
	 * Creates the provider.
	 */
	public LiveProvider()
	{
	}

	/**
	 * Returns the version folders whose companion files the installer asked this provider to install, in order.
	 *
	 * @return the version folders
	 */
	public List<Path> companions()
	{
		return List.copyOf(companions);
	}

	/**
	 * Records the version folder, which must exist because the harness installed it.
	 *
	 * @throws IOException if the version folder does not exist
	 */
	@Override
	public void installCompanions(Path home, Path versionFolder) throws IOException
	{
		if (!Files.isDirectory(versionFolder))
			throw new IOException("The version folder does not exist: " + versionFolder);
		companions.add(versionFolder);
	}

	@Override
	public String harness()
	{
		return "harness-a";
	}

	@Override
	public String pluginManifest()
	{
		return ".harness-a/plugin.json";
	}

	@Override
	public InstalledState installed(Path home) throws IOException
	{
		if (!Files.exists(home.resolve("live/version")))
			return new InstalledState(Optional.empty(), true);
		return new InstalledState(Optional.of(Files.readString(home.resolve("live/version"))), true);
	}

	@Override
	public Path migrationVersionFile(Path home)
	{
		return home.resolve("migration-version");
	}

	@Override
	public List<Path> removalPaths(Path home)
	{
		return List.of(home.resolve("live"));
	}

	@Override
	public Path dataDirectory(Path home)
	{
		return home.resolve("plugin-data");
	}
}
