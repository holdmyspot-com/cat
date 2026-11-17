package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.RuntimeBinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The steps with which the installer installs a plugin tree into one harness: it writes the tree into CAT's
 * marketplace in the harness home, asks the harness to install it, which puts it in its version folder, and adds the
 * permission and sandbox entries that name the installer in that version folder. The harness never replaces the
 * version folder of another version, so these steps leave the version folder of a running installer as it is.
 */
public final class PluginInstall
{
	private final HarnessIntegration integration;
	private final HarnessCli cli;
	private final FileReplacer replacer;
	private final Waiting waiting;

	/**
	 * Creates the steps of one harness.
	 *
	 * @param integration the harness's integration
	 * @param cli         runs the harness program
	 * @param replacer    atomically replaces the harness configuration files that the integration writes
	 * @param waiting     creates the pauses between the retries of a deletion
	 * @throws NullPointerException if any of the arguments are null
	 */
	public PluginInstall(HarnessIntegration integration, HarnessCli cli, FileReplacer replacer, Waiting waiting)
	{
		requireThat(waiting, "waiting").isNotNull();
		this.waiting = waiting;
		requireThat(integration, "integration").isNotNull();
		requireThat(cli, "cli").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		this.integration = integration;
		this.cli = cli;
		this.replacer = replacer;
	}

	/**
	 * Returns the installer launcher of a plugin folder.
	 *
	 * @param plugin  the plugin folder
	 * @param windows true for the launcher of Windows
	 * @return the launcher
	 * @throws NullPointerException if {@code plugin} is null
	 */
	public static Path installer(Path plugin, boolean windows)
	{
		Path launcher = plugin.resolve(PluginDirectory.INSTALLER);
		if (windows)
			return launcher.resolveSibling(launcher.getFileName() + ".ps1");
		return launcher;
	}

	/**
	 * Returns the folder in which the session launcher of earlier releases kept its state, which
	 * {@link #installEntries} deletes.
	 *
	 * @param userHome the user's home directory
	 * @return {@code <userHome>/.cat/sessions}
	 * @throws NullPointerException if {@code userHome} is null
	 */
	public static Path formerLauncherState(Path userHome)
	{
		return userHome.resolve(".cat").resolve("sessions");
	}

	/**
	 * Prepares and records incoming permissions before any marketplace publication or registration.
	 *
	 * @param environment the recorded harness environment
	 * @param home        the harness home
	 * @param installer   the incoming launcher
	 * @throws IOException if permissions or ownership cannot be written
	 */
	public void preparePermissions(Map<String, String> environment, Path home, Path installer) throws IOException
	{
		InstallerState state = InstallerState.of(integration.installation(home).dataDirectory(), integration.harness());
		Path record = state.permissions();
		if (Files.notExists(state.pendingPermissions()))
			StateFiles.write(state.pendingPermissions(), new byte[]{'{', '}'});
		integration.preparePermissions(environment, home, installer, replacer, entries ->
		{
			PermissionRecord.add(record, entries);
			PermissionRecord.add(state.pendingPermissions(), entries);
		});
	}

	/**
	 * Writes a plugin tree into CAT's marketplace in the harness home, bound to the Java runtime that the plugin will
	 * have in its version folder, and asks the harness to install it into that version folder.
	 *
	 * @param home    the real harness home
	 * @param plugin  the plugin tree, which this method does not change
	 * @param version the plugin version that the tree states
	 * @return the version folder into which the harness installed the plugin
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the marketplace folder cannot be written, the harness does not install the
	 *                              plugin, or the version folder is missing afterwards
	 */
	public Path deliver(Path home, Path plugin, PluginVersion version) throws IOException
	{
		requireThat(home, "home").isNotNull();
		requireThat(plugin, "plugin").isNotNull();
		requireThat(version, "version").isNotNull();
		Path versionFolder = integration.installation(home).versionFolder(version);
		writeMarketplace(plugin, integration.marketplaceFolder(home, version), versionFolder, home);
		integration.register(home, version, cli, replacer);
		if (!Files.isDirectory(versionFolder, NOFOLLOW_LINKS))
		{
			throw new IOException(integration.harness() + " did not install CAT " + version + " into its version " +
				"folder " + versionFolder + ".");
		}
		return versionFolder;
	}

	/**
	 * Writes a plugin tree into the marketplace folder. The copy is staged beside its destination and then replaces
	 * it.
	 *
	 * @param plugin        the plugin tree
	 * @param marketplace   the marketplace folder that the harness installs the plugin from
	 * @param versionFolder the version folder into which the harness installs the plugin
	 * @param home          the harness home
	 * @throws IOException if the folder cannot be written, or the tree is the marketplace folder itself
	 */
	private void writeMarketplace(Path plugin, Path marketplace, Path versionFolder, Path home)
		throws IOException
	{
		if (marketplace.equals(plugin.toRealPath()))
			throw new IOException("The installer must not install CAT from " + plugin + ", which it replaces.");
		Path parent = Files.createDirectories(marketplace.getParent());
		Path staged = parent.resolve("." + marketplace.getFileName() + "-staged");
		LinkSafeDeletion.delete(staged, home, waiting);
		TreeCopy.copy(plugin, staged);
		RuntimeBinding.write(staged, versionFolder.resolve(PluginDirectory.RUNTIME));
		LinkSafeDeletion.delete(marketplace, home, waiting);
		Files.move(staged, marketplace);
	}

	/**
	 * Makes the change that the user allowed, when {@code consented}, removes the former session-launcher state
	 * folder, installs and records permission, sandbox and startup-file protections, then verifies final permissions.
	 * Only after verification does it retire obsolete recorded entries, including prepared rules, and remove the
	 * pending-permission record. A failure retains ownership and pending state for recovery.
	 *
	 * @param environment         the harness environment recorded in the trust policy
	 * @param home                the harness home
	 * @param installer           the installer launcher in the version folder
	 * @param installation        the harness's CAT installation
	 * @param others              the CAT installations of the other harnesses
	 * @param catLocks            the directory of the capacity locks
	 * @param formerLauncherState the absolute folder that {@link #formerLauncherState(Path)} returns
	 * @param startupFiles        the shell startup files that the harness's sessions may not change
	 * @param consented           true if the user allowed the change that {@link HarnessIntegration#consentRequest}
	 *                            asks about
	 * @return the recorded entries, and what the user needs to know about the entries that this call removed and added
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code formerLauncherState} is relative
	 * @throws IOException              if an entry or the record cannot be read or written, or the folder cannot be
	 *                                  deleted or is a link
	 */
	public InstalledEntries installEntries(Map<String, String> environment, Path home, Path installer,
		HarnessInstallation installation,
		List<HarnessInstallation> others, Path catLocks, Path formerLauncherState, List<Path> startupFiles,
		boolean consented) throws IOException
	{
		requireThat(home, "home").isNotNull();
		requireThat(installer, "installer").isNotNull();
		requireThat(installation, "installation").isNotNull();
		requireThat(others, "others").isNotNull();
		requireThat(catLocks, "catLocks").isNotNull();
		requireThat(formerLauncherState, "formerLauncherState").isAbsolute();
		requireThat(startupFiles, "startupFiles").isNotNull();
		Path record = InstallerState.of(installation.dataDirectory(), integration.harness()).permissions();
		Path pending = InstallerState.of(installation.dataDirectory(), integration.harness()).pendingPermissions();
		Map<String, List<String>> added = new LinkedHashMap<>(PermissionRecord.read(pending));
		Map<String, List<String>> consentedEntries = Map.of();
		if (consented)
		{
			consentedEntries = integration.installConsentedEntries(home, replacer);
			merge(added, consentedEntries);
		}

		Map<String, List<String>> maintained = new LinkedHashMap<>(integration.permissions(environment, installer));
		merge(maintained, consentedEntries);
		for (HarnessInstallation other : others)
			merge(maintained, integration.sandboxEntries(home, other, catLocks));
		merge(maintained, integration.readOnlyEntries(home, startupFiles));
		Path stateParent = LinkSafeDeletion.resolveRoot(formerLauncherState.getParent());
		LinkSafeDeletion.delete(stateParent.resolve(formerLauncherState.getFileName()), stateParent, waiting);
		merge(added, integration.installPermissions(environment, home, installer, replacer,
			entries -> PermissionRecord.add(record, entries)));
		for (HarnessInstallation other : others)
			merge(added, integration.installSandboxEntries(home, other, catLocks, replacer));
		merge(added, integration.installReadOnlyEntries(home, startupFiles, replacer));
		PermissionRecord.add(record, added);
		if (!integration.permissionsInstalled(environment, home, installer))
			throw new IOException("The installed permissions do not match " + installer + "; rerun install to repair them.");
		Map<String, List<String>> obsolete = PermissionRecord.obsolete(record, maintained);
		if (!obsolete.isEmpty())
		{
			integration.removePermissions(home, obsolete, replacer);
			PermissionRecord.remove(record, obsolete);
		}
		Map<String, List<String>> recorded = PermissionRecord.read(record);
		Files.deleteIfExists(InstallerState.of(installation.dataDirectory(), integration.harness()).pendingPermissions());
		return new InstalledEntries(recorded, integration.describeAdded(home, added));
	}

	/**
	 * Adds entries to a map of entries, keeping each key's entries in order and each entry once.
	 *
	 * @param target  the entries that receive the others
	 * @param entries the added entries
	 */
	private static void merge(Map<String, List<String>> target, Map<String, List<String>> entries)
	{
		for (Map.Entry<String, List<String>> entry : entries.entrySet())
		{
			List<String> values = new ArrayList<>(target.getOrDefault(entry.getKey(), List.of()));
			for (String value : entry.getValue())
			{
				if (!values.contains(value))
					values.add(value);
			}
			target.put(entry.getKey(), List.copyOf(values));
		}
	}

	/**
	 * The permission and sandbox entries after an install.
	 *
	 * @param recorded every entry that the installer has recorded, by entry key
	 * @param notices  the sentences that tell the user what the entries that this install added change beyond CAT
	 */
	public record InstalledEntries(Map<String, List<String>> recorded, List<String> notices)
	{
		/**
		 * Creates the entries.
		 *
		 * @param recorded every entry that the installer has recorded, by entry key
		 * @param notices  the sentences that tell the user what the entries that this install added change beyond
		 *                 CAT
		 * @throws NullPointerException if any of the arguments are null
		 */
		public InstalledEntries
		{
			recorded = Map.copyOf(recorded);
			notices = List.copyOf(notices);
		}

		/**
		 * Returns a message followed by the notices.
		 *
		 * @param message the message
		 * @return the message and the notices, separated by spaces
		 * @throws NullPointerException if {@code message} is null
		 */
		public String withNotices(String message)
		{
			requireThat(message, "message").isNotNull();
			StringJoiner joined = new StringJoiner(" ");
			joined.add(message);
			notices.forEach(joined::add);
			return joined.toString();
		}
	}
}
