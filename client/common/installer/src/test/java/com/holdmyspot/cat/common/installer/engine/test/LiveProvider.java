package com.holdmyspot.cat.common.installer.engine.test;

import com.holdmyspot.cat.common.installer.activation.ActivationStep;
import com.holdmyspot.cat.common.installer.activation.AddDirectory;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.activation.ReplaceDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Describes a generic harness {@code harness-a} whose plugin is the directory {@code live} in the home, with its
 * version in {@code live/version}, and whose data is the directory {@code plugin-data}.
 */
public class LiveProvider implements PluginActivation
{
	/**
	 * Creates the provider.
	 */
	public LiveProvider()
	{
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
	public List<ActivationStep> plan(Path home, String newVersion)
	{
		return List.of(new ReplaceDirectory(home.resolve("live"), home));
	}

	@Override
	public Path migrationVersionFile(Path home)
	{
		return home.resolve("migration-version");
	}

	@Override
	public List<ActivationStep> installPlan(Path home, String newVersion)
	{
		return List.of(new AddDirectory(home.resolve("live"), home));
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
