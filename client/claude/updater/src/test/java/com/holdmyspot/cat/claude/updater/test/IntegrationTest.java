package com.holdmyspot.cat.claude.updater.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.updater.Integration;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.activation.CommandResult;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
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
	 * Discovery records the path at which it found the first {@code claude} program on the search path, without
	 * resolving the link that Claude Code re-points when it updates itself, a search path without
	 * relative entries or entries that a project or another user-writable location controls, and only the variables
	 * that Claude Code needs for the default configuration directory.
	 *
	 * @throws IOException if the fake program cannot be created or discovery fails
	 */
	@Test
	public void discoverRecordsProgram() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory());
			Path userHome = Files.createDirectories(root.resolve("user"));
			Path home = Files.createDirectories(userHome.resolve(".claude"));
			Path workingDirectory = Files.createDirectories(root.resolve("project"));
			Path temporaryDirectory = Files.createDirectories(root.resolve("temporary"));
			Path installed = Files.createDirectories(root.resolve("installed/bin"));
			Path real = fakeClaude(Files.createDirectories(root.resolve("versions/2.1.318")), "2.1.318 (Claude Code)", 0);
			Path link = Files.createSymbolicLink(installed.resolve(real.getFileName()), real);
			List<String> decoys = new ArrayList<>();
			for (Path directory : List.of(workingDirectory.resolve("bin"), temporaryDirectory.resolve("bin"),
				home.resolve("bin"), userHome.resolve(".cat/bin")))
			{
				fakeClaude(Files.createDirectories(directory), "decoy", 0);
				decoys.add(directory.toString());
			}
			Path other = Files.createDirectories(root.resolve("other"));
			StringJoiner path = new StringJoiner(File.pathSeparator);
			for (String entry : decoys)
				path.add(entry);
			path.add("relative-bin").add("").add(installed.toString()).add(other.toString());
			Platform platform = Platform.current();
			Map<String, String> environment = new HashMap<>();
			environment.put(platform.homeVariable(), userHome.toString());
			environment.put("PATH", path.toString());
			environment.put("UNRELATED", "value");
			Map<String, String> expectedEnvironment = new HashMap<>();
			expectedEnvironment.put(platform.homeVariable(), userHome.toString());
			expectedEnvironment.put("PATH", installed + File.pathSeparator + other);
			List<String> expectedCommand = List.of(link.toString());
			if (platform.isWindows())
			{
				// Windows starts cmd.exe only with SystemRoot, so the fake batch program needs the real value.
				String systemRoot = System.getenv("SystemRoot");
				environment.put("SystemRoot", systemRoot);
				environment.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");
				expectedEnvironment.put("SystemRoot", systemRoot);
				expectedEnvironment.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");
				expectedEnvironment.put("NoDefaultCurrentDirectoryInExePath", "1");
				expectedCommand = List.of(Path.of(systemRoot, "System32/cmd.exe").toString(), "/d", "/s", "/c",
					link.toString());
			}

			HarnessCommand command = new Integration(platform, workingDirectory, temporaryDirectory).
				discover(home, environment);

			requireThat(command.command(), "command").isEqualTo(expectedCommand);
			requireThat(command.environment(), "environment").isEqualTo(expectedEnvironment);
		}
	}

	/**
	 * A configuration directory other than the default one is recorded in {@code CLAUDE_CONFIG_DIR}, which also moves
	 * Claude Code's {@code .claude.json}.
	 *
	 * @throws IOException if the fake program cannot be created or discovery fails
	 */
	@Test
	public void discoverRecordsConfigDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory());
			Path userHome = Files.createDirectories(root.resolve("user"));
			Path home = Files.createDirectories(root.resolve("custom-claude"));
			Path installed = Files.createDirectories(root.resolve("installed"));
			fakeClaude(installed, "2.1.318 (Claude Code)", 0);

			HarnessCommand command = integration(root).discover(home, programEnvironment(userHome, installed));

			requireThat(command.environment().get("CLAUDE_CONFIG_DIR"), "CLAUDE_CONFIG_DIR").isEqualTo(home.toString());
		}
	}

	/**
	 * Discovery names Claude Code when no {@code claude} program is on the search path.
	 *
	 * @throws IOException if the fixture cannot be created or the refusal is returned
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*Claude Code.*")
	public void discoverRefusesMissingProgram() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory());
			Path userHome = Files.createDirectories(root.resolve("user"));
			Path empty = Files.createDirectories(root.resolve("empty"));

			integration(root).discover(userHome.resolve(".claude"), programEnvironment(userHome, empty));
		}
	}

	/**
	 * Discovery refuses a program on an allowed search directory whose link leads into a directory that discovery
	 * never records, because a project or download could replace the program there.
	 *
	 * @param excluded the excluded directory, relative to the test directory
	 * @throws IOException if the fixture cannot be created or the refusal is returned
	 */
	@Test(dataProvider = "excludedDirectories", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*claude.*resolves into.*")
	public void discoverRefusesLinkIntoExcludedDirectory(String excluded) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory());
			Path userHome = Files.createDirectories(root.resolve("user"));
			Path installed = Files.createDirectories(root.resolve("installed"));
			Path target = fakeClaude(Files.createDirectories(root.resolve(excluded)), "2.1.318 (Claude Code)", 0);
			Files.createSymbolicLink(installed.resolve(target.getFileName()), target);

			integration(root).discover(userHome.resolve(".claude"), programEnvironment(userHome, installed));
		}
	}

	/**
	 * Supplies the directories that discovery never records, relative to the test directory.
	 *
	 * @return the working directory, the temporary directory, the configuration directory and CAT's user directory
	 */
	@DataProvider
	public Object[][] excludedDirectories()
	{
		return new Object[][] {{"project/bin"}, {"temporary/bin"}, {"user/.claude/bin"}, {"user/.cat/bin"}};
	}

	/**
	 * Discovery refuses a program that does not run as Claude Code.
	 *
	 * @param output     what the fake program prints
	 * @param exitStatus the fake program's exit status
	 * @throws IOException if the fixture cannot be created or the refusal is returned
	 */
	@Test(dataProvider = "brokenPrograms", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*claude --version.*")
	public void discoverRefusesBrokenProgram(String output, int exitStatus) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory());
			Path userHome = Files.createDirectories(root.resolve("user"));
			Path installed = Files.createDirectories(root.resolve("installed"));
			fakeClaude(installed, output, exitStatus);

			integration(root).discover(userHome.resolve(".claude"), programEnvironment(userHome, installed));
		}
	}

	/**
	 * Supplies programs named {@code claude} that are not a working Claude Code.
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

			integration(home).register(home, cli);

			requireThat(cli.calls(), "calls").isEqualTo(List.of(
				List.of("plugin", "marketplace", "add", root.toString()),
				List.of("plugin", "install", "cat@cat")));
			requireThat(Files.readString(root.resolve(".claude-plugin/marketplace.json")), "marketplaceManifest").
				isEqualTo(Files.readString(root.resolve("plugins/cat/install/claude-marketplace.json")));
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

			integration(home).register(home, new RecordingCli(answers));
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

			integration(home).unregister(home, cli);

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

			integration(home).unregister(home, new RecordingCli(answers));
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

			Map<String, List<String>> added = integration(home).installPermissions(home, launcher);

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

			Map<String, List<String>> added = integration(home).installPermissions(home, launcher);

			String quoted = ShellQuoting.posix(launcher.toString());
			List<String> allowed = added.get(SETTINGS_KEY_ALLOW);
			requireThat(allowed, "allowed").isEqualTo(List.of(
				"Bash(" + quoted + " install --harness claude)",
				"Bash(" + pattern(quoted + " install --harness claude") + ")",
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
			Integration integration = new Integration(Platform.WINDOWS_X64, home.resolve("project"),
				home.resolve("temporary"));

			Map<String, List<String>> added = integration.installPermissions(home, launcher);

			String prefix = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" + launcher +
				"\"";
			requireThat(added.get(SETTINGS_KEY_EXCLUDED), "excluded").isEqualTo(List.of(
				prefix + " install --harness claude", pattern(prefix + " install --harness claude"),
				prefix + " update --harness claude", pattern(prefix + " update --harness claude"),
				prefix + " status", prefix + " status --harness claude",
				prefix + " rollback --harness claude",
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

			Map<String, List<String>> added = new Integration(Platform.LINUX_X64, home.resolve("project"),
				home.resolve("temporary")).installPermissions(home, launcher);

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
			Integration integration = integration(home);

			Map<String, List<String>> added = integration.installPermissions(home, launcher);
			String merged = Files.readString(settings);
			FileTime modified = Files.getLastModifiedTime(settings);
			Map<String, List<String>> repeated = integration.installPermissions(home, launcher);

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
			Integration integration = integration(home);
			Map<String, List<String>> added = integration.installPermissions(home, launcher);
			String removedByUser = added.get(SETTINGS_KEY_ALLOW).getFirst();
			Files.writeString(settings, Files.readString(settings).replace(json(removedByUser) + ",", ""));

			integration.removePermissions(home, added);

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

			integration(home).removePermissions(home, Map.of(SETTINGS_KEY_ALLOW, List.of("Bash(ls)")));

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
			Integration integration = integration(home);
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
			Integration integration = integration(home);

			requireRefusal(() -> integration.validateConfiguration(home));
			requireRefusal(() -> integration.installPermissions(home, home.resolve("data/bin/cat-updater")));
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

			integration(home).validateConfiguration(home);
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
		List<String> prompted = List.of(install, pattern(install), update, pattern(update), quoted + " status",
			quoted + " status --harness claude");
		List<String> excluded = new ArrayList<>(prompted);
		excluded.addAll(List.of(quoted + " rollback --harness claude", quoted + " uninstall --harness claude",
			quoted + " uninstall --harness claude --delete-data"));
		List<String> allowed = new ArrayList<>();
		for (String entry : prompted)
			allowed.add("Bash(" + entry + ")");
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
	 * Creates an integration whose working and temporary directories lie inside a test directory.
	 *
	 * @param root the test directory
	 * @return the integration for the current platform
	 */
	private static Integration integration(Path root)
	{
		return new Integration(Platform.current(), root.resolve("project"), root.resolve("temporary"));
	}

	/**
	 * Returns a setup environment that names the user's home and one search directory.
	 *
	 * @param userHome  the user's home directory
	 * @param directory the only search directory
	 * @return the environment
	 */
	private static Map<String, String> programEnvironment(Path userHome, Path directory)
	{
		Platform platform = Platform.current();
		Map<String, String> environment = new HashMap<>();
		environment.put(platform.homeVariable(), userHome.toString());
		environment.put("PATH", directory.toString());
		if (platform.isWindows())
		{
			// Windows starts cmd.exe only with SystemRoot, so the fake batch program needs the real value.
			environment.put("SystemRoot", System.getenv("SystemRoot"));
		}
		return environment;
	}

	/**
	 * Creates a fake {@code claude} program that prints one line and exits.
	 *
	 * @param directory  the directory to create it in
	 * @param output     the line to print
	 * @param exitStatus the exit status
	 * @return the program's path
	 * @throws IOException if the program cannot be written
	 */
	private static Path fakeClaude(Path directory, String output, int exitStatus) throws IOException
	{
		if (Platform.current().isWindows())
		{
			return Files.writeString(directory.resolve("claude.cmd"),
				"@echo " + output + "\r\n@exit /b " + exitStatus + "\r\n");
		}
		Path program = Files.writeString(directory.resolve("claude"),
			"#!/bin/sh\necho '" + output + "'\nexit " + exitStatus + "\n");
		if (!program.toFile().setExecutable(true))
			throw new IOException("Could not make the fake program executable: " + program);
		return program;
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
