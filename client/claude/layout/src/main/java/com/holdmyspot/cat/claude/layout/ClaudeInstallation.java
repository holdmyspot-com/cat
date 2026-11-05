package com.holdmyspot.cat.claude.layout;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where Claude Code keeps CAT's installation. Claude Code runs a directory-source plugin in place, so each plugin
 * version lives in its own folder inside the marketplace plugin. Claude Code also copies each version that it installs
 * into its plugin cache, which it does not run.
 *
 * @param home Claude Code's absolute configuration directory
 */
public record ClaudeInstallation(Path home) implements HarnessInstallation
{
	/**
	 * Creates Claude Code's installation.
	 *
	 * @param home Claude Code's absolute configuration directory
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	public ClaudeInstallation
	{
		requireThat(home, "home").isAbsolute();
		home = home.normalize();
	}

	/**
	 * Locates Claude Code's installation in the directory that {@code CLAUDE_CONFIG_DIR} names, or in {@code .claude}
	 * in the user's home directory when the variable is unset or empty.
	 *
	 * @param environment the environment variables
	 * @param userHome    the user's absolute home directory
	 * @return the installation
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code userHome} is relative or {@code CLAUDE_CONFIG_DIR} names a relative
	 *                                  directory
	 */
	public static ClaudeInstallation fromEnvironment(Map<String, String> environment, Path userHome)
	{
		return new ClaudeInstallation(HarnessInstallation.resolveHome(environment, "CLAUDE_CONFIG_DIR", userHome,
			Path.of(".claude")));
	}

	@Override
	public Path marketplaceRoot()
	{
		return home.resolve("plugins/cat-marketplace");
	}

	@Override
	public Path marketplacePlugin()
	{
		return marketplaceRoot().resolve("plugins/cat");
	}

	@Override
	public Path cacheRoot()
	{
		return home.resolve("plugins/cache");
	}

	@Override
	public Path versionFolders()
	{
		return marketplacePlugin();
	}

	/**
	 * Returns the version folders, then Claude Code's cache copies of them.
	 *
	 * @return the folders
	 */
	@Override
	public List<Path> versionCopyFolders()
	{
		return List.of(versionFolders(), pluginCache());
	}

	/**
	 * Returns the plugin version that a version folder or a cache copy holds. Claude Code names a cache copy after
	 * its version with {@code +} replaced by {@code -}, such as {@code 1.0-b0123456789ab}.
	 *
	 * @param folder one of the folders that {@link #versionCopyFolders()} returns
	 * @param name   the child's name
	 * @return the plugin version, or empty when the name names no plugin version in that folder
	 * @throws NullPointerException if any of the arguments are null
	 */
	@Override
	public Optional<PluginVersion> copiedVersion(Path folder, String name)
	{
		requireThat(folder, "folder").isNotNull();
		requireThat(name, "name").isNotNull();
		if (!folder.equals(pluginCache()))
			return HarnessInstallation.super.copiedVersion(folder, name);
		// A release version holds no "-", so the first one is the build separator's "+"
		int separator = name.indexOf('-');
		if (separator == -1)
			return Optional.empty();
		Optional<PluginVersion> version = HarnessInstallation.super.copiedVersion(folder,
			name.substring(0, separator) + "+" + name.substring(separator + 1));
		if (version.isEmpty() || !cacheName(version.get()).equals(name))
			return Optional.empty();
		return version;
	}

	@Override
	public Path dataDirectory()
	{
		return home.resolve("plugins/data/cat-cat");
	}

	/**
	 * Returns the manifest of CAT's marketplace, which names the version folder that Claude Code loads CAT from.
	 *
	 * @return {@code .claude-plugin/marketplace.json} in the marketplace folder
	 */
	public Path marketplaceManifest()
	{
		return marketplaceRoot().resolve(".claude-plugin/marketplace.json");
	}

	/**
	 * Returns the folder of Claude Code's plugin cache that holds its copies of the plugins of CAT's marketplace.
	 *
	 * @return {@code cat} in the plugin cache
	 */
	public Path marketplaceCache()
	{
		return cacheRoot().resolve("cat");
	}

	/**
	 * Returns Claude Code's record of its installed plugins, which states the version of CAT that it loads.
	 *
	 * @return {@code plugins/installed_plugins.json} in the configuration directory
	 */
	public Path installedPlugins()
	{
		return home.resolve("plugins/installed_plugins.json");
	}

	/**
	 * Returns the folder of Claude Code's plugin cache that holds its copies of CAT's plugin versions.
	 *
	 * @return {@code cat} in the {@link #marketplaceCache() marketplace's cache}
	 */
	private Path pluginCache()
	{
		return marketplaceCache().resolve("cat");
	}

	/**
	 * Returns the name of Claude Code's cache copy of a plugin version.
	 *
	 * @param version the plugin version
	 * @return the version with {@code +} replaced by {@code -}
	 */
	private static String cacheName(PluginVersion version)
	{
		return version.toString().replace('+', '-');
	}
}
