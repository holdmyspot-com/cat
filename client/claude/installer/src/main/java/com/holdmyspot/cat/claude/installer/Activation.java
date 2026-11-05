package com.holdmyspot.cat.claude.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.runner.PluginManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Describes CAT's version folders in the directory marketplace that Claude Code loads in place. Each version lives in
 * its own folder, named after the version, inside the marketplace plugin.
 * <p>
 * Existing symbolic links from the policy's home through a version folder are refused. This guards against
 * redirection by a project that can write those directories. The provider never changes Claude Code's
 * configuration.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class Activation implements PluginActivation
{
	/**
	 * Creates a provider for service loading.
	 */
	public Activation()
	{
	}

	@Override
	public String harness()
	{
		return "claude";
	}

	@Override
	public String pluginManifest()
	{
		return ".claude-plugin/plugin.json";
	}

	/**
	 * Reads the version that Claude Code records for CAT, and checks that its version folder states it.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return empty and consistent when Claude Code records no CAT installation and CAT's marketplace is absent; empty
	 * 	and inconsistent when the marketplace exists without a record, which a stopped installation or removal leaves;
	 * 	otherwise the recorded version, consistent only if its version folder's plugin manifest states that version
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the record or the manifest cannot be read or parsed, or a path component is a
	 *                                  symlink
	 */
	@Override
	public InstalledState installed(Path home) throws IOException
	{
		ClaudeInstallation installation = new ClaudeInstallation(home);
		Optional<String> recorded = Integration.installedVersion(home);
		if (recorded.isEmpty())
		{
			Locations.checkLinks(home, installation.marketplaceRoot());
			boolean absent = Files.notExists(installation.marketplaceRoot(), LinkOption.NOFOLLOW_LINKS);
			return new InstalledState(Optional.empty(), absent);
		}
		String version = recorded.get();
		if (!isVersion(version))
			return new InstalledState(recorded, false);
		Path folder = installation.versionFolders().resolve(version);
		Path manifestPath = Path.of(pluginManifest());
		Locations.checkLinks(home, folder.resolve(manifestPath));
		if (Files.notExists(folder.resolve(manifestPath), LinkOption.NOFOLLOW_LINKS))
			return new InstalledState(recorded, false);
		return new InstalledState(recorded, PluginManifest.version(folder, manifestPath).equals(recorded));
	}

	/**
	 * Installs nothing, because Claude Code loads everything of CAT's from the version folder.
	 *
	 * @param home          the absolute Claude Code configuration directory recorded in policy
	 * @param versionFolder the version folder into which Claude Code installed the plugin
	 * @throws NullPointerException if any of the arguments are null
	 */
	@Override
	public void installCompanions(Path home, Path versionFolder)
	{
		requireThat(home, "home").isNotNull();
		requireThat(versionFolder, "versionFolder").isNotNull();
	}

	/**
	 * Returns the migration marker in CAT's existing data directory.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return the absolute migration marker path
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	@Override
	public Path migrationVersionFile(Path home)
	{
		return dataDirectory(home).resolve("migration-version");
	}

	/**
	 * Lists CAT's marketplace and Claude Code's cached copies of the plugin that it installed from that marketplace.
	 * Claude Code's uninstall only marks the cached copies as orphaned, so uninstall deletes them to remove CAT
	 * completely.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return the locations that exist, in deletion order
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if a path component is a symlink
	 */
	@Override
	public List<Path> removalPaths(Path home) throws IOException
	{
		ClaudeInstallation installation = new ClaudeInstallation(home);
		List<Path> paths = new ArrayList<>();
		for (Path path : List.of(installation.marketplaceRoot(), installation.marketplaceCache()))
		{
			Locations.checkLinks(home, path);
			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
				paths.add(path);
		}
		return paths;
	}

	/**
	 * Returns the data directory that Claude Code assigns to the {@code cat} plugin of the {@code cat} marketplace.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return the absolute data directory
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	@Override
	public Path dataDirectory(Path home)
	{
		return new ClaudeInstallation(home).dataDirectory();
	}

	/**
	 * Returns whether a text names a version folder: a plugin version, or a release version that an installation
	 * from before plugin versions recorded.
	 *
	 * @param text the text
	 * @return true if {@code text} is a plugin version or a release version
	 */
	private static boolean isVersion(String text)
	{
		try
		{
			PluginVersion.parse(text);
			return true;
		}
		catch (IllegalArgumentException _)
		{
			try
			{
				ReleaseVersion.parse(text);
				return true;
			}
			catch (TrustException _)
			{
				return false;
			}
		}
	}
}
