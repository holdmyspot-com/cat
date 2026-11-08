package com.holdmyspot.cat.claude.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.installer.Integration;
import com.holdmyspot.cat.claude.installer.PluginCommand;
import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.engine.InstallerState;
import com.holdmyspot.cat.common.installer.engine.PermissionRecord;
import com.holdmyspot.cat.common.installer.engine.PluginInstall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.StringJoiner;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
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
	 * The installer's report to a person names the harness by the name that its users know.
	 */
	@Test
	public void namesClaudeCode()
	{
		requireThat(new Integration(Platform.LINUX_X64).productName(), "productName").isEqualTo("Claude Code");
	}

	/**
	 * The integration names the variable in which Claude Code gives the commands that its agent runs the session's
	 * identifier, which tells the installer that an agent reads its result.
	 */
	@Test
	public void namesAgentSessionVariable()
	{
		requireThat(new Integration(Platform.LINUX_X64).agentSessionVariable(), "agentSessionVariable").
			isEqualTo("CLAUDE_CODE_SESSION_ID");
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
	 * The marketplace manifest names the folder of the version that registration is asked for as CAT's source, although
	 * another version's folder is also present, so that Claude Code loads the plugin in place from the folder of the
	 * version that was registered. Claude Code 2.1.318 loads a directory marketplace's plugin in place from the folder
	 * that the manifest's source names.
	 *
	 * @param other      the version whose folder is also present
	 * @param registered the version to register
	 * @throws IOException if the fixture cannot be created or the manifest cannot be written
	 */
	@Test(dataProvider = "buildPairs")
	public void manifestNamesRegisteredVersionFolder(PluginVersion other, PluginVersion registered) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			writeVersionFolder(home, other);
			Path registeredFolder = writeVersionFolder(home, registered);

			Integration.writeMarketplaceManifest(home, registered, replacer());

			requireThat(manifestSource(home), "source").isEqualTo(registeredFolder);
		}
	}

	/**
	 * Supplies two builds of one release, in both orders: the build whose folder is also present and the build that
	 * registration names.
	 *
	 * @return the other and the registered version
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
	 * Registration refuses a version folder that lacks the marketplace metadata which the manifest is written from.
	 *
	 * @throws IOException if the fixture cannot be created or the failure is returned
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "The plugin version folder lacks its marketplace metadata: .*")
	public void manifestRequiresMetadata() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());

			Integration.writeMarketplaceManifest(home, VERSION, replacer());
		}
	}

	/**
	 * Registration replaces an outdated marketplace manifest although the file system refuses the first attempt, as
	 * Windows does while another process reads the file.
	 *
	 * @throws IOException if the fixture cannot be created or the manifest cannot be written
	 */
	@Test
	public void manifestRetriesRefusedReplacement() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path versionFolder = writeVersionFolder(home, VERSION);
			Path manifest = Files.createDirectories(new ClaudeInstallation(home).marketplaceRoot().
				resolve(".claude-plugin")).resolve("marketplace.json");
			Files.writeString(manifest, "{\"name\": \"outdated\"}\n");
			TransientRefusal refusal = new TransientRefusal(manifest);

			Integration.writeMarketplaceManifest(home, VERSION, refusal.replacer());

			requireThat(manifestSource(home), "source").isEqualTo(versionFolder);
			requireThat(refusal.pauses(), "pauses").isEqualTo(1);
		}
	}

	/**
	 * Registration adds CAT's marketplace, the directory that holds the manifest, to Claude Code.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void addsMarketplaceFromItsRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path root = new ClaudeInstallation(home).marketplaceRoot();

			requireThat(PluginCommand.addMarketplace(root).arguments(), "arguments").
				isEqualTo(List.of("plugin", "marketplace", "add", root.toString()));
		}
	}

	/**
	 * A first registration installs CAT, because Claude Code records no CAT installation.
	 *
	 * @throws IOException if the fixture cannot be created or the command cannot be chosen
	 */
	@Test
	public void firstRegistrationInstalls() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());

			requireThat(Integration.registrationCommand(home).arguments(), "arguments").
				isEqualTo(List.of("plugin", "install", "cat@cat"));
		}
	}

	/**
	 * Registering a new version of an installed CAT updates CAT, because Claude Code's install leaves an installed
	 * plugin unchanged.
	 *
	 * @throws IOException if the fixture cannot be created or the command cannot be chosen
	 */
	@Test
	public void laterRegistrationUpdates() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			InstalledPluginRecord.write(home, VERSION.toString());

			requireThat(Integration.registrationCommand(home).arguments(), "arguments").
				isEqualTo(List.of("plugin", "update", "cat@cat"));
		}
	}

	/**
	 * Registration succeeds when Claude Code records the registered version as CAT's installed version.
	 *
	 * @throws IOException if the fixture cannot be created or the check fails
	 */
	@Test
	public void acceptsSwitchedVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			InstalledPluginRecord.write(home, NEW_VERSION.toString());

			Integration.requireSwitched(home, NEW_VERSION, PluginCommand.registerPlugin(true));
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
	public void refusesUnswitchedVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			InstalledPluginRecord.write(home, VERSION.toString());

			Integration.requireSwitched(home, NEW_VERSION, PluginCommand.registerPlugin(true));
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

			requireThat(added, "added").isEqualTo(platformEntries(launcher));
			requireThat(Files.readString(settings), "settings").contains("excludedCommands");
			requireThat(adding.pauses(), "addingPauses").isEqualTo(1);
			TransientRefusal removing = new TransientRefusal(settings);

			integration().removePermissions(home, added, removing.replacer());

			requireThat(Files.readString(settings), "settings").doesNotContain(launcher.toString());
			requireThat(removing.pauses(), "removingPauses").isEqualTo(1);
		}
	}

	/**
	 * Registration accepts Claude Code's answers to its commands when they succeed. The answers are those that Claude
	 * Code 2.1.318 printed.
	 *
	 * @param command the registration command
	 * @param answer  Claude Code's answer, with exit status 0
	 * @throws IOException if the answer is refused
	 */
	@Test(dataProvider = "registrationSuccesses")
	public void acceptsRegistrationSuccess(PluginCommand command, String answer) throws IOException
	{
		command.requireAccepted(new CommandResult(0, answer));
	}

	/**
	 * Supplies each registration command with the answer that Claude Code 2.1.318 printed when it succeeded.
	 *
	 * @return each command and its answer
	 */
	@DataProvider
	public Object[][] registrationSuccesses()
	{
		Path root = absoluteTestPath("marketplace");
		return new Object[][]
		{
			{PluginCommand.addMarketplace(root), "✔ Successfully added marketplace: cat (declared in user settings)\n"},
			{PluginCommand.registerPlugin(false), "✔ Successfully installed plugin: cat@cat (scope: user)\n"},
			{PluginCommand.registerPlugin(true), "✔ Plugin \"cat\" updated. Restart to apply changes.\n"}
		};
	}

	/**
	 * Registration fails, naming Claude Code's answer, when a command fails.
	 *
	 * @param command the registration command that Claude Code refuses
	 * @throws IOException if the failure is returned
	 */
	@Test(dataProvider = "registrationCommands", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*network unavailable.*")
	public void registrationFailsOnRefusal(PluginCommand command) throws IOException
	{
		command.requireAccepted(new CommandResult(1, "✘ network unavailable\n"));
	}

	/**
	 * Supplies the registration commands that can fail.
	 *
	 * @return each command
	 */
	@DataProvider
	public Object[][] registrationCommands()
	{
		return new Object[][]
		{
			{PluginCommand.addMarketplace(absoluteTestPath("marketplace"))},
			{PluginCommand.registerPlugin(false)},
			{PluginCommand.registerPlugin(true)}
		};
	}

	/**
	 * Unregistration uninstalls CAT and then removes its marketplace, treating Claude Code's not-found answers as
	 * success.
	 *
	 * @throws IOException if an answer is refused
	 */
	@Test
	public void unregisterAcceptsAbsence() throws IOException
	{
		List<PluginCommand> commands = PluginCommand.unregistration(false);

		requireThat(commands.stream().map(PluginCommand::arguments).toList(), "arguments").isEqualTo(List.of(
			List.of("plugin", "uninstall", "cat@cat"), List.of("plugin", "marketplace", "remove", "cat")));
		commands.get(0).requireAccepted(new CommandResult(1, PLUGIN_NOT_FOUND));
		commands.get(1).requireAccepted(new CommandResult(1, MARKETPLACE_NOT_FOUND));
	}

	/**
	 * Unregistration that keeps the data asks Claude Code to keep the plugin's data directory, which its uninstall
	 * deletes otherwise.
	 */
	@Test
	public void unregisterKeepsData()
	{
		requireThat(PluginCommand.unregistration(true).stream().map(PluginCommand::arguments).toList(), "arguments").
			isEqualTo(List.of(List.of("plugin", "uninstall", "--keep-data", "cat@cat"),
				List.of("plugin", "marketplace", "remove", "cat")));
	}

	/**
	 * Unregistration fails on any other refusal, so uninstall does not report a registration it left behind.
	 *
	 * @param index the position of the refused command among the unregistration commands
	 * @throws IOException if the failure is returned
	 */
	@Test(dataProvider = "unregistrationCommands", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*settings are locked.*")
	public void unregisterFailsOnRefusal(int index) throws IOException
	{
		PluginCommand.unregistration(false).get(index).requireAccepted(new CommandResult(1, "✘ settings are locked"));
	}

	/**
	 * Supplies the position of each unregistration command.
	 *
	 * @return the uninstall command's and the marketplace removal's position
	 */
	@DataProvider
	public Object[][] unregistrationCommands()
	{
		return new Object[][] {{0}, {1}};
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
			Map<String, List<String>> expected = platformEntries(launcher);

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
	 * Uninstall is never allowed without a prompt; only status and install into Claude Code, of the latest or a named
	 * release, are. Every operation runs outside the sandbox, and neither setup nor update is named.
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

			String quoted = launcherCommand(launcher);
			List<String> allowed = added.get(SETTINGS_KEY_ALLOW);
			requireThat(allowed, "allowed").isEqualTo(platformEntries(launcher).get(SETTINGS_KEY_ALLOW));
			requireThat(allowed.subList(0, 4), "installerAllowed").isEqualTo(List.of(
				"Bash(" + quoted + " install --harness claude)",
				"Bash(" + pattern(quoted + " install --harness claude") + ")",
				"Bash(" + quoted + " status)",
				"Bash(" + quoted + " status --harness claude)"));
			requireThat(added.get(SETTINGS_KEY_EXCLUDED), "excluded").containsAll(List.of(
				quoted + " uninstall --harness claude", quoted + " uninstall --harness claude --delete-data"));
			for (List<String> entries : added.values())
			{
				for (String entry : entries)
					requireThat(entry, "entry").doesNotContain("setup").doesNotContain(" update ");
			}
		}
	}

	/**
	 * The only installer exclusion with a wildcard is the one for {@code install --harness claude} followed by a
	 * release argument, so that an install of a named release runs outside the sandbox without a prompt. Claude Code
	 * 2.1.277 and later keep a call in the sandbox unless every command in it matches an exclusion; earlier versions
	 * ran the whole call outside it when one command matched a wildcard exclusion, so CAT requires 2.1.277 or later.
	 * Every other installer exclusion is one exact command, because no other operation takes a release. Except on
	 * Windows, the request server beside the installer runs outside the sandbox and without a prompt as exactly its
	 * absolute path, and no runner launcher does.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void onlyInstallExclusionHasWildcard() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path launcher = launcher(home);

			Map<String, List<String>> added = integration().installPermissions(home, launcher, replacer());

			String quoted = launcherCommand(launcher);
			String installWildcard = pattern(quoted + " install --harness claude");
			List<String> excluded = added.get(SETTINGS_KEY_EXCLUDED);
			List<String> servers = List.of();
			if (!Platform.current().isWindows())
				servers = serverEntries(launcher);
			requireThat(excluded.stream().filter(entry -> entry.contains("*")).toList(), "wildcards").
				isEqualTo(List.of(installWildcard));
			List<String> expected = new ArrayList<>(List.of(quoted + " install --harness claude",
				quoted + " install --harness claude latest", installWildcard, quoted + " status",
				quoted + " status --harness claude", quoted + " uninstall --harness claude",
				quoted + " uninstall --harness claude --delete-data"));
			expected.addAll(servers);
			requireThat(excluded, "excluded").containsExactly(expected);
			requireThat(added.get(SETTINGS_KEY_ALLOW), "allowed").contains("Bash(" + installWildcard + ")");
			for (String server : servers)
				requireThat(added.get(SETTINGS_KEY_ALLOW), "allowed").contains("Bash(" + server + ")");
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
			Path next = launcher(home, NEW_VERSION);
			Integration integration = integration();
			Map<String, List<String>> recorded = integration.installPermissions(home, earlier, replacer());

			integration.removePermissions(home, recorded, replacer());
			Map<String, List<String>> added = integration.installPermissions(home, next, replacer());

			requireThat(added, "added").isEqualTo(platformEntries(next));
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
	 * No permission entry lets an agent run, without asking, an install that gives the user's permission to create a
	 * permission profile or names another harness home, because those options come before the operation.
	 *
	 * @param platform the platform, whose launcher form differs
	 * @throws IOException if the launcher path cannot be named in an entry
	 */
	@Test(dataProvider = "platforms")
	public void consentOptionsPrompt(Platform platform) throws IOException
	{
		Path home = absoluteTestPath("claude");
		Path launcher = launcher(home);
		String prefix = ShellQuoting.posix(launcher.toString());
		if (platform.isWindows())
		{
			launcher = launcher.resolveSibling("installer.ps1");
			prefix = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" + launcher + "\"";
		}
		String other = ShellQuoting.posix(home.resolveSibling("other-home").toString());

		Map<String, List<String>> maintained = new Integration(platform).permissions(launcher);

		for (String options : List.of("--create-profile", "--home " + other, "--home " + other + " --create-profile"))
		{
			for (String release : List.of("", " v1.2"))
			{
				String command = prefix + " " + options + " install --harness claude" + release;
				for (String entry : maintained.get(SETTINGS_KEY_ALLOW))
				{
					String rule = entry.substring("Bash(".length(), entry.length() - ")".length());
					requireThat(matches(rule, command), "matches").withContext(entry, "entry").
						withContext(command, "command").isFalse();
				}
			}
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
		// Requirement: R-I11
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
				codexHome.resolve("plugins/cache/cat/cat").toString(),
				codexHome.resolve("plugins/data/cat-cat").toString());
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
	 * A reinstall after CAT granted sandboxed writes to another capacity locks folder, as an earlier installer did
	 * from a wrong user home, leaves the settings granting only the new folder, keeps the user's own entry, and
	 * records the new grant.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void reinstallReplacesEarlierLocksGrant() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path userHome = cleanup.add(testDirectory());
			Path home = Files.createDirectories(userHome.resolve(".claude"));
			HarnessInstallation claude = new ClaudeInstallation(home);
			HarnessInstallation codex = CodexInstallation.fromEnvironment(Map.of(), userHome);
			Path wrongLocks = userHome.resolve("account/.cat/locks");
			Path catLocks = userHome.resolve(".cat/locks");
			Path userEntry = userHome.resolve("shared");
			Files.writeString(home.resolve("settings.json"), "{\"sandbox\":{\"filesystem\":{\"allowWrite\":[" +
				json(userEntry.toString()) + "]}}}");
			PluginInstall install = new PluginInstall(integration(), ClaudeProgram.cli(home), replacer());
			install.installEntries(home, launcher(home), claude, List.of(codex), wrongLocks,
				userHome.resolve("account/.cat/sessions"), List.of(), false);
			requireThat(Files.readString(home.resolve("settings.json")), "granted").contains(json(wrongLocks.toString()));

			PluginInstall.InstalledEntries entries = install.installEntries(home, launcher(home), claude,
				List.of(codex), catLocks, userHome.resolve(".cat/sessions"), List.of(), false);

			requireThat(Files.readString(home.resolve("settings.json")), "settings").
				contains(json(userEntry.toString())).contains(json(catLocks.toString())).
				doesNotContain(json(wrongLocks.toString()));
			requireThat(entries.recorded().get(SETTINGS_KEY_ALLOW_WRITE), "recorded").
				isEqualTo(List.of(catLocks.toString()));
		}
	}

	/**
	 * A reinstall after an earlier install denied sandboxed writes to the session launcher's state folder removes that
	 * recorded denial and the folder, and keeps the user's own denial.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void reinstallRemovesLauncherState() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path userHome = cleanup.add(testDirectory());
			Path home = Files.createDirectories(userHome.resolve(".claude"));
			HarnessInstallation claude = new ClaudeInstallation(home);
			HarnessInstallation codex = CodexInstallation.fromEnvironment(Map.of(), userHome);
			Path launcherState = PluginInstall.formerLauncherState(userHome);
			Files.createDirectories(launcherState.resolve("claude/launchers/1234"));
			Files.writeString(launcherState.resolve("claude/launchers/1234/heartbeat"), "1");
			Path userEntry = userHome.resolve("private");
			Files.writeString(home.resolve("settings.json"), "{\"sandbox\":{\"filesystem\":{\"denyWrite\":[" +
				json(userEntry.toString()) + "," + json(launcherState.toString()) + "]}}}");
			PermissionRecord.add(InstallerState.of(claude.dataDirectory(), "claude").permissions(),
				Map.of(SETTINGS_KEY_DENY_WRITE, List.of(launcherState.toString())));
			PluginInstall install = new PluginInstall(integration(), ClaudeProgram.cli(home), replacer());

			PluginInstall.InstalledEntries entries = install.installEntries(home, launcher(home), claude,
				List.of(codex), userHome.resolve(".cat/locks"), launcherState, List.of(), false);

			requireThat(Files.readString(home.resolve("settings.json")), "settings").
				contains(json(userEntry.toString())).doesNotContain(json(launcherState.toString()));
			requireThat(entries.recorded().get(SETTINGS_KEY_DENY_WRITE), "recorded").
				doesNotContain(launcherState.toString()).doesNotContain(userEntry.toString());
			requireThat(Files.exists(launcherState, LinkOption.NOFOLLOW_LINKS), "launcherStateExists").isFalse();
			requireThat(Files.isDirectory(launcherState.getParent()), "catFolderExists").isTrue();
		}
	}

	/**
	 * A reinstall after an earlier install let the runner launchers run outside the sandbox removes those recorded
	 * entries, adds the request server's, and keeps the user's own entry.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void reinstallRemovesRecordedRunnerEntries() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path userHome = cleanup.add(testDirectory());
			Path home = Files.createDirectories(userHome.resolve(".claude"));
			HarnessInstallation claude = new ClaudeInstallation(home);
			HarnessInstallation codex = CodexInstallation.fromEnvironment(Map.of(), userHome);
			Path launcher = claude.versionFolder(VERSION).resolve("bin/installer");
			List<String> runners = List.of(pattern(posixCommand(launcher.resolveSibling("claude-runner"))),
				pattern(posixCommand(launcher.resolveSibling("codex-runner"))));
			List<String> allowedRunners = runners.stream().map(runner -> "Bash(" + runner + ")").toList();
			List<String> allowed = new ArrayList<>(allowedRunners);
			allowed.add("Bash(ls)");
			JsonMapper mapper = JsonMapper.builder().build();
			Files.writeString(home.resolve("settings.json"), mapper.writeValueAsString(Map.of(
				"sandbox", Map.of("excludedCommands", runners), "permissions", Map.of("allow", allowed))));
			PermissionRecord.add(InstallerState.of(claude.dataDirectory(), "claude").permissions(),
				Map.of(SETTINGS_KEY_EXCLUDED, runners, SETTINGS_KEY_ALLOW, allowedRunners));
			PluginInstall install = new PluginInstall(new Integration(Platform.LINUX_X64), ClaudeProgram.cli(home),
				replacer());

			PluginInstall.InstalledEntries entries = install.installEntries(home, launcher, claude, List.of(codex),
				userHome.resolve(".cat/locks"), userHome.resolve(".cat/sessions"), List.of(), false);

			String settings = Files.readString(home.resolve("settings.json"));
			for (String runner : runners)
				requireThat(settings, "settings").doesNotContain(json(runner));
			String server = posixCommand(launcher.resolveSibling("request-server"));
			requireThat(settings, "settings").contains(json(server)).contains(json("Bash(" + server + ")")).
				contains(json("Bash(ls)"));
			requireThat(entries.recorded().get(SETTINGS_KEY_EXCLUDED), "recordedExcluded").contains(server).
				doesNotContainAny(runners);
			requireThat(entries.recorded().get(SETTINGS_KEY_ALLOW), "recordedAllowed").
				contains("Bash(" + server + ")").doesNotContainAny(allowedRunners).doesNotContain("Bash(ls)");
		}
	}

	/**
	 * The read-only entries deny sandboxed commands and Claude Code's file tools writing each file, are reported as
	 * paths that the sandbox may not write, keep a user's own entry of the same setting, and are removed completely;
	 * repeating the call adds nothing.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void readOnlyEntriesDenyWritingFiles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path userHome = cleanup.add(testDirectory());
			Path home = Files.createDirectories(userHome.resolve(".claude"));
			Path settings = Files.writeString(home.resolve("settings.json"),
				"{\"permissions\":{\"deny\":[\"Bash(rm *)\"]}}\n");
			List<Path> files = List.of(userHome.resolve(".bashrc"), userHome.resolve(".zshenv"));
			Integration integration = new Integration(Platform.LINUX_X64);

			Map<String, List<String>> added = integration.installReadOnlyEntries(home, files, replacer());

			Map<String, List<String>> expected = Map.of(
				SETTINGS_KEY_DENY_WRITE, List.of(files.get(0).toString(), files.get(1).toString()),
				"settings.json#permissions.deny", List.of("Edit(/" + files.get(0) + ")", "Edit(/" + files.get(1) + ")"));
			requireThat(added, "added").isEqualTo(expected);
			requireThat(integration.readOnlyEntries(home, files), "maintained").isEqualTo(expected);
			requireThat(integration.installReadOnlyEntries(home, files, replacer()), "repeated").isEmpty();
			requireThat(MAPPER.readTree(Files.readString(settings)).path("permissions").path("deny").toString(),
				"denied").isEqualTo(MAPPER.writeValueAsString(List.of("Bash(rm *)", "Edit(/" + files.get(0) + ")",
					"Edit(/" + files.get(1) + ")")));
			List<EntryGroup> groups = integration.describeEntries(added, launcher(home));
			requireThat(groups, "groups").containsExactly(List.of(
				new EntryGroup(EntryEffect.SANDBOX_MAY_NOT_WRITE, Path.of("settings.json"), "sandbox.filesystem.denyWrite",
					expected.get(SETTINGS_KEY_DENY_WRITE), expected.get(SETTINGS_KEY_DENY_WRITE)),
				new EntryGroup(EntryEffect.SANDBOX_MAY_NOT_WRITE, Path.of("settings.json"), "permissions.deny",
					expected.get(SETTINGS_KEY_DENY_WRITE), expected.get("settings.json#permissions.deny"))));

			integration.removePermissions(home, added, replacer());

			requireThat(Files.readString(settings), "settings").isEqualTo("""
				{
				  "permissions": {
				    "deny": [
				      "Bash(rm *)"
				    ]
				  },
				  "sandbox": {
				    "filesystem": {
				      "denyWrite": []
				    }
				  }
				}
				""");
		}
	}

	/**
	 * Claude Code has no sandbox on Windows, so no command runs outside one there and no file needs to be
	 * read-only.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void readOnlyEntriesAbsentOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path userHome = cleanup.add(testDirectory());
			Path home = Files.createDirectories(userHome.resolve(".claude"));
			Integration integration = new Integration(Platform.WINDOWS_X64);
			List<Path> files = List.of(userHome.resolve(".bashrc"));

			requireThat(integration.installReadOnlyEntries(home, files, replacer()), "added").isEmpty();
			requireThat(integration.readOnlyEntries(home, files), "maintained").isEmpty();
			requireThat(Files.exists(home.resolve("settings.json")), "settingsCreated").isFalse();
		}
	}

	/**
	 * The report names each installer operation and the request server that the recorded entries let Claude Code run
	 * outside its sandbox and without asking.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void reportNamesRequestServer() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path launcher = home.resolve("plugins/cache/cat/cat/1.0/bin/installer");
			Integration integration = new Integration(Platform.LINUX_X64);
			Map<String, List<String>> added = integration.installPermissions(home, launcher, replacer());

			List<EntryGroup> groups = integration.describeEntries(added, launcher);

			Map<String, List<String>> items = new HashMap<>();
			for (EntryGroup group : groups)
				items.put(group.setting(), group.items());
			requireThat(items, "items").isEqualTo(Map.of(
				"sandbox.excludedCommands", List.of("installer install", "installer install", "installer install",
					"installer status", "installer status", "installer uninstall", "installer uninstall", "request-server"),
				"permissions.allow", List.of("installer install", "installer install", "installer status",
					"installer status", "request-server")));
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
				prefix + " install --harness claude", prefix + " install --harness claude latest",
				pattern(prefix + " install --harness claude"), prefix + " status", prefix + " status --harness claude",
				prefix + " uninstall --harness claude", prefix + " uninstall --harness claude --delete-data"));
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
			Map<String, List<String>> expected = platformEntries(launcher);
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
	 * Install leaves the request server entries that the user added before it, which equal its own, unrecorded, so
	 * uninstall leaves them, as it leaves every entry that CAT did not record, and says nothing about them.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void uninstallKeepsUserServerExceptions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			String server = new ClaudeInstallation(home).versionFolder(VERSION).resolve("bin/request-server").
				toString();
			Path settings = Files.writeString(home.resolve("settings.json"), "{\"sandbox\":{\"excludedCommands\":[" +
				json(server) + "]},\"permissions\":{\"allow\":[" + json("Bash(" + server + ")") + "]}}");
			Integration integration = integration();
			Map<String, List<String>> added = integration.installPermissions(home, launcher(home), replacer());

			integration.removePermissions(home, added, replacer());
			List<String> retained = integration.describeRetained(home, added);

			requireThat(Files.readString(settings), "settings").isEqualTo("""
				{
				  "sandbox": {
				    "excludedCommands": [
				      %s
				    ]
				  },
				  "permissions": {
				    "allow": [
				      %s
				    ]
				  }
				}
				""".formatted(json(server), json("Bash(" + server + ")")));
			requireThat(retained, "retained").isEmpty();
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
		return entries(posixCommand(launcher), serverEntries(launcher));
	}

	/**
	 * Returns the entries that the launcher of the platform that runs the test receives.
	 *
	 * @param launcher the launcher's absolute path
	 * @return the entries keyed as {@link HarnessIntegration#installPermissions} returns them
	 */
	private static Map<String, List<String>> platformEntries(Path launcher)
	{
		if (Platform.current().isWindows())
			return entries(launcherCommand(launcher), List.of());
		return entries(launcherCommand(launcher), serverEntries(launcher));
	}

	/**
	 * Returns the entries that let the request server beside a POSIX installer launcher run.
	 *
	 * @param launcher the installer launcher's absolute path
	 * @return the server's entry, without {@code Bash(...)}
	 */
	private static List<String> serverEntries(Path launcher)
	{
		return List.of(posixCommand(launcher.resolveSibling("request-server")));
	}

	/**
	 * Returns the entries for a launcher command.
	 *
	 * @param command the shell text that runs the launcher
	 * @param servers the entries of the request server beside it, without {@code Bash(...)}
	 * @return the entries keyed as {@link HarnessIntegration#installPermissions} returns them
	 */
	private static Map<String, List<String>> entries(String command, List<String> servers)
	{
		String install = command + " install --harness claude";
		List<String> excluded = new ArrayList<>(List.of(install, install + " latest", pattern(install),
			command + " status", command + " status --harness claude", command + " uninstall --harness claude",
			command + " uninstall --harness claude --delete-data"));
		excluded.addAll(servers);
		List<String> allowed = new ArrayList<>(List.of("Bash(" + install + ")", "Bash(" + pattern(install) + ")",
			"Bash(" + command + " status)", "Bash(" + command + " status --harness claude)"));
		for (String server : servers)
			allowed.add("Bash(" + server + ")");
		return Map.of(SETTINGS_KEY_EXCLUDED, List.copyOf(excluded), SETTINGS_KEY_ALLOW, List.copyOf(allowed));
	}

	/**
	 * Returns the shell text that runs a POSIX launcher.
	 *
	 * @param launcher the launcher's absolute path
	 * @return the quoted path
	 */
	private static String posixCommand(Path launcher)
	{
		// Claude's wildcard classification ignores shell quotes, so a literal star outside the quoted path is escaped.
		return ShellQuoting.posix(launcher.toString()).replace("*", "'\\*'");
	}

	/**
	 * Returns the shell text that runs a launcher on the platform that runs the test.
	 *
	 * @param launcher the launcher's absolute path
	 * @return on Windows, PowerShell's fixed non-interactive form for the launcher script; elsewhere, the quoted path
	 */
	private static String launcherCommand(Path launcher)
	{
		if (Platform.current().isWindows())
			return "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" + launcher + "\"";
		return posixCommand(launcher);
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
	 * Returns the folder that the marketplace manifest names as CAT's source.
	 *
	 * @param home the configuration directory
	 * @return the absolute folder: the manifest's source for the {@code cat} plugin, resolved against the marketplace
	 * 	directory
	 * @throws IOException if the manifest cannot be read or has no {@code cat} plugin
	 */
	private static Path manifestSource(Path home) throws IOException
	{
		ClaudeInstallation installation = new ClaudeInstallation(home);
		JsonNode manifest = MAPPER.readTree(Files.readString(installation.marketplaceManifest()));
		for (JsonNode plugin : manifest.path("plugins"))
		{
			if (plugin.path("name").asString().equals("cat"))
				return installation.marketplaceRoot().resolve(plugin.path("source").asString()).normalize();
		}
		throw new AssertionError("The marketplace manifest has no cat plugin: " + manifest);
	}

	/**
	 * Returns the installer launcher in a configuration directory's version folder of {@link #VERSION}, as the
	 * platform that runs the test names it.
	 *
	 * @param home the configuration directory
	 * @return the launcher's absolute path
	 */
	private static Path launcher(Path home)
	{
		return launcher(home, VERSION);
	}

	/**
	 * Returns the installer launcher in a configuration directory's version folder, as the platform that runs the
	 * test names it: {@code bin/installer.ps1} on Windows and {@code bin/installer} elsewhere.
	 *
	 * @param home    the configuration directory
	 * @param version the plugin version
	 * @return the launcher's absolute path
	 */
	private static Path launcher(Path home, PluginVersion version)
	{
		String name = "bin/installer";
		if (Platform.current().isWindows())
			name += ".ps1";
		return new ClaudeInstallation(home).versionFolder(version).resolve(name);
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
