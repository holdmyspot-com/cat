package com.holdmyspot.cat.common.updater.activation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
	 * Reconstructs the original activation plan to authorize recovery and rollback journal paths.
	 * <p>
	 * The returned steps must equal those planned before activating {@code newVersion} over {@code previousVersion},
	 * even when a stopped operation left a live directory absent or when the new release is already active. This
	 * method must retain the provider's canonical destination and symbolic-link checks without requiring a complete
	 * live installation. It must not mutate any path. Providers whose plan depends on the active version must override
	 * this method and use the recorded versions instead of observing the current active version.
	 * <p>
	 * The default delegates to {@link #plan(Path, String)} for providers whose plans depend only on the protected home
	 * and requested version and remain available while live paths are absent.
	 *
	 * @param home the absolute, normalized harness home from protected policy
	 * @param previousVersion the canonical version active before the recorded activation
	 * @param newVersion the canonical version that the recorded activation installed
	 * @return the original ordered steps with absolute, normalized paths
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if any version is blank or a path redirects the provider's authority
	 * @throws IOException if canonical destinations cannot be safely established
	 */
	default List<ActivationStep> recoveryPlan(Path home, String previousVersion, String newVersion) throws IOException
	{
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		requireThat(previousVersion, "previousVersion").isNotBlank();
		requireThat(newVersion, "newVersion").isNotBlank();
		return plan(home, newVersion);
	}

	/**
	 * Returns the file that records the version of the last data migration applied to CAT's data in this harness. After
	 * a successful activation, the updater replaces its content with the activated version.
	 *
	 * @param home the harness's absolute home directory, from the trust policy
	 * @return the file's absolute path
	 * @throws NullPointerException if {@code home} is null
	 */
	Path migrationVersionFile(Path home);

	/**
	 * Plans adding a validated release to a harness that has no CAT plugin. Each step's undo deletes only what the step
	 * created. The plan covers every location that {@link #plan(Path, String)} replaces, so a later update finds a
	 * consistent installation.
	 *
	 * @param home       the harness's absolute home directory, from the trust policy
	 * @param newVersion the version of the release to install
	 * @return the steps to run in order, each with absolute, normalized paths
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code newVersion} is blank
	 * @throws IOException              if the harness's files cannot be read
	 */
	List<ActivationStep> installPlan(Path home, String newVersion) throws IOException;

	/**
	 * Returns what uninstall deletes for this harness: every CAT plugin location and the CAT-owned files that the
	 * provider installed alongside them, in deletion order, excluding the {@link #dataDirectory(Path) data directory}
	 * and every file that CAT does not own. The provider includes an owned file only after checking that CAT still owns
	 * it. Paths that do not exist may be omitted. The updater core deletes each path without following links.
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
