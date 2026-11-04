package com.holdmyspot.cat.common.updater.activation;

import com.holdmyspot.cat.common.FileReplacer;
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
	 * Returns the name of the harness's command-line program, which setup looks up on the user's search path, adding
	 * a Windows program extension.
	 *
	 * @return the program's name, such as {@code harness-a}
	 */
	String programName();

	/**
	 * Returns the harness's own variables that the recorded program environment holds for one home, such as the
	 * variable that moves the harness's configuration directory. Setup adds the search path and the variables that
	 * the platform needs. Only setup calls this.
	 *
	 * @param home        the absolute, normalized harness home that setup records
	 * @param environment the setup process's environment variables, looked up case-insensitively on Windows
	 * @return the variables
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the environment lacks a variable that the harness needs
	 */
	Map<String, String> programEnvironment(Path home, Map<String, String> environment) throws IOException;

	/**
	 * Fails unless the program that setup found answered {@code --version} as this harness's program does. Only
	 * setup calls this.
	 *
	 * @param version the exit status and output of {@code <program> --version}
	 * @throws NullPointerException if {@code version} is null
	 * @throws IOException          if the answer does not identify the harness's program; the message names what the
	 *                              user must install
	 */
	void requireIdentity(CommandResult version) throws IOException;

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
	 * Registers the plugin files already activated under {@code home} with the harness. A failure does not undo what
	 * this call registered before it failed: the updater calls {@link #unregister} after a failed registration only
	 * when that registration was part of an install where the harness had no plugin, and never after a failed update,
	 * repair or registration of a plugin that is already up to date, whose plugin files stay registered where they
	 * are.
	 *
	 * @param home     the absolute, normalized harness home from the trust policy
	 * @param cli      runs the recorded harness program
	 * @param replacer replaces each file that registration writes, retrying while Windows refuses the replacement
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if registration fails
	 */
	void register(Path home, HarnessCli cli, FileReplacer replacer) throws IOException;

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
	 * Adds the entries that let an agent run the stable launcher's install, update, status and uninstall operations
	 * without a permission prompt or sandbox. Never covers setup. Idempotent.
	 *
	 * @param home           the absolute, normalized harness home from the trust policy
	 * @param stableLauncher the absolute path of the installed stable updater launcher
	 * @param replacer       replaces each configuration file that this call writes, retrying while Windows refuses
	 *                       the replacement
	 * @return the entries that this call added, keyed by {@code <path relative to home>#<setting>}, with an empty
	 * 	setting for a whole CAT-owned file; entries that already existed are not returned
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the configuration cannot be changed
	 */
	Map<String, List<String>> installPermissions(Path home, Path stableLauncher, FileReplacer replacer)
		throws IOException;

	/**
	 * Returns every entry that {@link #installPermissions} maintains for a stable launcher, whether or not the
	 * configuration already holds it. An entry that the updater recorded and this set lacks is obsolete, and setup
	 * removes it.
	 *
	 * @param stableLauncher the absolute path of the installed stable updater launcher
	 * @return the entries keyed as {@link #installPermissions} returns them
	 * @throws NullPointerException     if {@code stableLauncher} is null
	 * @throws IllegalArgumentException if {@code stableLauncher} is relative
	 * @throws IOException              if the launcher path cannot be named in an entry
	 */
	Map<String, List<String>> permissions(Path stableLauncher) throws IOException;

	/**
	 * Returns the entries that the updater which earlier builds shared between harnesses asked the user to add for its
	 * stable launcher, in the form that {@link #removePermissions} accepts, so that they are removed once that launcher
	 * is retired.
	 *
	 * @param legacyLauncher the absolute path of the shared installation's stable launcher
	 * @return the entries keyed as {@link #installPermissions} returns them; empty when this harness's current
	 * 	permissions replace them
	 * @throws NullPointerException if {@code legacyLauncher} is null
	 */
	Map<String, List<String>> legacyPermissions(Path legacyLauncher);

	/**
	 * Removes previously added entries that are still present, leaving everything else.
	 *
	 * @param home     the absolute, normalized harness home from the trust policy
	 * @param added    the entries that {@link #installPermissions} returned
	 * @param replacer replaces each configuration file that this call writes, retrying while Windows refuses the
	 *                 replacement
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the configuration cannot be changed
	 */
	void removePermissions(Path home, Map<String, List<String>> added, FileReplacer replacer) throws IOException;
}
