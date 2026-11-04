package com.holdmyspot.cat.claude.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.installer.Integration;
import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.StringJoiner;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests Claude Code's {@link HarnessIntegration} service.
 */
public final class IntegrationTest
{
	/**
	 * The answer that Claude Code 2.1.318 printed, with exit status 1, to {@code plugin uninstall cat@cat} when CAT
	 * was not installed.
	 */
	private static final String PLUGIN_NOT_FOUND = "✘ Failed to uninstall plugin \"cat@cat\": Plugin \"cat@cat\" not " +
		"found in installed plugins\n";
	/**
	 * The answer that Claude Code 2.1.318 printed, with exit status 1, to {@code plugin marketplace remove cat} when
	 * the marketplace was not added.
	 */
	private static final String MARKETPLACE_NOT_FOUND = "✘ Failed to remove marketplace: Marketplace 'cat' not found\n";
	private static final String SETTINGS_KEY_EXCLUDED = "settings.json#sandbox.excludedCommands";
	private static final String SETTINGS_KEY_ALLOW = "settings.json#permissions.allow";
	private static final String SETTINGS_KEY_DENY_WRITE = "settings.json#sandbox.filesystem.denyWrite";
	private static final String SETTINGS_KEY_ALLOW_WRITE = "settings.json#sandbox.filesystem.allowWrite";
	/**
	 * The plugin version that registration installs first.
	 */
	private static final PluginVersion VERSION = PluginVersion.parse("1.0+b0123456789ab");
	/**
	 * A later build of the same release.
	 */
	private static final PluginVersion NEW_VERSION = PluginVersion.parse("1.0+bfedcba987654");

	/**
	 * Creates a test instance.
	 */
	public IntegrationTest()
	{
	}

	/**
	 * Service loading finds exactly one Claude Code integration.
	 */
	@Test
	public void selectsClaudeIntegration()
	{
		List<HarnessIntegration> providers = ServiceLoader.load(HarnessIntegration.class).stream().
			map(ServiceLoader.Provider::get).filter(provider -> provider.harness().equals("claude")).toList();

		requireThat(providers, "providers").size().isEqualTo(1);
	}

	/**
	 * Setup finds Claude Code's {@code claude} program.
	 */
	@Test
	public void namesClaudeProgram()
	{
		requireThat(new Integration(Platform.LINUX_X64).programName(), "programName").isEqualTo("claude");
	}

	/**
	 * The recorded environment names the user's home directory and leaves {@code CLAUDE_CONFIG_DIR} unset for the
	 * default configuration directory, so that Claude Code finds its {@code .claude.json} in the user's home.
	 *
	 * @param platform the platform, whose home directory variable differs
	 * @throws IOException if the environment is refused
	 */
	@Test(dataProvider = "platforms")
	public void environmentForDefaultDirectory(Platform platform) throws IOException
	{
		Path userHome = absoluteTestPath("user");

		Map<String, String> variables = new Integration(platform).programEnvironment(userHome.resolve(".claude"),
			Map.of(platform.homeVariable(), userHome.toString(), "UNRELATED", "value"));

		requireThat(variables, "variables").isEqualTo(Map.of(platform.homeVariable(), userHome.toString()));
	}

	/**
	 * Supplies platforms with different home directory variables.
	 *
	 * @return a POSIX and a Windows platform
	 */
	@DataProvider
	public Object[][] platforms()
	{
		return new Object[][] {{Platform.LINUX_X64}, {Platform.WINDOWS_X64}};
	}

	/**
	 * A configuration directory other than the default one is recorded in {@code CLAUDE_CONFIG_DIR}, which also moves
	 * Claude Code's {@code .claude.json}.
	 *
	 * @throws IOException if the environment is refused
	 */
	@Test
	public void environmentRecordsConfigDirectory() throws IOException
	{
		Path userHome = absoluteTestPath("user");
		Path home = absoluteTestPath("configurations/claude");

		Map<String, String> variables = new Integration(Platform.LINUX_X64).programEnvironment(home,
			Map.of("HOME", userHome.toString()));

		requireThat(variables, "variables").isEqualTo(Map.of("HOME", userHome.toString(), "CLAUDE_CONFIG_DIR",
			home.toString()));
	}

	/**
	 * Setup cannot tell Claude Code's default configuration directory without the user's home directory.
	 *
	 * @throws IOException if the environment is refused
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "HOME is not set.*")
	public void environmentRequiresUserHome() throws IOException
	{
		new Integration(Platform.LINUX_X64).programEnvironment(absoluteTestPath("user/.claude"),
			Map.of());
	}

	/**
	 * A program that answers {@code --version} with Claude Code's version line is Claude Code.
	 *
	 * @throws IOException if the answer is refused
	 */
	@Test
	public void identityAcceptsClaudeCode() throws IOException
	{
		new Integration(Platform.LINUX_X64).requireIdentity(new CommandResult(0, "2.1.318 (Claude Code)\n"));
	}

	/**
	 * A program named {@code claude} that does not answer as a working Claude Code is refused, naming what the user
	 * must install.
	 *
	 * @param output     what the program printed
	 * @param exitStatus the program's exit status
	 * @throws IOException if the answer is refused
	 */
	@Test(dataProvider = "brokenPrograms", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s)claude --version did not identify Claude Code.*Install Claude Code.*")
	public void identityRefusesOtherProgram(String output, int exitStatus) throws IOException
	{
		new Integration(Platform.LINUX_X64).requireIdentity(new CommandResult(exitStatus, output));
	}

	/**
	 * Supplies answers of programs named {@code claude} that are not a working Claude Code.
	 *
	 * @return each program's output and exit status
	 */
	@DataProvider
	public Object[][] brokenPrograms()
	{
		return new Object[][] {{"2.1.318 (Claude Code)", 1}, {"other program 1.0", 0}};
	}

	/**
	 * A first registration points the marketplace manifest at the new version folder, adds the marketplace and
	 * installs CAT, so that Claude Code loads the plugin in place from that version folder.
	 *
	 * @throws IOException if the fixture cannot be created or registration fails
	 */
	@Test
	public void registerInstallsFromVersionFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path versionFolder = writeVersionFolder(home, VERSION);
			FakeClaude claude = new FakeClaude(home);

			integration().register(home, VERSION, claude, replacer());

			ClaudeInstallation installation = new ClaudeInstallation(home);
			requireThat(claude.calls(), "calls").isEqualTo(List.of(
				List.of("plugin", "marketplace", "add", installation.marketplaceRoot().toString()),
				List.of("plugin", "install", "cat@cat")));
			requireThat(claude.loadedFrom(), "loadedFrom").isEqualTo(Optional.of(versionFolder));
		}
	}

	/**
	 * Registering a new version of an installed CAT points the marketplace manifest at the new version folder and
	 * updates CAT, because Claude Code's install leaves an installed plugin unchanged.
	 *
	 * @throws IOException if the fixture cannot be created or registration fails
	 */
	@Test
	public void registerUpdatesToNewVersionFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			writeVersionFolder(home, VERSION);
			FakeClaude.recordInstalled(home, VERSION.toString());
			Path newFolder = writeVersionFolder(home, NEW_VERSION);
			FakeClaude claude = new FakeClaude(home);

			integration().register(home, NEW_VERSION, claude, replacer());

			requireThat(claude.calls().getLast(), "lastCall").isEqualTo(List.of("plugin", "update", "cat@cat"));
			requireThat(claude.loadedFrom(), "loadedFrom").isEqualTo(Optional.of(newFolder));
		}
	}

	/**
	 * Registration fails, naming both versions, when Claude Code answers success but still records another version, so
	 * that install does not report a version that Claude Code does not load.
	 *
	 * @throws IOException if the fixture cannot be created or the failure is returned
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*1\\.0\\+bfedcba987654.*1\\.0\\+b0123456789ab.*")
	public void registerRefusesUnswitchedVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			writeVersionFolder(home, VERSION);
			FakeClaude.recordInstalled(home, VERSION.toString());
			writeVersionFolder(home, NEW_VERSION);
			// Claude Code answers success without switching versions, as it would for a plugin it read elsewhere.
			RecordingCli claude = new RecordingCli(List.of(new CommandResult(0, "ok"), new CommandResult(0, "ok")));

			integration().register(home, NEW_VERSION, claude, replacer());
		}
	}

	/**
	 * Returns two builds of one release, in both orders: the build that Claude Code records as installed and the build
	 * that registration names.
	 *
	 * @return the installed and the registered version
	 */
	@DataProvider
	public Object[][] buildPairs()
	{
		return new Object[][]
		{
			{VERSION, NEW_VERSION},
			{NEW_VERSION, VERSION}
		};
	}

	/**
	 * With two version folders of one release present, registration through the harness integration switches Claude
	 * Code to the folder of the version that it names, whichever of the two is installed or was written first.
	 *
	 * @param installed  the version that Claude Code records as installed, whose folder is written first
	 * @param registered the version to register
	 * @throws IOException if the fixture cannot be created or registration fails
	 */
	@Test(dataProvider = "buildPairs")
	public void registerChoosesNamedVersionFolder(PluginVersion installed, PluginVersion registered)
		throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			writeVersionFolder(home, installed);
			FakeClaude.recordInstalled(home, installed.toString());
			Path registeredFolder = writeVersionFolder(home, registered);
			FakeClaude claude = new FakeClaude(home);
			HarnessIntegration integration = integration();

			integration.register(home, registered, claude, replacer());

			requireThat(claude.loadedFrom(), "loadedFrom").isEqualTo(Optional.of(registeredFolder));
		}
	}

	/**
	 * Registration replaces an outdated marketplace manifest although the file system refuses the first attempt, as
	 * Windows does while another process reads the file.
	 *
	 * @throws IOException if the fixture cannot be created or registration fails
	 */
	@Test
	public void registerRetriesRefusedManifest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path versionFolder = writeVersionFolder(home, VERSION);
			Path manifest = Files.createDirectories(new ClaudeInstallation(home).marketplaceRoot().
				resolve(".claude-plugin")).resolve("marketplace.json");
			Files.writeString(manifest, "{\"name\": \"outdated\"}\n");
			TransientRefusal refusal = new TransientRefusal(manifest);
			FakeClaude claude = new FakeClaude(home);

			integration().register(home, VERSION, claude, refusal.replacer());

			requireThat(claude.loadedFrom(), "loadedFrom").isEqualTo(Optional.of(versionFolder));
			requireThat(refusal.pauses(), "pauses").isEqualTo(1);
		}
	}

	/**
	 * Adding and removing permission entries rewrite {@code settings.json} although the file system refuses each
	 * first attempt, as Windows does while another process reads the file.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void permissionsRetryRefusedSettings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path settings = Files.writeString(home.resolve("settings.json"), "{}\n");
			Path launcher = launcher(home);
			TransientRefusal adding = new TransientRefusal(settings);

			Map<String, List<String>> added = integration().installPermissions(home, launcher,
				adding.replacer());

			requireThat(added, "added").isEqualTo(posixEntries(launcher));
			requireThat(Files.readString(settings), "settings").contains("excludedCommands");
			requireThat(adding.pauses(), "addingPauses").isEqualTo(1);
			TransientRefusal removing = new TransientRefusal(settings);

			integration().removePermissions(home, added, removing.replacer());

			requireThat(Files.readString(settings), "settings").doesNotContain(launcher.toString());
			requireThat(removing.pauses(), "removingPauses").isEqualTo(1);
		}
	}

	/**
	 * Registration fails, naming Claude Code's answer, when a command fails.
	 *
	 * @param refused the subcommand that Claude Code refuses
	 * @param earlier the version that Claude Code has installed, or empty
	 * @throws IOException if the fixture cannot be created or the failure is returned
	 */
	@Test(dataProvider = "registrationCalls", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*network unavailable.*")
	public void registerFailsOnRefusal(String refused, String earlier) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			writeVersionFolder(home, VERSION);
			if (!earlier.isEmpty())
				FakeClaude.recordInstalled(home, earlier);

			integration().register(home, VERSION, new FakeClaude(home, refused), replacer());
		}
	}

	/**
	 * Supplies the registration commands that can fail.
	 *
	 * @return each refused subcommand and the version installed before registration
	 */
	@DataProvider
	public Object[][] registrationCalls()
	{
		return new Object[][] {{"add", ""}, {"install", ""}, {"update", NEW_VERSION.toString()}};
	}

	/**
	 * Unregistration uninstalls CAT and then removes its marketplace, treating Claude Code's not-found answers as
	 * success.
	 *
	 * @throws IOException if the fixture cannot be created or unregistration fails
	 */
	@Test
	public void unregisterAcceptsAbsence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			RecordingCli cli = new RecordingCli(List.of(new CommandResult(1, PLUGIN_NOT_FOUND),
				new CommandResult(1, MARKETPLACE_NOT_FOUND)));

			integration().unregister(home, cli, false);

			requireThat(cli.calls(), "calls").isEqualTo(List.of(List.of("plugin", "uninstall", "cat@cat"),
				List.of("plugin", "marketplace", "remove", "cat")));
		}
	}

	/**
	 * Unregistration that keeps the data asks Claude Code to keep the plugin's data directory, which its uninstall
	 * deletes otherwise.
	 *
	 * @throws IOException if the fixture cannot be created or unregistration fails
	 */
	@Test
	public void unregisterKeepsData() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			RecordingCli cli = new RecordingCli(List.of(new CommandResult(0, ""), new CommandResult(0, "")));

			integration().unregister(home, cli, true);

			requireThat(cli.calls(), "calls").isEqualTo(List.of(List.of("plugin", "uninstall", "--keep-data", "cat@cat"),
				List.of("plugin", "marketplace", "remove", "cat")));
		}
	}

	/**
	 * Unregistration fails on any other refusal, so uninstall does not report a registration it left behind.
	 *
	 * @param answers Claude Code's answers to the two commands
	 * @throws IOException if the fixture cannot be created or the failure is returned
	 */
	@Test(dataProvider = "unregistrationFailures", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*settings are locked.*")
	public void unregisterFailsOnRefusal(List<CommandResult> answers) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());

			integration().unregister(home, new RecordingCli(answers), false);
		}
	}

	/**
	 * Supplies a refusal of each unregistration command.
	 *
	 * @return the answers to both commands
	 */
	@DataProvider
	public Object[][] unregistrationFailures()
	{
		CommandResult refusal = new CommandResult(1, "✘ settings are locked");
		return new Object[][] {{List.of(refusal, new CommandResult(0, ""))},
			{List.of(new CommandResult(0, ""), refusal)}};
	}

	/**
	 * Permission installation creates {@code settings.json} with exactly the entries for the agent operations, in
	 * Claude Code's two-space layout, and returns every entry as added.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void permissionsCreateSettings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path launcher = launcher(home);
			Map<String, List<String>> expected = posixEntries(launcher);

			Map<String, List<String>> added = integration().installPermissions(home, launcher, replacer());

			requireThat(added, "added").isEqualTo(expected);
			StringJoiner excluded = new StringJoiner(",\n", "", "\n");
			for (String entry : expected.get(SETTINGS_KEY_EXCLUDED))
				excluded.add("      " + json(entry));
			StringJoiner allowed = new StringJoiner(",\n", "", "\n");
			for (String entry : expected.get(SETTINGS_KEY_ALLOW))
				allowed.add("      " + json(entry));
			requireThat(Files.readString(home.resolve("settings.json")), "settings").isEqualTo("{\n" +
				"  \"sandbox\": {\n" +
				"    \"excludedCommands\": [\n" + excluded +
				"    ]\n" +
				"  },\n" +
				"  \"permissions\": {\n" +
				"    \"allow\": [\n" + allowed +
				"    ]\n" +
				"  }\n" +
				"}\n");
		}
	}

	/**
	 * Uninstall is never allowed without a prompt; only status, install and update are. Every operation runs outside
	 * the sandbox, and setup is never named.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void permissionsPromptForUninstall() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path launcher = launcher(home);

			Map<String, List<String>> added = integration().installPermissions(home, launcher, replacer());

			String quoted = ShellQuoting.posix(launcher.toString());
			List<String> allowed = added.get(SETTINGS_KEY_ALLOW);
			requireThat(allowed, "allowed").isEqualTo(List.of(
				"Bash(" + quoted + " install --harness claude)",
				"Bash(" + quoted + " update --harness claude)",
				"Bash(" + pattern(quoted + " update --harness claude") + ")",
				"Bash(" + quoted + " status)",
				"Bash(" + quoted + " status --harness claude)"));
			requireThat(added.get(SETTINGS_KEY_EXCLUDED), "excluded").containsAll(List.of(
				quoted + " uninstall --harness claude", quoted + " uninstall --harness claude --delete-data"));
			for (List<String> entries : added.values())
			{
				for (String entry : entries)
					requireThat(entry, "entry").doesNotContain("setup");
			}
		}
	}

	/**
	 * The only sandbox exclusion with a wildcard is the one for {@code update --harness claude} followed by a release
	 * argument, so that a named-release update runs outside the sandbox without a prompt. Claude Code 2.1.277 and later
	 * keep a call in the sandbox unless every command in it matches an exclusion; earlier versions ran the whole call
	 * outside it when one command matched a wildcard exclusion, so CAT requires 2.1.277 or later. Every other exclusion
	 * is one exact command, because no other operation takes a release.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void onlyUpdateExclusionHasWildcard() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path launcher = launcher(home);

			Map<String, List<String>> added = integration().installPermissions(home, launcher, replacer());

			String quoted = ShellQuoting.posix(launcher.toString());
			String updateWildcard = pattern(quoted + " update --harness claude");
			List<String> excluded = added.get(SETTINGS_KEY_EXCLUDED);
			List<String> wildcards = excluded.stream().filter(entry -> entry.contains("*")).toList();
			requireThat(wildcards, "wildcards").isEqualTo(List.of(updateWildcard));
			requireThat(excluded, "excluded").containsExactly(List.of(quoted + " install --harness claude",
				quoted + " update --harness claude", quoted + " update --harness claude latest", updateWildcard,
				quoted + " status", quoted + " status --harness claude", quoted + " uninstall --harness claude",
				quoted + " uninstall --harness claude --delete-data"));
			requireThat(added.get(SETTINGS_KEY_ALLOW), "allowed").contains("Bash(" + updateWildcard + ")");
		}
	}

	/**
	 * Installing a new version replaces the entries that name the earlier version folder's launcher with entries that
	 * name the new one: install removes the recorded entries that it no longer maintains, then adds the new ones.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void versionBumpRewritesEntries() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path earlier = launcher(home);
			Path next = new ClaudeInstallation(home).versionFolder(NEW_VERSION).resolve("bin/installer");
			Integration integration = integration();
			Map<String, List<String>> recorded = integration.installPermissions(home, earlier, replacer());

			integration.removePermissions(home, recorded, replacer());
			Map<String, List<String>> added = integration.installPermissions(home, next, replacer());

			requireThat(added, "added").isEqualTo(posixEntries(next));
			String settings = Files.readString(home.resolve("settings.json"));
			requireThat(settings, "settings").doesNotContain(earlier.toString());
			for (List<String> entries : added.values())
			{
				for (String entry : entries)
					requireThat(settings, "settings").contains(json(entry));
			}
		}
	}

	/**
	 * No entry lets an agent run the installer's install into the other harness without the user's approval. An entry
	 * is taken to match a command when the command equals it, or starts with it and a space once a trailing wildcard is
	 * removed; that covers Claude Code's exact, prefix and wildcard matching.
	 *
	 * @param platform the platform, whose launcher form differs
	 * @throws IOException if the launcher path cannot be named in an entry
	 */
	@Test(dataProvider = "platforms")
	public void otherHarnessInstallPrompts(Platform platform) throws IOException
	{
		Path home = absoluteTestPath("claude");
		Path launcher = launcher(home);
		String command = ShellQuoting.posix(launcher.toString()) + " install --harness codex";
		if (platform.isWindows())
		{
			launcher = launcher.resolveSibling("installer.ps1");
			command = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" + launcher +
				"\" install --harness codex";
		}

		Map<String, List<String>> maintained = new Integration(platform).permissions(launcher);

		for (String entry : maintained.get(SETTINGS_KEY_EXCLUDED))
			requireThat(matches(entry, command), "matches").withContext(entry, "entry").isFalse();
		for (String entry : maintained.get(SETTINGS_KEY_ALLOW))
		{
			String rule = entry.substring("Bash(".length(), entry.length() - ")".length());
			requireThat(matches(rule, command), "matches").withContext(entry, "entry").isFalse();
		}
	}

	/**
	 * Returns whether a permission or sandbox exclusion entry could match a command under exact, prefix or trailing
	 * wildcard matching.
	 *
	 * @param entry   the entry, whose trailing {@code " *"} is a wildcard and whose other stars are escaped
	 * @param command the command
	 * @return true if the entry could match the command
	 */
	private static boolean matches(String entry, String command)
	{
		String literal = entry;
		if (literal.endsWith(" *"))
			literal = literal.substring(0, literal.length() - " *".length()).replace("\\*", "*").replace("\\\\", "\\");
		return command.equals(literal) || command.startsWith(literal + " ");
	}

	/**
	 * The sandbox entries deny sandboxed writes to the other harness's CAT folders, found through its own home
	 * variable, and allow writes to the capacity locks, in Claude Code's two-space layout; repeating the call adds
	 * nothing.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void sandboxEntriesProtectOtherHarness() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path userHome = cleanup.add(testDirectory());
			Path home = Files.createDirectories(userHome.resolve(".claude"));
			Path codexHome = userHome.resolve("configurations/codex");
			HarnessInstallation codex = CodexInstallation.fromEnvironment(Map.of("CODEX_HOME", codexHome.toString()),
				userHome);
			Path catLocks = userHome.resolve(".cat/locks");
			Integration integration = integration();

			Map<String, List<String>> added = integration.installSandboxEntries(home, codex, catLocks, replacer());
			Map<String, List<String>> repeated = integration.installSandboxEntries(home, codex, catLocks, replacer());

			List<String> denied = List.of(codexHome.resolve("plugins/cat-marketplace").toString(),
				codexHome.resolve("plugins/cache/cat/cat").toString(), codexHome.resolve("plugins/data/cat-cat").toString());
			Map<String, List<String>> expected = Map.of(SETTINGS_KEY_DENY_WRITE, denied, SETTINGS_KEY_ALLOW_WRITE,
				List.of(catLocks.toString()));
			requireThat(added, "added").isEqualTo(expected);
			requireThat(integration.sandboxEntries(home, codex, catLocks), "maintained").isEqualTo(expected);
			requireThat(repeated, "repeated").isEmpty();
			StringJoiner deniedLines = new StringJoiner(",\n", "", "\n");
			for (String entry : denied)
				deniedLines.add("        " + json(entry));
			requireThat(Files.readString(home.resolve("settings.json")), "settings").isEqualTo("{\n" +
				"  \"sandbox\": {\n" +
				"    \"filesystem\": {\n" +
				"      \"denyWrite\": [\n" + deniedLines +
				"      ],\n" +
				"      \"allowWrite\": [\n" +
				"        " + json(catLocks.toString()) + "\n" +
				"      ]\n" +
				"    }\n" +
				"  }\n" +
				"}\n");
		}
	}

	/**
	 * Sandbox entries keep the user's own entries, do not report an entry that the user already had, and their removal
	 * deletes only what was added, so the user's entry for the capacity locks stays.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void sandboxEntriesRemoveOnlyAdded() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path userHome = cleanup.add(testDirectory());
			Path home = Files.createDirectories(userHome.resolve(".claude"));
			HarnessInstallation codex = CodexInstallation.fromEnvironment(Map.of(), userHome);
			Path catLocks = userHome.resolve(".cat/locks");
			String userSettings = "{\"sandbox\":{\"enabled\":true,\"filesystem\":{\"allowWrite\":[" +
				json(userHome.toString()) + "," + json(catLocks.toString()) + "],\"denyWrite\":[\"/etc\"]}}}";
			Path settings = Files.writeString(home.resolve("settings.json"), userSettings);
			Integration integration = integration();

			Map<String, List<String>> added = integration.installSandboxEntries(home, codex, catLocks, replacer());
			integration.removePermissions(home, added, replacer());

			requireThat(added.keySet(), "addedKeys").isEqualTo(Set.of(SETTINGS_KEY_DENY_WRITE));
			requireThat(Files.readString(settings), "settings").isEqualTo("{\n" +
				"  \"sandbox\": {\n" +
				"    \"enabled\": true,\n" +
				"    \"filesystem\": {\n" +
				"      \"allowWrite\": [\n" +
				"        " + json(userHome.toString()) + ",\n" +
				"        " + json(catLocks.toString()) + "\n" +
				"      ],\n" +
				"      \"denyWrite\": [\n" +
				"        \"/etc\"\n" +
				"      ]\n" +
				"    }\n" +
				"  }\n" +
				"}\n");
		}
	}

	/**
	 * On Windows, every entry names the version folder's PowerShell launcher through PowerShell's fixed
	 * non-interactive form.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void permissionsUsePowerShellOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path launcher = launcher(home).resolveSibling("installer.ps1");
			Integration integration = new Integration(Platform.WINDOWS_X64);

			Map<String, List<String>> added = integration.installPermissions(home, launcher, replacer());

			String prefix = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" + launcher +
				"\"";
			requireThat(added.get(SETTINGS_KEY_EXCLUDED), "excluded").isEqualTo(List.of(
				prefix + " install --harness claude", prefix + " update --harness claude",
				prefix + " update --harness claude latest", pattern(prefix + " update --harness claude"),
				prefix + " status", prefix + " status --harness claude", prefix + " uninstall --harness claude",
				prefix + " uninstall --harness claude --delete-data"));
		}
	}

	/**
	 * Supplies shell-sensitive launcher names; a literal star is supplied only on file systems that accept it.
	 *
	 * @return launcher paths
	 */
	@DataProvider
	public Object[][] launcherNames()
	{
		Path base = Path.of(System.getProperty("cat.installer.test.directory"));
		List<Object[]> cases = new ArrayList<>();
		for (String name : List.of("installer data", "installer $payload;echo hi", "installer's data"))
			cases.add(new Object[] {base.resolve(name + "/bin/installer")});
		try
		{
			cases.add(new Object[] {base.resolve("installer*data/bin/installer")});
		}
		catch (InvalidPathException _)
		{
			// Windows cannot supply a launcher path containing a literal star.
		}
		return cases.toArray(Object[][]::new);
	}

	/**
	 * Entries quote one executable path and keep its literal characters out of the version wildcard.
	 *
	 * @param launcher the absolute installed launcher path
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test(dataProvider = "launcherNames")
	public void permissionsQuoteLauncher(Path launcher) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());

			Map<String, List<String>> added = new Integration(Platform.LINUX_X64).installPermissions(home, launcher,
				replacer());

			requireThat(added, "added").isEqualTo(posixEntries(launcher));
		}
	}

	/**
	 * Permission installation keeps every other member and entry, adds only missing entries, returns only those, and
	 * changes nothing when repeated.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void permissionsMergeIdempotently() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path launcher = launcher(home);
			Map<String, List<String>> expected = posixEntries(launcher);
			String existing = expected.get(SETTINGS_KEY_ALLOW).getFirst();
			Path settings = Files.writeString(home.resolve("settings.json"), "{\"model\":\"opus\"," +
				"\"permissions\":{\"allow\":[\"Bash(ls)\"," + json(existing) + "],\"deny\":[\"Read(.env)\"]}," +
				"\"sandbox\":{\"enabled\":true}}");
			Integration integration = integration();

			Map<String, List<String>> added = integration.installPermissions(home, launcher, replacer());
			String merged = Files.readString(settings);
			FileTime modified = Files.getLastModifiedTime(settings);
			Map<String, List<String>> repeated = integration.installPermissions(home, launcher, replacer());

			List<String> expectedAllowed = new ArrayList<>(expected.get(SETTINGS_KEY_ALLOW));
			expectedAllowed.removeFirst();
			requireThat(added, "added").isEqualTo(Map.of(SETTINGS_KEY_EXCLUDED, expected.get(SETTINGS_KEY_EXCLUDED),
				SETTINGS_KEY_ALLOW, expectedAllowed));
			requireThat(repeated, "repeated").isEmpty();
			requireThat(Files.getLastModifiedTime(settings), "modified").isEqualTo(modified);
			requireThat(merged, "merged").startsWith("{\n  \"model\": \"opus\",\n  \"permissions\": {\n" +
				"    \"allow\": [\n      \"Bash(ls)\",\n      " + json(existing) + ",\n");
			requireThat(merged, "merged").contains("\"deny\": [\n      \"Read(.env)\"\n    ]");
			requireThat(merged, "merged").contains("\"sandbox\": {\n    \"enabled\": true,\n" +
				"    \"excludedCommands\": [\n");
		}
	}

	/**
	 * Permission removal deletes only the recorded entries that are still present, keeping the user's entries,
	 * members and entries that someone else removed.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void removePermissionsKeepsOthers() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path launcher = launcher(home);
			Path settings = Files.writeString(home.resolve("settings.json"),
				"{\"permissions\":{\"allow\":[\"Bash(ls)\"]},\"theme\":\"dark\"}");
			Integration integration = integration();
			Map<String, List<String>> added = integration.installPermissions(home, launcher, replacer());
			String removedByUser = added.get(SETTINGS_KEY_ALLOW).getFirst();
			Files.writeString(settings, Files.readString(settings).replace(json(removedByUser) + ",", ""));

			integration.removePermissions(home, added, replacer());

			requireThat(Files.readString(settings), "settings").isEqualTo("""
				{
				  "permissions": {
				    "allow": [
				      "Bash(ls)"
				    ]
				  },
				  "theme": "dark",
				  "sandbox": {
				    "excludedCommands": []
				  }
				}
				""");
		}
	}

	/**
	 * Permission removal without a settings file changes nothing.
	 *
	 * @throws IOException if the fixture cannot be created or removal fails
	 */
	@Test
	public void removePermissionsAcceptsAbsence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());

			integration().removePermissions(home, Map.of(SETTINGS_KEY_ALLOW, List.of("Bash(ls)")), replacer());

			requireThat(Files.exists(home.resolve("settings.json")), "settingsCreated").isFalse();
		}
	}

	/**
	 * Validation accepts an absent settings file and one whose target members are absent or arrays.
	 *
	 * @throws IOException if the fixture cannot be created or validation fails
	 */
	@Test
	public void validationAcceptsChangeable() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Integration integration = integration();
			integration.validateConfiguration(home);
			Files.writeString(home.resolve("settings.json"),
				"{\"sandbox\":{\"excludedCommands\":[]},\"permissions\":{}}");

			integration.validateConfiguration(home);
		}
	}

	/**
	 * Supplies settings that permission installation cannot change safely.
	 *
	 * @return each settings file's content
	 */
	@DataProvider
	public Object[][] malformedSettings()
	{
		return new Object[][] {{"[]"}, {"\"text\""}, {"{"}, {"{} {}"}, {"{\"sandbox\":[]}"}, {"{\"permissions\":1}"},
			{"{\"sandbox\":{\"excludedCommands\":{}}}"}, {"{\"permissions\":{\"allow\":\"Bash(ls)\"}}"},
			{"{\"permissions\":{},\"permissions\":{}}"}, {"{\"sandbox\":{\"filesystem\":[]}}"},
			{"{\"sandbox\":{\"filesystem\":{\"denyWrite\":{}}}}"}};
	}

	/**
	 * Validation and permission installation refuse settings they cannot change safely, changing nothing.
	 *
	 * @param content the settings file's content
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "malformedSettings")
	public void validationRefusesMalformed(String content) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path settings = Files.writeString(home.resolve("settings.json"), content);
			Integration integration = integration();

			requireRefusal(() -> integration.validateConfiguration(home));
			requireRefusal(() -> integration.installPermissions(home, launcher(home), replacer()));
			requireRefusal(() -> integration.installSandboxEntries(home, new CodexInstallation(home.resolve("codex")),
				home.resolve("locks"), replacer()));
			requireThat(Files.readString(settings), "settings").isEqualTo(content);
		}
	}

	/**
	 * Validation refuses a settings file that is a symbolic link, which could redirect the change elsewhere.
	 *
	 * @throws IOException if the fixture cannot be created or the refusal is returned
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*symbolic link.*")
	public void validationRefusesLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path target = Files.writeString(home.resolve("elsewhere.json"), "{}");
			Files.createSymbolicLink(home.resolve("settings.json"), target);

			integration().validateConfiguration(home);
		}
	}

	/**
	 * Runs an operation that must refuse with an {@link IOException}.
	 *
	 * @param operation the operation
	 */
	private static void requireRefusal(Operation operation)
	{
		try
		{
			operation.run();
		}
		catch (IOException _)
		{
			return;
		}
		throw new AssertionError("The operation accepted settings that it cannot change safely");
	}

	/**
	 * An operation that may fail with an {@link IOException}.
	 */
	@FunctionalInterface
	private interface Operation
	{
		/**
		 * Runs the operation.
		 *
		 * @throws IOException if the operation fails
		 */
		void run() throws IOException;
	}

	/**
	 * Returns the entries that a POSIX launcher receives.
	 *
	 * @param launcher the launcher's absolute path
	 * @return the entries keyed as {@link HarnessIntegration#installPermissions} returns them
	 */
	private static Map<String, List<String>> posixEntries(Path launcher)
	{
		// Claude's wildcard classification ignores shell quotes, so a literal star outside the quoted path is escaped.
		String quoted = ShellQuoting.posix(launcher.toString()).replace("*", "'\\*'");
		String install = quoted + " install --harness claude";
		String update = quoted + " update --harness claude";
		List<String> excluded = List.of(install, update, update + " latest", pattern(update), quoted + " status",
			quoted + " status --harness claude", quoted + " uninstall --harness claude",
			quoted + " uninstall --harness claude --delete-data");
		List<String> allowed = List.of("Bash(" + install + ")", "Bash(" + update + ")",
			"Bash(" + pattern(update) + ")", "Bash(" + quoted + " status)",
			"Bash(" + quoted + " status --harness claude)");
		return Map.of(SETTINGS_KEY_EXCLUDED, excluded, SETTINGS_KEY_ALLOW, allowed);
	}

	/**
	 * Returns the matcher pattern that accepts one exact command followed by a version argument.
	 *
	 * @param command the exact command
	 * @return the pattern, whose escapes keep the command's characters literal
	 */
	private static String pattern(String command)
	{
		return command.replace("\\", "\\\\").replace("*", "\\*") + " *";
	}

	/**
	 * Returns a string as a JSON string literal.
	 *
	 * @param value the string
	 * @return the quoted, escaped literal
	 */
	private static String json(String value)
	{
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/**
	 * Returns a replacer whose test-owned files never need a retry wait.
	 *
	 * @return the replacer
	 */
	private static FileReplacer replacer()
	{
		return new FileReplacer(Platform.current(), () ->
		{
		}, Clock.systemUTC());
	}

	/**
	 * Creates an integration for the current platform.
	 *
	 * @return the integration
	 */
	private static Integration integration()
	{
		return new Integration(Platform.current());
	}

	/**
	 * Writes a plugin version's folder, as install copies it from the plugin archive, with the marketplace metadata
	 * that the archive ships: a {@code cat} entry stamped with the plugin version.
	 *
	 * @param home    the configuration directory
	 * @param version the plugin version
	 * @return the version folder
	 * @throws IOException if the files cannot be written
	 */
	private static Path writeVersionFolder(Path home, PluginVersion version) throws IOException
	{
		Path versionFolder = new ClaudeInstallation(home).versionFolder(version);
		Path metadata = versionFolder.resolve("install/claude-marketplace.json");
		Files.createDirectories(metadata.getParent());
		Files.writeString(metadata, """
			{"name": "cat", "owner": {"name": "cowwoc"}, "plugins": [{"name": "cat", "source": "./plugins/cat",
			"version": "%s"}]}
			""".formatted(version));
		return versionFolder;
	}

	/**
	 * Returns the POSIX installer launcher in a configuration directory's version folder of {@link #VERSION}.
	 *
	 * @param home the configuration directory
	 * @return the launcher's absolute path
	 */
	private static Path launcher(Path home)
	{
		return new ClaudeInstallation(home).versionFolder(VERSION).resolve("bin/installer");
	}

	/**
	 * Returns an absolute path beneath this module's test directory without creating it.
	 *
	 * @param relative the path relative to the test directory
	 * @return the absolute, normalized path
	 */
	private static Path absoluteTestPath(String relative)
	{
		return Path.of(System.getProperty("cat.installer.test.directory")).toAbsolutePath().resolve(relative).
			normalize();
	}

	/**
	 * Creates a test-owned directory beneath this module's build directory.
	 *
	 * @return the new directory, registered by the caller with its cleanup scope
	 * @throws IOException if the directory cannot be created
	 */
	private static Path testDirectory() throws IOException
	{
		Path base = Path.of(System.getProperty("cat.installer.test.directory"));
		Files.createDirectories(base);
		return Files.createTempDirectory(base, "integration-");
	}
}
