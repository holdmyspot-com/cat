package com.holdmyspot.cat.common.updater.activation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Integrates the updater with one harness's command-line program and agent permissions.
 */
public interface HarnessIntegration
{
	/**
	 * Returns the identifier of the harness that this provider integrates.
	 *
	 * @return the harness identifier, equal to the matching {@link PluginActivation#harness()}
	 */
	String harness();

	/**
	 * Finds the harness's command-line program and the environment it needs for one home. Only setup calls this.
	 *
	 * @param home        the absolute, normalized harness home that setup records
	 * @param environment the setup process's environment variables
	 * @return the command, whose first element is an absolute executable path, and its complete environment
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the program cannot be found or run; the message names what the user must install
	 */
	HarnessCommand discover(Path home, Map<String, String> environment) throws IOException;

	/**
	 * Fails, changing nothing, when a configuration file that {@link #installPermissions} would change is malformed,
	 * is a symbolic link or cannot be changed safely.
	 *
	 * @param home the absolute, normalized harness home from the trust policy
	 * @throws NullPointerException if {@code home} is null
	 * @throws IOException          if the configuration cannot be read or changed safely
	 */
	void validateConfiguration(Path home) throws IOException;

	/**
	 * Registers the plugin files already activated under {@code home} with the harness.
	 *
	 * @param home the absolute, normalized harness home from the trust policy
	 * @param cli  runs the recorded harness program
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if registration fails
	 */
	void register(Path home, HarnessCli cli) throws IOException;

	/**
	 * Unregisters CAT from the harness. An answer that CAT is not registered is success.
	 *
	 * @param home the absolute, normalized harness home from the trust policy
	 * @param cli  runs the recorded harness program
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if unregistration fails
	 */
	void unregister(Path home, HarnessCli cli) throws IOException;

	/**
	 * Adds the entries that let an agent run the stable launcher's install, update, rollback, status and uninstall
	 * operations without a permission prompt or sandbox. Never covers setup. Idempotent.
	 *
	 * @param home           the absolute, normalized harness home from the trust policy
	 * @param stableLauncher the absolute path of the installed stable updater launcher
	 * @return the entries that this call added, keyed by {@code <path relative to home>#<setting>}, with an empty
	 * 	setting for a whole CAT-owned file; entries that already existed are not returned
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the configuration cannot be changed
	 */
	Map<String, List<String>> installPermissions(Path home, Path stableLauncher) throws IOException;

	/**
	 * Removes previously added entries that are still present, leaving everything else.
	 *
	 * @param home  the absolute, normalized harness home from the trust policy
	 * @param added the entries that {@link #installPermissions} returned
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the configuration cannot be changed
	 */
	void removePermissions(Path home, Map<String, List<String>> added) throws IOException;
}
