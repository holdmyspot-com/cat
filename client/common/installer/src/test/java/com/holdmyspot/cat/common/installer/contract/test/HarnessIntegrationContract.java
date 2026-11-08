package com.holdmyspot.cat.common.installer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.ConsentRequest;
import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.engine.PluginInstall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks that a {@link HarnessIntegration} changes a harness home as the installer relies on: registration installs
 * the version that install wrote, unregistration keeps the data when asked to, and each configuration change is
 * returned once, maintained, undone by removal and explained to the user.
 */
public final class HarnessIntegrationContract
{
	/**
	 * The first version that the checks register.
	 */
	private static final PluginVersion VERSION = PluginVersion.parse("1.0+b0123456789ab");
	/**
	 * A version whose name sorts after {@link #VERSION}.
	 */
	private static final PluginVersion NEXT = PluginVersion.parse("1.1+bfedcba987654");
	/**
	 * The form of a harness identifier.
	 */
	private static final Pattern HARNESS = Pattern.compile("[a-z][a-z0-9-]{0,31}");
	/**
	 * Replaces files without waiting, because the checks' files are never held open.
	 */
	private static final FileReplacer REPLACER = new FileReplacer(Platform.current(), () ->
	{
	}, Clock.systemUTC());

	/**
	 * The integration under contract and how its harness behaves.
	 */
	private final Subject subject;

	/**
	 * Creates checks of one integration.
	 *
	 * @param subject the integration under contract and how its harness behaves
	 */
	public HarnessIntegrationContract(Subject subject)
	{
		this.subject = subject;
	}

	/**
	 * Verifies that the integration names its activation's harness in the documented form, and names the harness's
	 * product, program and agent session variable.
	 */
	public void namesHarness()
	{
		HarnessIntegration integration = subject.integration();

		requireThat(HARNESS.matcher(integration.harness()).matches(), "harnessForm").
			withContext(integration.harness(), "harness").isTrue();
		requireThat(integration.harness(), "harness").isEqualTo(subject.activation().harness());
		requireThat(integration.productName(), "productName").isNotBlank();
		requireThat(integration.programName(), "programName").isNotBlank();
		requireThat(integration.agentSessionVariable(), "agentSessionVariable").isNotBlank();
	}

	/**
	 * Verifies that the installation at a home is that home's, with the marketplace folder inside it, and that a
	 * user's environment without harness variables selects a home inside the user's home directory.
	 *
	 * @param home     an empty absolute directory
	 * @param userHome another empty absolute directory
	 */
	public void placesInstallationAtHome(Path home, Path userHome)
	{
		HarnessIntegration integration = subject.integration();

		requireThat(integration.installation(home).home(), "home").isEqualTo(home);
		requireThat(integration.marketplaceFolder(home, VERSION).startsWith(home), "marketplaceInsideHome").isTrue();
		Path selected = integration.installation(Map.of(), userHome).home();
		requireThat(selected.startsWith(userHome), "selectedInsideUserHome").withContext(selected, "selected").
			isTrue();
	}

	/**
	 * Verifies that the program environment that install records for a home selects that home.
	 *
	 * @param home     an empty absolute directory
	 * @param userHome another empty absolute directory
	 * @throws IOException if the environment lacks a variable that the harness needs
	 */
	public void recordsProgramHome(Path home, Path userHome) throws IOException
	{
		HarnessIntegration integration = subject.integration();
		Map<String, String> environment = Map.of("HOME", userHome.toString(), "USERPROFILE", userHome.toString());

		Map<String, String> recorded = integration.programEnvironment(home, environment);

		requireThat(integration.installation(recorded, userHome).home(), "home").isEqualTo(home);
	}

	/**
	 * Verifies that the harness program's answer to {@code --version} identifies it, and that a failed answer does
	 * not.
	 *
	 * @throws IOException if the program's answer is refused
	 */
	public void checksIdentity() throws IOException
	{
		HarnessIntegration integration = subject.integration();
		CommandResult answer = subject.versionAnswer();

		integration.requireIdentity(answer);
		try
		{
			integration.requireIdentity(new CommandResult(1, answer.output()));
			throw new AssertionError("A failed --version answer was accepted");
		}
		catch (IOException _)
		{
			// The failed program does not identify the harness
		}
	}

	/**
	 * Verifies that a home without configuration files is accepted.
	 *
	 * @param home an empty absolute directory
	 * @throws IOException if the configuration is refused
	 */
	public void acceptsFreshConfiguration(Path home) throws IOException
	{
		subject.integration().validateConfiguration(home);
	}

	/**
	 * Verifies that registration makes the harness load the version that install wrote, and then the next one.
	 *
	 * @param home an empty absolute directory
	 * @throws IOException if registration fails
	 */
	public void registersVersion(Path home) throws IOException
	{
		HarnessIntegration integration = subject.integration();
		PluginActivation activation = subject.activation();

		register(integration, home, VERSION);
		requireThat(activation.installed(home), "first").
			isEqualTo(new InstalledState(Optional.of(VERSION.toString()), true));
		register(integration, home, NEXT);
		requireThat(activation.installed(home), "next").
			isEqualTo(new InstalledState(Optional.of(NEXT.toString()), true));
	}

	/**
	 * Verifies that registration fails when install wrote no plugin, because the harness would not run the version.
	 *
	 * @param home an empty absolute directory
	 * @throws IOException if the home cannot be read
	 */
	public void refusesMissingPlugin(Path home) throws IOException
	{
		HarnessIntegration integration = subject.integration();
		try
		{
			integration.register(home, VERSION, subject.cli(home), REPLACER);
			throw new AssertionError("Registration without a plugin succeeded");
		}
		catch (IOException _)
		{
			// The harness has no plugin to run
		}
		requireThat(subject.activation().installed(home).version(), "installed").isEmpty();
	}

	/**
	 * Verifies that unregistration keeps the data directory when asked to keep it, and otherwise leaves it as the
	 * harness's own uninstall does.
	 *
	 * @param kept    an empty absolute directory, whose data unregistration is asked to keep
	 * @param deleted another empty absolute directory, whose data unregistration is not asked to keep
	 * @throws IOException if registration or unregistration fails
	 */
	public void unregistersKeepingDataOnRequest(Path kept, Path deleted) throws IOException
	{
		HarnessIntegration integration = subject.integration();
		for (Path home : List.of(kept, deleted))
		{
			register(integration, home, VERSION);
			Files.writeString(Files.createDirectories(integration.installation(home).dataDirectory()).
				resolve("kept"), "data");
		}

		integration.unregister(kept, subject.cli(kept), true);
		integration.unregister(deleted, subject.cli(deleted), false);

		requireThat(Files.exists(integration.installation(kept).dataDirectory().resolve("kept"),
			LinkOption.NOFOLLOW_LINKS), "keptData").isTrue();
		requireThat(Files.exists(integration.installation(deleted).dataDirectory().resolve("kept"),
			LinkOption.NOFOLLOW_LINKS), "dataLeft").isEqualTo(!subject.deletesData());
	}

	/**
	 * Verifies that the harness never runs the registered version's own folder instead of it, never runs an older
	 * folder, and runs a newer folder only if it selects version folders by name.
	 */
	public void ordersVersionFolders()
	{
		HarnessIntegration integration = subject.integration();

		requireThat(integration.runsInstead(VERSION, VERSION), "same").isFalse();
		requireThat(integration.runsInstead(VERSION, NEXT), "older").isFalse();
		requireThat(integration.runsInstead(NEXT, VERSION), "newer").isEqualTo(subject.selectsByName());
	}

	/**
	 * Verifies that keeping sessions from changing files adds exactly the maintained entries, that a repeat adds none,
	 * and that removing them removes them completely, so that adding them again adds them again.
	 *
	 * @param home     an empty absolute directory
	 * @param userHome another empty absolute directory, standing for the user's home folder
	 * @throws IOException if the configuration cannot be changed
	 */
	public void installsReadOnlyEntriesOnce(Path home, Path userHome) throws IOException
	{
		HarnessIntegration integration = subject.integration();
		subject.arrangeReadOnlyEntries(integration, home);
		List<Path> files = List.of(userHome.resolve(".bashrc"), userHome.resolve(".zshenv"));
		Map<String, List<String>> maintained = integration.readOnlyEntries(home, files);

		Map<String, List<String>> added = integration.installReadOnlyEntries(home, files, REPLACER);
		requireThat(added, "added").isEqualTo(maintained);
		requireThat(integration.installReadOnlyEntries(home, files, REPLACER), "again").isEmpty();
		integration.removePermissions(home, added, REPLACER);
		requireThat(integration.describeRetained(home, added), "retained").isEmpty();
		requireThat(integration.installReadOnlyEntries(home, files, REPLACER), "restored").isEqualTo(added);
	}

	/**
	 * Verifies that installing the permissions of a fresh home adds every maintained entry, that a repeat adds none,
	 * and that removing them removes them completely, so that installing them again adds them again.
	 *
	 * @param home an empty absolute directory
	 * @throws IOException if the configuration cannot be changed
	 */
	public void installsPermissionsOnce(Path home) throws IOException
	{
		HarnessIntegration integration = subject.integration();
		register(integration, home, VERSION);
		Path installer = PluginInstall.installer(integration.installation(home).versionFolder(VERSION),
			Platform.current().isWindows());
		Map<String, List<String>> maintained = integration.permissions(installer);
		requireThat(maintained, "maintained").isNotEmpty();

		Map<String, List<String>> added = integration.installPermissions(home, installer, REPLACER);
		requireThat(added, "added").isEqualTo(maintained);
		requireThat(integration.installPermissions(home, installer, REPLACER), "again").isEmpty();
		integration.removePermissions(home, added, REPLACER);
		requireThat(integration.describeRetained(home, added), "retained").isEmpty();
		requireThat(integration.installPermissions(home, installer, REPLACER), "restored").isEqualTo(added);
	}

	/**
	 * Verifies that installing the sandbox entries of a fresh home adds only maintained entries, that a repeat adds
	 * none, and that removing them removes them completely, so that installing them again adds them again.
	 *
	 * @param home      an empty absolute directory
	 * @param otherHome another empty absolute directory, standing for the other harness's home
	 * @throws IOException if the configuration cannot be changed
	 */
	public void installsSandboxEntriesOnce(Path home, Path otherHome) throws IOException
	{
		HarnessIntegration integration = subject.integration();
		HarnessInstallation other = integration.installation(otherHome);
		Path catLocks = otherHome.resolve("locks");
		Map<String, List<String>> maintained = integration.sandboxEntries(home, other, catLocks);

		Map<String, List<String>> added = integration.installSandboxEntries(home, other, catLocks, REPLACER);

		requireThat(added, "added").isNotEmpty();
		requireMaintained(added, maintained);
		requireThat(integration.installSandboxEntries(home, other, catLocks, REPLACER), "again").
			isEmpty();
		integration.removePermissions(home, added, REPLACER);
		requireThat(integration.describeRetained(home, added), "retained").isEmpty();
		requireThat(integration.installSandboxEntries(home, other, catLocks, REPLACER), "restored").
			isEqualTo(added);
	}

	/**
	 * Verifies that a fresh home needs no consent and gets no consented entries, and that a home whose configuration
	 * needs consent asks once, maintains the consented entries as sandbox entries, explains them, and removes them
	 * completely.
	 *
	 * @param home      an empty absolute directory
	 * @param otherHome another empty absolute directory, standing for the other harness's home
	 * @throws IOException if the configuration cannot be read or changed
	 */
	public void asksConsentOnce(Path home, Path otherHome) throws IOException
	{
		HarnessIntegration integration = subject.integration();
		Path catLocks = otherHome.resolve("locks");
		requireThat(integration.consentRequest(home, catLocks), "fresh").isEmpty();
		requireThat(integration.installConsentedEntries(home, REPLACER), "freshEntries").isEmpty();
		if (!subject.arrangeConsent(integration, home))
			return;

		requireThat(integration.consentRequest(home, catLocks).map(ConsentRequest::question).orElse(""), "question").
			isNotBlank();
		Map<String, List<String>> consented = integration.installConsentedEntries(home, REPLACER);

		requireThat(consented, "consented").isNotEmpty();
		requireThat(integration.consentRequest(home, catLocks), "asked").isEmpty();
		requireMaintained(consented, integration.sandboxEntries(home, integration.installation(otherHome), catLocks));
		requireThat(integration.describeAdded(home, consented), "described").isNotEmpty();
		integration.removePermissions(home, consented, REPLACER);
		requireThat(integration.describeRetained(home, consented), "retained").isEmpty();
	}

	/**
	 * Verifies that a sandbox entry that install adds to a setting of the user's, which other uses of that setting
	 * share, is explained to the user once.
	 *
	 * @param home      an empty absolute directory
	 * @param otherHome another empty absolute directory, standing for the other harness's home
	 * @throws IOException if the configuration cannot be read or changed
	 */
	public void describesSharedEntriesOnce(Path home, Path otherHome) throws IOException
	{
		HarnessIntegration integration = subject.integration();
		if (!subject.arrangeSharedSetting(integration, home))
			return;
		HarnessInstallation other = integration.installation(otherHome);
		Path catLocks = otherHome.resolve("locks");

		Map<String, List<String>> added = integration.installSandboxEntries(home, other, catLocks, REPLACER);

		requireThat(integration.describeAdded(home, added), "described").isNotEmpty();
		Map<String, List<String>> again = integration.installSandboxEntries(home, other, catLocks, REPLACER);
		requireThat(integration.describeAdded(home, again), "describedAgain").isEmpty();
	}

	/**
	 * Verifies that uninstall tells the user that it left a consented entry that the user changed after install
	 * added it.
	 *
	 * @param home      an empty absolute directory
	 * @param otherHome another empty absolute directory, standing for the other harness's home
	 * @throws IOException if the configuration cannot be read or changed
	 */
	public void leavesChangedEntry(Path home, Path otherHome) throws IOException
	{
		HarnessIntegration integration = subject.integration();
		if (!subject.arrangeConsent(integration, home))
			return;
		requireThat(integration.consentRequest(home, otherHome.resolve("locks")).map(ConsentRequest::question).
			orElse(""), "question").
			isNotBlank();
		Map<String, List<String>> consented = integration.installConsentedEntries(home, REPLACER);
		subject.changeConsentedEntry(integration, home);

		integration.removePermissions(home, consented, REPLACER);

		requireThat(integration.describeRetained(home, consented), "retained").isNotEmpty();
	}

	/**
	 * Verifies that the entries that install recorded fall into groups as the installer's report shows them: each
	 * configuration setting holds groups of the effects that the harness gives its entries, the groups name exactly
	 * the configuration files of the recorded entries, each group's entries are recorded entries as the configuration
	 * holds them, and each item is a recorded entry, or a name or operation that one of the group's entries names, and
	 * an absolute path when the effect's items are paths. No recorded entries form no groups.
	 *
	 * @param home      an empty absolute directory
	 * @param otherHome another empty absolute directory, standing for the other harness's home
	 * @throws IOException if the configuration cannot be read or changed
	 */
	public void groupsRecordedEntries(Path home, Path otherHome) throws IOException
	{
		HarnessIntegration integration = subject.integration();
		register(integration, home, VERSION);
		Path installer = PluginInstall.installer(integration.installation(home).versionFolder(VERSION),
			Platform.current().isWindows());
		Map<String, List<String>> recorded = new LinkedHashMap<>(integration.installPermissions(home, installer,
			REPLACER));
		Map<String, List<String>> sandbox = integration.installSandboxEntries(home, integration.installation(otherHome),
			otherHome.resolve("locks"), REPLACER);
		sandbox = new LinkedHashMap<>(sandbox);
		for (Map.Entry<String, List<String>> entry : integration.installReadOnlyEntries(home,
			List.of(otherHome.resolve(".bashrc")), REPLACER).entrySet())
		{
			List<String> values = new ArrayList<>(sandbox.getOrDefault(entry.getKey(), List.of()));
			values.addAll(entry.getValue());
			sandbox.put(entry.getKey(), values);
		}
		for (Map.Entry<String, List<String>> entry : sandbox.entrySet())
		{
			List<String> values = new ArrayList<>(recorded.getOrDefault(entry.getKey(), List.of()));
			values.addAll(entry.getValue());
			recorded.put(entry.getKey(), values);
		}
		Map<Path, List<String>> recordedByFile = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : recorded.entrySet())
		{
			Path file = Path.of(entry.getKey().substring(0, entry.getKey().indexOf('#')));
			recordedByFile.computeIfAbsent(file, _ -> new ArrayList<>()).addAll(entry.getValue());
		}

		List<EntryGroup> groups = integration.describeEntries(recorded, installer);

		requireThat(integration.describeEntries(Map.of(), installer), "noEntries").isEmpty();
		Map<String, Set<EntryEffect>> effects = new LinkedHashMap<>();
		for (EntryGroup group : groups)
		{
			// A group's key is its file and setting, with an empty setting when CAT owns the whole file
			String key = group.file().toString().replace('\\', '/') + "#" + group.setting();
			effects.computeIfAbsent(key, _ -> EnumSet.noneOf(EntryEffect.class)).add(group.effect());
			requireGrouped(group, recordedByFile.getOrDefault(group.file(), List.of()));
		}
		requireThat(effects, "effects").isEqualTo(subject.effects());
		requireThat(groups.stream().map(EntryGroup::file).collect(Collectors.toSet()), "files").
			isEqualTo(recordedByFile.keySet());
	}

	/**
	 * Requires a group's entries to be recorded entries as the configuration holds them, and its items to be recorded
	 * entries or what its entries name.
	 *
	 * @param group    the group
	 * @param recorded the recorded entries of the group's file
	 */
	private static void requireGrouped(EntryGroup group, List<String> recorded)
	{
		requireThat(group.entries(), "entries").withContext(group, "group").isNotEmpty();
		requireThat(group.items(), "items").withContext(group, "group").isNotEmpty();
		for (String entry : group.entries())
		{
			// The configuration holds an entry as it is, as a quoted string, whose backslashes are escaped, or as a
			// line of a file that CAT owns
			requireThat(recorded.stream().anyMatch(value -> entry.contains(value) || value.contains(entry) ||
					entry.contains(value.replace("\\", "\\\\"))), "recordedEntry").withContext(entry, "entry").
				withContext(group, "group").isTrue();
		}
		for (String item : group.items())
		{
			// An operation's item names the launcher before the operation, such as "installer install", whose entry
			// names the launcher by its path
			String named = item;
			if (group.effect().items() == EntryEffect.Items.OPERATIONS)
				named = item.substring(item.lastIndexOf(' ') + 1);
			String word = named;
			requireThat(recorded.contains(item) || group.entries().stream().anyMatch(entry -> entry.contains(word)),
				"namedItem").withContext(item, "item").withContext(group, "group").isTrue();
			if (group.effect().items() == EntryEffect.Items.PATHS)
			{
				requireThat(Path.of(item).isAbsolute(), "absoluteItem").withContext(item, "item").
					withContext(group, "group").isTrue();
			}
		}
	}

	/**
	 * Verifies that no added entries need no explanation.
	 *
	 * @param home an empty absolute directory
	 * @throws IOException if the configuration cannot be read
	 */
	public void describesNothingAdded(Path home) throws IOException
	{
		requireThat(subject.integration().describeAdded(home, Map.of()), "described").isEmpty();
	}

	/**
	 * Writes a version's plugin as install does and registers it.
	 *
	 * @param integration the integration
	 * @param home        the harness home
	 * @param version     the version
	 * @throws IOException if registration fails
	 */
	private void register(HarnessIntegration integration, Path home, PluginVersion version) throws IOException
	{
		subject.writePlugin(home, version);
		integration.register(home, version, subject.cli(home), REPLACER);
	}

	/**
	 * Requires every entry to be one that the integration maintains.
	 *
	 * @param entries    the entries
	 * @param maintained the maintained entries
	 */
	private static void requireMaintained(Map<String, List<String>> entries, Map<String, List<String>> maintained)
	{
		for (Map.Entry<String, List<String>> entry : entries.entrySet())
		{
			requireThat(maintained.getOrDefault(entry.getKey(), List.of()), entry.getKey()).
				containsAll(entry.getValue());
		}
	}

	/**
	 * The integration under contract and how its harness behaves.
	 */
	public interface Subject
	{
		/**
		 * Returns a new integration under contract.
		 *
		 * @return the integration
		 */
		HarnessIntegration integration();

		/**
		 * Returns the activation of the integration's harness.
		 *
		 * @return the activation
		 */
		PluginActivation activation();

		/**
		 * Returns a runner of the harness program in one home that answers as the harness program does.
		 *
		 * @param home the absolute harness home
		 * @return the runner
		 */
		HarnessCli cli(Path home);

		/**
		 * Writes a version's plugin into the integration's marketplace folder as install does.
		 *
		 * @param home    the absolute harness home
		 * @param version the version
		 * @throws IOException if the plugin cannot be written
		 */
		void writePlugin(Path home, PluginVersion version) throws IOException;

		/**
		 * Returns the harness program's answer to {@code --version}.
		 *
		 * @return the answer
		 */
		CommandResult versionAnswer();

		/**
		 * Returns whether the harness deletes the plugin's data directory when it uninstalls the plugin without being
		 * asked to keep it.
		 *
		 * @return true if it deletes the directory
		 */
		boolean deletesData();

		/**
		 * Returns whether the harness selects a version folder by its name.
		 *
		 * @return true if it runs the folder whose name sorts last
		 */
		boolean selectsByName();

		/**
		 * Configures a home so that {@link HarnessIntegration#installReadOnlyEntries} adds entries, if the harness needs
		 * a configuration for it.
		 *
		 * @param integration the integration under contract
		 * @param home        the absolute harness home
		 * @throws IOException if the configuration cannot be written
		 */
		void arrangeReadOnlyEntries(HarnessIntegration integration, Path home) throws IOException;

		/**
		 * Configures a home so that install must ask for consent, if the harness has such a configuration.
		 *
		 * @param integration the integration under contract
		 * @param home        the absolute harness home
		 * @return false if no configuration of the harness needs consent
		 * @throws IOException if the configuration cannot be written
		 */
		boolean arrangeConsent(HarnessIntegration integration, Path home) throws IOException;

		/**
		 * Changes, as the user would, the setting that install changed with the user's consent in a home that
		 * {@link #arrangeConsent(HarnessIntegration, Path)} configured.
		 *
		 * @param integration the integration under contract
		 * @param home        the absolute harness home
		 * @throws IOException if the configuration cannot be written
		 */
		void changeConsentedEntry(HarnessIntegration integration, Path home) throws IOException;

		/**
		 * Configures a home so that install adds a sandbox entry to a setting of the user's that other uses of the
		 * setting share, if the harness has such a setting.
		 *
		 * @param integration the integration under contract
		 * @param home        the absolute harness home
		 * @return false if the harness has no such setting
		 * @throws IOException if the configuration cannot be written
		 */
		boolean arrangeSharedSetting(HarnessIntegration integration, Path home) throws IOException;

		/**
		 * Returns the effects that the harness gives the entries that install adds to a fresh home, as the
		 * installer's report groups them.
		 *
		 * @return each group's effects, by the group's configuration file, {@code #} and setting, which is empty when
		 * 	CAT owns the whole file
		 */
		Map<String, Set<EntryEffect>> effects();
	}
}
