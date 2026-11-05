package com.holdmyspot.cat.common.installer.activation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Describes where one harness loads CAT from, and installs the files that CAT places beside the plugin.
 * <p>
 * Each harness module provides one implementation as a {@link java.util.ServiceLoader} service. The installer selects
 * the provider whose {@link #harness()} equals the {@code --harness} option, and passes it the harness's home
 * directory from the user's trust policy, never from the environment. The harness installs the plugin itself; the
 * provider reads which version it loads, installs CAT's companion files once the harness has installed a version, and
 * lists what uninstall deletes. It never runs code from the release.
 * <p>
 * A provider changes only files that CAT owns, never the harness's configuration files.
 */
public interface PluginActivation
{
	/**
	 * Returns the harness that this provider activates releases in.
	 *
	 * @return the harness's identifier: a lowercase letter followed by at most 31 lowercase letters, digits or hyphens,
	 * 	as in release asset names and the trust policy
	 */
	String harness();

	/**
	 * Returns the path, inside a release's plugin directory, of the harness's plugin manifest, whose {@code version}
	 * the installer checks before activation.
	 *
	 * @return a relative path written with {@code /} between names, such as {@code .harness-a/plugin.json}; it names a
	 * 	path in the release archive, not on the local file system
	 */
	String pluginManifest();

	/**
	 * Reads which version of CAT the harness loads.
	 *
	 * @param home the harness's absolute home directory, from the trust policy
	 * @return the installed version, and whether the provider's copies of CAT agree on it
	 * @throws NullPointerException if {@code home} is null
	 * @throws IOException          if the harness's files cannot be read
	 */
	InstalledState installed(Path home) throws IOException;

	/**
	 * Installs the files that CAT places beside a version that the harness installed, such as custom agents that the
	 * harness reads from its own directory. Repeated installs replace the files that CAT placed earlier and remove those
	 * that the version no longer ships; a file that CAT does not own is never replaced.
	 *
	 * @param home          the harness's absolute home directory, from the trust policy
	 * @param versionFolder the version folder into which the harness installed the plugin
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a file cannot be read or written, or a file that CAT would write is not CAT's
	 */
	void installCompanions(Path home, Path versionFolder) throws IOException;

	/**
	 * Returns the file that records the release whose data structures CAT's data in this harness has. Its directory is
	 * the data directory that data migrations change. Install records each data migration that it runs there, and then
	 * the installed release.
	 *
	 * @param home the harness's absolute home directory, from the trust policy
	 * @return the file's absolute path
	 * @throws NullPointerException if {@code home} is null
	 */
	Path migrationVersionFile(Path home);

	/**
	 * Returns what uninstall deletes for this harness: every CAT plugin location and the CAT-owned files that the
	 * provider installed alongside them, in deletion order, excluding the {@link #dataDirectory(Path) data directory}
	 * and every file that CAT does not own. The provider includes an owned file only after checking that CAT still owns
	 * it. Paths that do not exist may be omitted. The installer core deletes each path without following links.
	 *
	 * @param home the harness's absolute home directory, from the trust policy
	 * @return absolute, normalized paths under {@code home}
	 * @throws NullPointerException if {@code home} is null
	 * @throws IOException          if the harness's files cannot be read
	 */
	List<Path> removalPaths(Path home) throws IOException;

	/**
	 * Returns the directory that holds CAT's data in this harness, which uninstall keeps unless asked to delete it.
	 *
	 * @param home the harness's absolute home directory, from the trust policy
	 * @return the directory's absolute path
	 * @throws NullPointerException if {@code home} is null
	 */
	Path dataDirectory(Path home);
}
