package com.holdmyspot.cat.claude.updater.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.updater.Integration;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.activation.CommandResult;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
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
	 * Registration writes the release's marketplace manifest, adds the marketplace and then installs CAT from it.
	 *
	 * @throws IOException if the fixture cannot be created or registration fails
	 */
	@Test
	public void registerAddsMarketplaceThenInstalls() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path root = activatedMarketplace(home);
			// Claude Code 2.1.318's answers when the marketplace and plugin were already registered.
			RecordingCli cli = new RecordingCli(List.of(
				new CommandResult(0, "Adding marketplace…✔ Marketplace 'cat' already on disk — declared in user " +
					"settings\n"),
				new CommandResult(0, "Installing plugin \"cat@cat\"...✔ Plugin \"cat@cat\" is already installed " +
					"(scope: user)\n")));

			integration().register(home, cli, replacer());

			requireThat(cli.calls(), "calls").isEqualTo(List.of(
				List.of("plugin", "marketplace", "add", root.toString()),
				List.of("plugin", "install", "cat@cat")));
			requireThat(Files.readString(root.resolve(".claude-plugin/marketplace.json")), "marketplaceManifest").
				isEqualTo(Files.readString(root.resolve("plugins/cat/install/claude-marketplace.json")));
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
			Path root = activatedMarketplace(home);
			Path manifest = Files.createDirectories(root.resolve(".claude-plugin")).resolve("marketplace.json");
			Files.writeString(manifest, "{\"name\": \"outdated\"}\n");
			TransientRefusal refusal = new TransientRefusal(manifest);

			integration().register(home, new RecordingCli(List.of(new CommandResult(0, "ok"),
				new CommandResult(0, "ok"))), refusal.replacer());

			requireThat(Files.readString(manifest), "marketplaceManifest").
				isEqualTo(Files.readString(root.resolve("plugins/cat/install/claude-marketplace.json")));
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
			Path launcher = home.resolve("data/bin/cat-updater");
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
	 * Registration fails, naming Claude Code's answer, when either command fails.
	 *
	 * @param failingCall the zero-based call that fails
	 * @throws IOException if the fixture cannot be created or the failure is returned
	 */
	@Test(dataProvider = "registrationCalls", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*network unavailable.*")
	public void registerFailsOnRefusal(int failingCall) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			activatedMarketplace(home);
			List<CommandResult> answers = new ArrayList<>(List.of(new CommandResult(0, "ok"),
				new CommandResult(0, "ok")));
			answers.set(failingCall, new CommandResult(1, "✘ network unavailable"));

			integration().register(home, new RecordingCli(answers), replacer());
		}
	}

	/**
	 * Supplies the registration commands that can fail.
	 *
	 * @return each command's position
	 */
	@DataProvider
	public Object[][] registrationCalls()
	{
		return new Object[][] {{0}, {1}};
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

			integration().unregister(home, cli);

			requireThat(cli.calls(), "calls").isEqualTo(List.of(List.of("plugin", "uninstall", "cat@cat"),
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

			integration().unregister(home, new RecordingCli(answers));
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
			Path launcher = home.resolve("data/bin/cat-updater");
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
	 * Rollback and uninstall are never allowed without a prompt; only status, install and update are. Every operation
	 * runs outside the sandbox, and setup is never named.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void permissionsPromptForRollback() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path launcher = home.resolve("data/bin/cat-updater");

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
				quoted + " rollback --harness claude", quoted + " uninstall --harness claude",
				quoted + " uninstall --harness claude --delete-data"));
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
			Path launcher = home.resolve("data/bin/cat-updater");

			Map<String, List<String>> added = integration().installPermissions(home, launcher, replacer());

			String quoted = ShellQuoting.posix(launcher.toString());
			String updateWildcard = pattern(quoted + " update --harness claude");
			List<String> excluded = added.get(SETTINGS_KEY_EXCLUDED);
			List<String> wildcards = excluded.stream().filter(entry -> entry.contains("*")).toList();
			requireThat(wildcards, "wildcards").isEqualTo(List.of(updateWildcard));
			requireThat(excluded, "excluded").containsExactly(List.of(quoted + " install --harness claude",
				quoted + " update --harness claude", quoted + " update --harness claude latest", updateWildcard,
				quoted + " status", quoted + " status --harness claude", quoted + " rollback --harness claude",
				quoted + " uninstall --harness claude", quoted + " uninstall --harness claude --delete-data"));
			requireThat(added.get(SETTINGS_KEY_ALLOW), "allowed").contains("Bash(" + updateWildcard + ")");
		}
	}

	/**
	 * Permission installation on an installation whose settings hold the earlier exact-only exclusions adds the
	 * named-release update exclusion and returns only that entry, so that the permission record gains it.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void permissionsUpgradeEarlierEntries() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path launcher = home.resolve("data/bin/cat-updater");
			Map<String, List<String>> expected = posixEntries(launcher);
			String quoted = ShellQuoting.posix(launcher.toString());
			String updateWildcard = pattern(quoted + " update --harness claude");
			StringJoiner earlierExcluded = new StringJoiner(",");
			for (String entry : expected.get(SETTINGS_KEY_EXCLUDED))
			{
				if (!entry.equals(updateWildcard))
					earlierExcluded.add(json(entry));
			}
			StringJoiner allowed = new StringJoiner(",");
			for (String entry : expected.get(SETTINGS_KEY_ALLOW))
				allowed.add(json(entry));
			Files.writeString(home.resolve("settings.json"), "{\"sandbox\":{\"excludedCommands\":[" + earlierExcluded +
				"]},\"permissions\":{\"allow\":[" + allowed + "]}}");

			Map<String, List<String>> added = integration().installPermissions(home, launcher, replacer());

			requireThat(added, "added").isEqualTo(Map.of(SETTINGS_KEY_EXCLUDED, List.of(updateWildcard)));
			requireThat(Files.readString(home.resolve("settings.json")), "settings").contains(json(updateWildcard));
		}
	}

	/**
	 * On Windows, every entry names the stable PowerShell launcher through PowerShell's fixed non-interactive form.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be written
	 */
	@Test
	public void permissionsUsePowerShellOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path launcher = home.resolve("data/bin/cat-updater.ps1");
			Integration integration = new Integration(Platform.WINDOWS_X64);

			Map<String, List<String>> added = integration.installPermissions(home, launcher, replacer());

			String prefix = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" + launcher +
				"\"";
			requireThat(added.get(SETTINGS_KEY_EXCLUDED), "excluded").isEqualTo(List.of(
				prefix + " install --harness claude", prefix + " update --harness claude",
				prefix + " update --harness claude latest", pattern(prefix + " update --harness claude"),
				prefix + " status", prefix + " status --harness claude", prefix + " rollback --harness claude",
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
		Path base = Path.of(System.getProperty("cat.updater.test.directory"));
		List<Object[]> cases = new ArrayList<>();
		for (String name : List.of("updater data", "updater $payload;echo hi", "updater's data"))
			cases.add(new Object[] {base.resolve(name + "/bin/cat-updater")});
		try
		{
			cases.add(new Object[] {base.resolve("updater*data/bin/cat-updater")});
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
			Path launcher = home.resolve("data/bin/cat-updater");
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
			Path launcher = home.resolve("data/bin/cat-updater");
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
			{"{\"permissions\":{},\"permissions\":{}}"}};
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
			requireRefusal(() -> integration.installPermissions(home, home.resolve("data/bin/cat-updater"), replacer()));
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
	 * The entries that the updater which earlier builds shared between harnesses told the user to add for its stable
	 * launcher are removed with the provider's permission removal, leaving the user's other entries.
	 *
	 * @throws IOException if the fixture cannot be created or the settings cannot be changed
	 */
	@Test
	public void legacyPermissionsAreRemoved() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path launcher = Path.of("/home/user/.local/share/cat-updater/bin/cat-updater");
			String update = launcher + " update --harness claude";
			Path settings = Files.writeString(home.resolve("settings.json"), "{\"sandbox\":{\"excludedCommands\":[" +
				json(update) + "," + json(update + " *") + ",\"git status\"]},\"permissions\":{\"allow\":[" +
				json("Bash(" + update + ")") + "," + json("Bash(" + update + " *)") + ",\"Bash(ls)\"]}}");
			Integration integration = integration();

			integration.removePermissions(home, integration.legacyPermissions(launcher), replacer());

			String content = Files.readString(settings);
			requireThat(content, "settings").doesNotContain(launcher.toString()).contains("git status").
				contains("Bash(ls)");
		}
	}

	/**
	 * Returns the entries that a POSIX stable launcher receives.
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
			quoted + " status --harness claude", quoted + " rollback --harness claude",
			quoted + " uninstall --harness claude", quoted + " uninstall --harness claude --delete-data");
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
	 * Creates a home whose marketplace holds an activated release with its marketplace metadata.
	 *
	 * @param home the fake home
	 * @return the marketplace root
	 * @throws IOException if the files cannot be written
	 */
	private static Path activatedMarketplace(Path home) throws IOException
	{
		Path root = home.resolve("plugins/cat-marketplace");
		Path metadata = root.resolve("plugins/cat/install/claude-marketplace.json");
		Files.createDirectories(metadata.getParent());
		Files.writeString(metadata, """
			{"name": "cat", "plugins": [{"name": "cat", "source": "./plugins/cat"}]}
			""");
		return root;
	}

	/**
	 * Returns an absolute path beneath this module's test directory without creating it.
	 *
	 * @param relative the path relative to the test directory
	 * @return the absolute, normalized path
	 */
	private static Path absoluteTestPath(String relative)
	{
		return Path.of(System.getProperty("cat.updater.test.directory")).toAbsolutePath().resolve(relative).
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
		Path base = Path.of(System.getProperty("cat.updater.test.directory"));
		Files.createDirectories(base);
		return Files.createTempDirectory(base, "integration-");
	}
}
