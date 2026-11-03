package com.holdmyspot.cat.common.updater.activation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Activates a validated CAT release in one harness, by describing where that harness loads CAT from.
 * <p>
 * Each harness module provides one implementation as a {@link java.util.ServiceLoader} service. The updater selects
 * the provider whose {@link #harness()} equals the {@code --harness} option, and passes it the harness's home
 * directory from the user's trust policy, never from the environment. The provider only reads the harness's files and
 * plans the changes. The updater's core stages, journals, runs and undoes them, and never runs code from the release.
 * <p>
 * A provider's steps change only the files that the harness loads CAT from and files that CAT owns. They never change
 * the harness's configuration files, so a routine update needs no permission to write them.
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
	 * the updater checks before activation.
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
	 * Plans the activation of a validated release.
	 *
	 * @param home       the harness's absolute home directory, from the trust policy
	 * @param newVersion the version of the release to activate
	 * @return the steps to run in order, each with absolute, normalized paths
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code newVersion} is blank
	 * @throws IOException              if the harness's files cannot be read
	 */
	List<ActivationStep> plan(Path home, String newVersion) throws IOException;

	/**
	 * Returns the file that records the version of the last data migration applied to CAT's data in this harness. After
	 * a successful activation, the updater replaces its content with the activated version.
	 *
	 * @param home the harness's absolute home directory, from the trust policy
	 * @return the file's absolute path
	 * @throws NullPointerException if {@code home} is null
	 */
	Path migrationVersionFile(Path home);
}
