package com.holdmyspot.cat.common.installation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.Map;

/**
 * The folders of CAT's installation in one harness: the marketplace folder that the installer writes, the version
 * folders that the harness runs the plugin from, and the plugin data directory that keeps CAT's configuration and
 * caches across versions. Each harness's layout module declares where its harness keeps them.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
public interface HarnessInstallation
{
	/**
	 * Resolves a harness's configuration directory from the environment: the directory that the home variable names,
	 * or the default directory in the user's home directory when the variable is unset or empty.
	 *
	 * @param environment  the environment variables
	 * @param homeVariable the environment variable that names the harness's configuration directory
	 * @param userHome     the user's absolute home directory
	 * @param defaultHome  the configuration directory relative to {@code userHome} when the variable is unset
	 * @return the absolute, normalized configuration directory
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code homeVariable} is blank, {@code userHome} is relative,
	 *                                  {@code defaultHome} is absolute, or the variable names a relative directory
	 */
	static Path resolveHome(Map<String, String> environment, String homeVariable, Path userHome, Path defaultHome)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(homeVariable, "homeVariable").isNotBlank();
		requireThat(userHome, "userHome").isAbsolute();
		requireThat(defaultHome, "defaultHome").isRelative();
		String value = environment.get(homeVariable);
		if (value == null || value.isEmpty())
			return userHome.resolve(defaultHome).normalize();
		Path home = Path.of(value);
		if (!home.isAbsolute())
			throw new IllegalArgumentException(homeVariable + " must name an absolute directory: \"" + value + "\"");
		return home.normalize();
	}

	/**
	 * Returns the harness's absolute configuration directory.
	 *
	 * @return the configuration directory
	 */
	Path home();

	/**
	 * Returns the marketplace folder that the installer writes and the harness reads.
	 *
	 * @return the marketplace folder
	 */
	Path marketplaceRoot();

	/**
	 * Returns the plugin's folder inside the marketplace.
	 *
	 * @return the marketplace plugin folder
	 */
	Path marketplacePlugin();

	/**
	 * Returns the harness's plugin cache.
	 *
	 * @return the plugin cache
	 */
	Path cacheRoot();

	/**
	 * Returns the folder that holds every version folder of CAT.
	 *
	 * @return the folder whose children are named after plugin versions
	 */
	Path versionFolders();

	/**
	 * Returns the folder that the harness runs one plugin version from.
	 *
	 * @param version the plugin version
	 * @return the child of {@link #versionFolders()} named after {@code version}
	 * @throws NullPointerException if {@code version} is null
	 */
	default Path versionFolder(PluginVersion version)
	{
		requireThat(version, "version").isNotNull();
		return versionFolders().resolve(version.toString());
	}

	/**
	 * Returns the plugin data directory, which the harness keeps across plugin versions.
	 *
	 * @return the data directory
	 */
	Path dataDirectory();

	/**
	 * Returns the installer's policy.
	 *
	 * @return {@code policy.json} in the data directory
	 */
	default Path policy()
	{
		return dataDirectory().resolve("policy.json");
	}

	/**
	 * Returns the installer's state directory, which holds its recorded entries, logs and lock.
	 *
	 * @return {@code state} in the data directory
	 */
	default Path stateDirectory()
	{
		return dataDirectory().resolve("state");
	}

	/**
	 * Returns the release cache, which holds one folder per release version.
	 *
	 * @return {@code releases} in the data directory
	 */
	default Path releases()
	{
		return dataDirectory().resolve("releases");
	}
}
