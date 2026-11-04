package com.holdmyspot.cat.common.installer.engine.test;

import com.holdmyspot.cat.common.installer.activation.ActivationStep;
import com.holdmyspot.cat.common.installer.activation.AddDirectory;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.activation.ReplaceDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Describes a generic harness {@code harness-a} that, like a harness with a versioned plugin cache, copies each plugin
 * version into a folder named by that version. The plugin is the directory {@code live} in the home, with its version
 * in {@code live/version}, and its copy is the directory {@code versions/<version>}. The installation is consistent
 * only when the folder that the live plugin's version names holds that same version.
 */
public final class VersionFolderProvider implements PluginActivation
{
	/**
	 * Creates the provider.
	 */
	public VersionFolderProvider()
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
		Path live = home.resolve("live/version");
		if (!Files.exists(live, LinkOption.NOFOLLOW_LINKS))
			return new InstalledState(Optional.empty(), true);
		String version = Files.readString(live);
		Path copy = home.resolve("versions").resolve(version).resolve("version");
		boolean consistent = Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS) &&
			Files.readString(copy).equals(version);
		return new InstalledState(Optional.of(version), consistent);
	}

	@Override
	public List<ActivationStep> plan(Path home, String newVersion) throws IOException
	{
		String active = installed(home).version().orElseThrow();
		return steps(home, active, newVersion);
	}

	@Override
	public List<ActivationStep> recoveryPlan(Path home, String previousVersion, String newVersion)
	{
		return steps(home, previousVersion, newVersion);
	}

	/**
	 * Returns the steps that activate a version over another.
	 *
	 * @param home            the harness home
	 * @param previousVersion the active version
	 * @param newVersion      the version to activate
	 * @return the steps: replace the live plugin, then replace the version's folder when it is the active version and
	 *         add it otherwise
	 */
	private static List<ActivationStep> steps(Path home, String previousVersion, String newVersion)
	{
		Path copy = home.resolve("versions").resolve(newVersion);
		ActivationStep copyStep;
		if (previousVersion.equals(newVersion))
			copyStep = new ReplaceDirectory(copy, home);
		else
			copyStep = new AddDirectory(copy, home);
		return List.of(new ReplaceDirectory(home.resolve("live"), home), copyStep);
	}

	@Override
	public Path migrationVersionFile(Path home)
	{
		return home.resolve("migration-version");
	}

	@Override
	public List<ActivationStep> installPlan(Path home, String newVersion)
	{
		return List.of(new AddDirectory(home.resolve("live"), home),
			new AddDirectory(home.resolve("versions").resolve(newVersion), home));
	}

	@Override
	public List<Path> removalPaths(Path home)
	{
		return List.of(home.resolve("live"), home.resolve("versions"));
	}

	@Override
	public Path dataDirectory(Path home)
	{
		return home.resolve("plugin-data");
	}
}
