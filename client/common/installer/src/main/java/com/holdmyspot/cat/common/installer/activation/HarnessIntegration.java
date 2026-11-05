package com.holdmyspot.cat.common.installer.activation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Integrates the installer with one harness's installation layout, command-line program, agent permissions and
 * sandbox settings.
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
	 * Returns where the harness keeps CAT's installation below a harness home.
	 *
	 * @param home the absolute, normalized harness home
	 * @return the installation
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	HarnessInstallation installation(Path home);

	/**
	 * Returns where the harness keeps CAT's installation for a user, at the harness home that the user's environment
	 * selects.
	 *
	 * @param environment the user's environment variables
	 * @param userHome    the user's absolute home directory
	 * @return the installation
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code userHome} is relative, or the environment names a relative harness
	 *                                  home
	 */
	HarnessInstallation installation(Map<String, String> environment, Path userHome);

	/**
	 * Returns the folder into which the installer writes a plugin version before {@link #register} asks the harness to
	 * install it: a folder of the harness's marketplace, which is the version folder itself when the harness runs the
	 * plugin from its marketplace.
	 *
	 * @param home    the absolute, normalized harness home
	 * @param version the plugin version
	 * @return the folder
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	Path marketplaceFolder(Path home, PluginVersion version);

	/**
	 * Returns whether the harness would run the plugin from the version folder of one version instead of the folder
	 * of the version that it registered, if both folders existed. A harness that runs the version that its
	 * registration names never does; a harness that selects a version folder by its name may.
	 *
	 * @param candidate the version of a folder that could be left beside the registered version's folder
	 * @param active    the version that the harness registered
	 * @return true if the harness would run {@code candidate}
	 * @throws NullPointerException if any of the arguments are null
	 */
	default boolean runsInstead(PluginVersion candidate, PluginVersion active)
	{
		requireThat(candidate, "candidate").isNotNull();
		requireThat(active, "active").isNotNull();
		return false;
	}

	/**
	 * Returns the name of the harness's command-line program, which install looks up on the user's search path, adding
	 * a Windows program extension.
	 *
	 * @return the program's name, such as {@code harness-a}
	 */
	String programName();

	/**
	 * Returns the harness's own variables that the recorded program environment holds for one home, such as the
	 * variable that moves the harness's configuration directory. Install adds the search path and the variables that
	 * the platform needs. Only install calls this.
	 *
	 * @param home        the absolute, normalized harness home that install records
	 * @param environment the install process's environment variables, looked up case-insensitively on Windows
	 * @return the variables
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the environment lacks a variable that the harness needs
	 */
	Map<String, String> programEnvironment(Path home, Map<String, String> environment) throws IOException;

	/**
	 * Fails unless the program that install found answered {@code --version} as this harness's program does. Only
	 * install calls this.
	 *
	 * @param version the exit status and output of {@code <program> --version}
	 * @throws NullPointerException if {@code version} is null
	 * @throws IOException          if the answer does not identify the harness's program; the message names what the
	 *                              user must install
	 */
	void requireIdentity(CommandResult version) throws IOException;

	/**
	 * Fails, changing nothing, when a configuration file that {@link #installPermissions} or
	 * {@link #installSandboxEntries} would change is malformed, is a symbolic link or cannot be changed safely.
	 *
	 * @param home the absolute, normalized harness home from the trust policy
	 * @throws NullPointerException if {@code home} is null
	 * @throws IOException          if the configuration cannot be read or changed safely
	 */
	void validateConfiguration(Path home) throws IOException;

	/**
	 * Asks the harness to install one plugin version that the installer wrote into the
	 * {@link #marketplaceFolder marketplace folder}, which places it in that version's folder, or registers that
	 * version's plugin files already activated under {@code home}. Registration chooses the version folder by
	 * {@code version} alone, whatever other version folders are present. A failure does not undo what this call
	 * registered before it failed, and the installer never calls {@link #unregister} after it: the harness keeps
	 * whichever version it then reports, and running install again repeats the registration.
	 *
	 * @param home     the absolute, normalized harness home from the trust policy
	 * @param version  the plugin version to register
	 * @param cli      runs the recorded harness program
	 * @param replacer replaces each file that registration writes, retrying while Windows refuses the replacement
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if registration fails, or the harness does not run {@code version} afterward
	 */
	void register(Path home, PluginVersion version, HarnessCli cli, FileReplacer replacer) throws IOException;

	/**
	 * Unregisters CAT from the harness. An answer that CAT is not registered is success.
	 *
	 * @param home     the absolute, normalized harness home from the trust policy
	 * @param cli      runs the recorded harness program
	 * @param keepData true to ask the harness to keep CAT's plugin data directory, false to let it delete the directory
	 *                 as it does when it uninstalls a plugin
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if unregistration fails
	 */
	void unregister(Path home, HarnessCli cli, boolean keepData) throws IOException;

	/**
	 * Removes the entries that CAT's installation guide told users to add so that CAT's runners ran outside the
	 * harness's sandbox, which they no longer do. Removes only the exact forms that the guide gave, leaves a user's
	 * configuration that holds other settings in the same place unchanged, and leaves a configuration without such
	 * entries as it is. Install calls this before {@link #installPermissions}; uninstall never does.
	 *
	 * @param home     the absolute, normalized harness home from the trust policy
	 * @param replacer replaces each configuration file that this call writes, retrying while Windows refuses the
	 *                 replacement
	 * @return the sentences that tell the user what this call removed, or which entries the user must remove; empty
	 * 	when there was nothing to remove
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the configuration cannot be changed
	 */
	List<String> removeRunnerExceptions(Path home, FileReplacer replacer) throws IOException;

	/**
	 * Adds the entries that let an agent run the installer's routine operations without a permission prompt or sandbox.
	 * Idempotent.
	 *
	 * @param home      the absolute, normalized harness home from the trust policy
	 * @param installer the absolute path of the installer launcher in the active version folder
	 * @param replacer  replaces each configuration file that this call writes, retrying while Windows refuses the
	 *                  replacement
	 * @return the entries that this call added, keyed by {@code <path relative to home>#<setting>}, with an empty
	 * 	setting for a whole CAT-owned file; entries that already existed are not returned
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the configuration cannot be changed
	 */
	Map<String, List<String>> installPermissions(Path home, Path installer, FileReplacer replacer)
		throws IOException;

	/**
	 * Returns every entry that {@link #installPermissions} maintains for an installer launcher, whether or not the
	 * configuration already holds it. An entry that the installer recorded and that neither this set nor
	 * {@link #sandboxEntries} holds is obsolete, and install removes it.
	 *
	 * @param installer the absolute path of the installer launcher in the active version folder
	 * @return the entries keyed as {@link #installPermissions} returns them
	 * @throws NullPointerException     if {@code installer} is null
	 * @throws IllegalArgumentException if {@code installer} is relative
	 * @throws IOException              if the launcher path cannot be named in an entry
	 */
	Map<String, List<String>> permissions(Path installer) throws IOException;

	/**
	 * Returns the question that install must put to the user before it lets sandboxed commands take the capacity
	 * locks, because the only change that achieves it also changes a setting that is not CAT's alone. Install changes
	 * nothing unless the user agrees, and then calls {@link #installConsentedEntries} before
	 * {@link #installSandboxEntries}.
	 *
	 * @param home     the absolute, normalized harness home from the trust policy
	 * @param catLocks the absolute directory of CAT's capacity locks
	 * @return the question, which explains the problem, what fails without the change, and the change; empty when
	 * 	install needs no permission
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} or {@code catLocks} are relative
	 * @throws IOException              if the configuration cannot be read
	 */
	Optional<String> consentRequest(Path home, Path catLocks) throws IOException;

	/**
	 * Makes the change that {@link #consentRequest} asks the user to allow, once the user has allowed it. Does nothing
	 * when {@link #consentRequest} asks nothing.
	 *
	 * @param home     the absolute, normalized harness home from the trust policy
	 * @param replacer replaces each configuration file that this call writes, retrying while Windows refuses the
	 *                 replacement
	 * @return the entries that this call added, keyed as {@link #installPermissions} keys them, which
	 * 	{@link #sandboxEntries} maintains afterwards and {@link #removePermissions} undoes
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the configuration cannot be changed
	 */
	Map<String, List<String>> installConsentedEntries(Path home, FileReplacer replacer) throws IOException;

	/**
	 * Adds the sandbox settings that keep sandboxed commands from writing the other harness's CAT installation and
	 * that let them take the capacity locks. Idempotent.
	 *
	 * @param home     the absolute, normalized harness home from the trust policy
	 * @param other    where the other harness keeps CAT's installation
	 * @param catLocks the absolute directory of CAT's capacity locks
	 * @param replacer replaces each configuration file that this call writes, retrying while Windows refuses the
	 *                 replacement
	 * @return the entries that this call added, keyed as {@link #installPermissions} keys them; entries that already
	 * 	existed are not returned
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} or {@code catLocks} are relative
	 * @throws IOException              if the configuration cannot be changed
	 */
	Map<String, List<String>> installSandboxEntries(Path home, HarnessInstallation other, Path catLocks,
		FileReplacer replacer) throws IOException;

	/**
	 * Returns every entry that {@link #installSandboxEntries} maintains, whether or not the configuration already holds
	 * it.
	 *
	 * @param home     the absolute, normalized harness home from the trust policy
	 * @param other    where the other harness keeps CAT's installation
	 * @param catLocks the absolute directory of CAT's capacity locks
	 * @return the entries keyed as {@link #installPermissions} keys them
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} or {@code catLocks} are relative
	 */
	Map<String, List<String>> sandboxEntries(Path home, HarnessInstallation other, Path catLocks);

	/**
	 * Returns the sentences that tell the user what entries that install added change beyond CAT, such as a setting
	 * that other projects share.
	 *
	 * @param home  the absolute, normalized harness home from the trust policy
	 * @param added the entries that this install added, as {@link #installPermissions},
	 *              {@link #installConsentedEntries} and {@link #installSandboxEntries} returned them
	 * @return the sentences, empty when the entries need no explanation
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the configuration cannot be read
	 */
	List<String> describeAdded(Path home, Map<String, List<String>> added) throws IOException;

	/**
	 * Returns the sentences that tell the user which recorded entries {@link #removePermissions} left in place
	 * because the user changed them after install added them. Called after {@link #removePermissions}.
	 *
	 * @param home  the absolute, normalized harness home from the trust policy
	 * @param added the entries that {@link #removePermissions} was asked to remove
	 * @return the sentences, empty when it removed every entry
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative
	 * @throws IOException              if the configuration cannot be read
	 */
	List<String> describeRetained(Path home, Map<String, List<String>> added) throws IOException;

	/**
	 * Removes previously added entries that are still present, leaving everything else.
	 *
	 * @param home     the absolute, normalized harness home from the trust policy
	 * @param added    the entries that {@link #installPermissions} or {@link #installSandboxEntries} returned
	 * @param replacer replaces each configuration file that this call writes, retrying while Windows refuses the
	 *                 replacement
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the configuration cannot be changed
	 */
	void removePermissions(Path home, Map<String, List<String>> added, FileReplacer replacer) throws IOException;
}
