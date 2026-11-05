package com.holdmyspot.cat.codex.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the Codex harness integration through its installer contract.
 */
public final class IntegrationTest
{
	/**
	 * Quotes paths as the rules file writes them.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	/**
	 * Matches one rule of a Codex execution-policy file: its pattern and its decision.
	 */
	private static final Pattern PREFIX_RULE = Pattern.compile(
		"prefix_rule\\(pattern=(\\[[^]]*]), decision=(\"[^\"]*\")\\)");

	/**
	 * The release's marketplace metadata in the fixtures.
	 */
	private static final String METADATA = "{\"name\":\"cat\",\"plugins\":[]}\n";

	/**
	 * The plugin version of the activated release, which names its build.
	 */
	private static final String VERSION = "1.0+b0123456789ab";

	/**
	 * The plugin version of an earlier build of the same release.
	 */
	private static final String EARLIER_VERSION = "1.0+bfedcba987654";

	/**
	 * Setup finds the Codex command-line program {@code codex}.
	 */
	@Test
	public void namesCodexProgram()
	{
		requireThat(provider().programName(), "programName").isEqualTo("codex");
	}

	/**
	 * Codex runs the version folder with the greatest name, so a version folder whose name is greater than the
	 * registered version's would run instead of it, and one whose name is smaller would not.
	 */
	@Test
	public void greaterVersionFolderRunsInstead()
	{
		HarnessIntegration provider = provider();
		// The names are not semantic versions, so Codex compares them character by character
		PluginVersion smaller = PluginVersion.parse("1.0+b0123456789ab");
		PluginVersion greater = PluginVersion.parse("1.0+bfedcba987654");
		PluginVersion later = PluginVersion.parse("1.1+b000000000001");

		requireThat(provider.runsInstead(greater, smaller), "greaterBuild").isTrue();
		requireThat(provider.runsInstead(smaller, greater), "smallerBuild").isFalse();
		requireThat(provider.runsInstead(later, greater), "laterRelease").isTrue();
		requireThat(provider.runsInstead(greater, later), "earlierRelease").isFalse();
	}

	/**
	 * The recorded environment holds the user's home variable as given and names a home other than Codex's default
	 * in {@code CODEX_HOME}.
	 *
	 * @throws IOException if the environment is refused
	 */
	@Test
	public void environmentRecordsCodexHome() throws IOException
	{
		Path userHome = absoluteTestPath("user");
		Path home = absoluteTestPath("codex-home");

		Map<String, String> variables = provider().programEnvironment(home, Map.of(userHomeVariable(),
			userHome.toString(), "CODEX_SECRET", "not recorded"));

		requireThat(variables, "variables").isEqualTo(Map.of(userHomeVariable(), userHome.toString(), "CODEX_HOME",
			home.toString()));
	}

	/**
	 * The recorded environment leaves {@code CODEX_HOME} unset when the home is Codex's default,
	 * {@code <user home>/.codex}.
	 *
	 * @throws IOException if the environment is refused
	 */
	@Test
	public void environmentOmitsDefaultCodexHome() throws IOException
	{
		Path userHome = absoluteTestPath("user");

		Map<String, String> variables = provider().programEnvironment(userHome.resolve(".codex"),
			Map.of(userHomeVariable(), userHome.toString()));

		requireThat(variables, "variables").isEqualTo(Map.of(userHomeVariable(), userHome.toString()));
	}

	/**
	 * A program that answers {@code --version} with status 0 is accepted as Codex.
	 *
	 * @throws IOException if the answer is refused
	 */
	@Test
	public void identityAcceptsWorkingCodex() throws IOException
	{
		provider().requireIdentity(new CommandResult(0, "codex-cli 0.130.0\n"));
	}

	/**
	 * A program whose {@code --version} fails is refused, naming what the user must reinstall.
	 *
	 * @throws IOException if the answer is refused
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*failed with status 3; reinstall Codex.*")
	public void identityRefusesFailingCodex() throws IOException
	{
		provider().requireIdentity(new CommandResult(3, "broken\n"));
	}

	/**
	 * Registration writes the marketplace metadata from the activated release, adds the marketplace and the plugin
	 * through Codex, and accepts a cache copy equal to the marketplace source.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void registersThroughCodex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			activate(home);
			FakeCli cli = new FakeCli(arguments ->
			{
				if (arguments.equals(List.of("plugin", "add", "cat@cat")))
					codexCopiesPlugin(home, "");
				return new CommandResult(0, "");
			});

			provider().register(home, PluginVersion.parse(VERSION), cli, replacer());
			requireThat(cli.calls, "calls").isEqualTo(List.of(
				List.of("plugin", "marketplace", "add", home.resolve("plugins/cat-marketplace").toString()),
				List.of("plugin", "add", "cat@cat")));
			requireThat(Files.readString(home.resolve("plugins/cat-marketplace/.agents/plugins/marketplace.json")),
				"marketplace").isEqualTo(METADATA);
		}
	}

	/**
	 * Registration replaces an outdated marketplace manifest although the file system refuses the first attempt, as
	 * Windows does while another process reads the file.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void registerRetriesRefusedManifest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			activate(home);
			Path manifest = home.resolve("plugins/cat-marketplace/.agents/plugins/marketplace.json");
			write(manifest, "{\"name\": \"outdated\"}\n");
			TransientRefusal refusal = new TransientRefusal(manifest);
			FakeCli cli = new FakeCli(arguments ->
			{
				if (arguments.equals(List.of("plugin", "add", "cat@cat")))
					codexCopiesPlugin(home, "");
				return new CommandResult(0, "");
			});

			provider().register(home, PluginVersion.parse(VERSION), cli, refusal.replacer());

			requireThat(Files.readString(manifest), "marketplace").isEqualTo(METADATA);
			requireThat(refusal.pauses(), "pauses").isEqualTo(1);
		}
	}

	/**
	 * Registration fails when Codex's cache copy differs from the validated release, and leaves unregistering CAT to
	 * the installer, which does so only when the failed registration was part of an install.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void refusesDivergentCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			activate(home);
			FakeCli cli = new FakeCli(arguments ->
			{
				if (arguments.equals(List.of("plugin", "add", "cat@cat")))
					codexCopiesPlugin(home, "skills/added-by-codex/SKILL.md");
				return new CommandResult(0, "");
			});

			try
			{
				provider().register(home, PluginVersion.parse(VERSION), cli, replacer());
				throw new AssertionError("Registration accepted a divergent cache");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("differs from the validated release");
			}
			requireThat(cli.calls, "calls").isEqualTo(List.of(
				List.of("plugin", "marketplace", "add", home.resolve("plugins/cat-marketplace").toString()),
				List.of("plugin", "add", "cat@cat")));
		}
	}

	/**
	 * A failed marketplace registration reports Codex's output and runs nothing else.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void reportsFailedMarketplaceAdd() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			activate(home);
			FakeCli cli = new FakeCli(_ -> new CommandResult(1, "config.toml is read-only"));

			try
			{
				provider().register(home, PluginVersion.parse(VERSION), cli, replacer());
				throw new AssertionError("Registration ignored a failed marketplace add");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("config.toml is read-only");
			}
			requireThat(cli.calls.size(), "calls").isEqualTo(1);
		}
	}

	/**
	 * Unregistration removes the plugin, then the marketplace, and treats Codex's "not installed" and "not
	 * configured" answers as success.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void unregistersAbsentPlugin() throws IOException
	{
		Path home = testRoot().resolve("fake-home");
		FakeCli cli = new FakeCli(arguments ->
		{
			if (arguments.contains("marketplace"))
				return new CommandResult(1, "Error: marketplace `cat` is not configured or installed");
			return new CommandResult(1, "Error: plugin `cat@cat` is not installed");
		});

		provider().unregister(home, cli, false);
		requireThat(cli.calls, "calls").isEqualTo(List.of(List.of("plugin", "remove", "cat@cat"),
			List.of("plugin", "marketplace", "remove", "cat")));
	}

	/**
	 * Uninstall that keeps CAT's data runs Codex's plain {@code plugin remove}, which keeps the plugin data directory.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void unregisterKeepsData() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path data = home.resolve("plugins/data/cat-cat/policy.json");
			write(data, "{}");
			FakeCli cli = new FakeCli(_ -> new CommandResult(0, ""));

			provider().unregister(home, cli, true);
			requireThat(cli.calls, "calls").isEqualTo(List.of(List.of("plugin", "remove", "cat@cat"),
				List.of("plugin", "marketplace", "remove", "cat")));
			requireThat(Files.readString(data), "data").isEqualTo("{}");
		}
	}

	/**
	 * Registering a new build over an earlier one accepts the version folder that {@code codex plugin add} created in
	 * place of the earlier folder.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void registersNewVersionFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			activate(home);
			Path versions = home.resolve("plugins/cache/cat/cat");
			TemporaryDirectories.deleteRecursively(versions.resolve(VERSION));
			write(versions.resolve(EARLIER_VERSION).resolve(".codex-plugin/plugin.json"),
				"{\"name\":\"cat\",\"version\":\"" + EARLIER_VERSION + "\"}\n");
			FakeCli cli = new FakeCli(arguments ->
			{
				if (arguments.equals(List.of("plugin", "add", "cat@cat")))
					codexCopiesPlugin(home, "");
				return new CommandResult(0, "");
			});

			provider().register(home, PluginVersion.parse(VERSION), cli, replacer());
			try (Stream<Path> folders = Files.list(versions))
			{
				requireThat(folders.toList(), "versionFolders").isEqualTo(List.of(versions.resolve(VERSION)));
			}
		}
	}

	/**
	 * Registration fails when {@code codex plugin add} leaves the earlier build's version folder in place of the new
	 * one.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*does not load CAT 1\\.0\\+b0123456789ab.*")
	public void refusesKeptEarlierVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			activate(home);
			Path versions = home.resolve("plugins/cache/cat/cat");
			TemporaryDirectories.deleteRecursively(versions.resolve(VERSION));
			write(versions.resolve(EARLIER_VERSION).resolve(".codex-plugin/plugin.json"),
				"{\"name\":\"cat\",\"version\":\"" + EARLIER_VERSION + "\"}\n");

			provider().register(home, PluginVersion.parse(VERSION), new FakeCli(_ -> new CommandResult(0, "")),
				replacer());
		}
	}

	/**
	 * Registration refuses, before it runs Codex, when CAT's marketplace source holds another version than the one
	 * that it is asked to register, because Codex copies the source into the folder of the version that it states.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void refusesSourceOfOtherVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			activate(home);
			FakeCli cli = new FakeCli(_ -> new CommandResult(0, ""));

			try
			{
				provider().register(home, PluginVersion.parse(EARLIER_VERSION), cli, replacer());
				throw new AssertionError("Registration accepted a source of another version");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains(EARLIER_VERSION).contains(VERSION);
			}
			requireThat(cli.calls, "calls").isEmpty();
		}
	}

	/**
	 * Supplies two builds of one release in both orders, with whether Codex loads the registered build when it keeps
	 * the other build's folder: it does only when the registered build's name sorts last as text.
	 *
	 * @return the registered build, the kept build and whether registration succeeds
	 */
	@DataProvider
	public Object[][] keptBuilds()
	{
		return new Object[][] {{EARLIER_VERSION, VERSION, true}, {VERSION, EARLIER_VERSION, false}};
	}

	/**
	 * When {@code codex plugin add} keeps another build's folder beside the registered build's, registration accepts
	 * the result only when Codex loads the registered build, and never reports the other build as registered.
	 *
	 * @param registered the build to register
	 * @param kept       the other build, whose folder Codex keeps
	 * @param loaded     whether Codex loads the registered build
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "keptBuilds")
	public void registersOnlyLoadedBuild(String registered, String kept, boolean loaded) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path plugin = home.resolve("plugins/cat-marketplace/plugins/cat");
			write(plugin.resolve(".codex-plugin/plugin.json"), "{\"name\":\"cat\",\"version\":\"" + registered +
				"\"}\n");
			write(plugin.resolve("install/codex-marketplace.json"), METADATA);
			Path versions = home.resolve("plugins/cache/cat/cat");
			write(versions.resolve(kept).resolve(".codex-plugin/plugin.json"),
				"{\"name\":\"cat\",\"version\":\"" + kept + "\"}\n");
			FakeCli cli = new FakeCli(arguments ->
			{
				if (arguments.equals(List.of("plugin", "add", "cat@cat")))
					copy(plugin, versions.resolve(registered));
				return new CommandResult(0, "");
			});

			try
			{
				provider().register(home, PluginVersion.parse(registered), cli, replacer());
				requireThat(loaded, "loaded").isTrue();
			}
			catch (IOException e)
			{
				requireThat(loaded, "loaded").isFalse();
				requireThat(e.getMessage(), "message").contains("does not load CAT " + registered);
			}
		}
	}

	/**
	 * Unregistration reports any other Codex failure.
	 *
	 * @throws IOException if Codex's failure is reported
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*permission denied.*")
	public void reportsFailedUnregistration() throws IOException
	{
		provider().unregister(testRoot().resolve("fake-home"),
			new FakeCli(_ -> new CommandResult(1, "permission denied")), false);
	}

	/**
	 * The rules file permits installing into Codex and status without asking, asks before uninstall, and is reported
	 * as added only by the call that wrote it.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void writesExactRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path launcher = installer(home, VERSION);
			String expected = rules(launcher);

			Map<String, List<String>> added = provider().installPermissions(home, launcher, replacer());
			requireThat(added, "added").isEqualTo(Map.of("rules/cat-installer.rules#", List.of(expected)));
			requireThat(Files.readString(home.resolve("rules/cat-installer.rules")), "content").isEqualTo(expected);
			requireThat(provider().installPermissions(home, launcher, replacer()), "repeated").isEmpty();
			provider().validateConfiguration(home);
		}
	}

	/**
	 * Installing the latest or a named release into Codex runs without asking. No rule covers installing CAT into the
	 * other harness, which the user approves, or the removed update command, and uninstall always asks.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void otherHarnessInstallPrompts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			String launcher = installer(home, VERSION).toString();
			provider().installPermissions(home, installer(home, VERSION), replacer());
			String content = Files.readString(home.resolve("rules/cat-installer.rules"));

			requireThat(decisions(content, List.of(launcher, "install", "--harness", "claude")), "otherHarness").
				isEmpty();
			requireThat(decisions(content, List.of(launcher, "install", "--harness", "codex")), "ownHarness").
				isEqualTo(List.of("allow"));
			requireThat(decisions(content, List.of(launcher, "install", "--harness", "codex", "v1.2")),
				"namedRelease").isEqualTo(List.of("allow"));
			requireThat(decisions(content, List.of(launcher, "update", "--harness", "codex")), "update").isEmpty();
			requireThat(decisions(content, List.of(launcher, "uninstall", "--harness", "codex")), "uninstall").
				isEqualTo(List.of("prompt"));
		}
	}

	/**
	 * No rule lets an agent run, without asking, an install that gives the user's permission to create a permission
	 * profile or names another harness home, because those options come before the operation.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void consentOptionsPrompt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			String launcher = installer(home, VERSION).toString();
			provider().installPermissions(home, installer(home, VERSION), replacer());
			String content = Files.readString(home.resolve("rules/cat-installer.rules"));
			String other = home.resolveSibling("other-home").toString();

			for (List<String> options : List.of(List.of("--create-profile"), List.of("--home", other),
				List.of("--home", other, "--create-profile")))
			{
				for (List<String> release : List.of(List.<String>of(), List.of("v1.2")))
				{
					List<String> command = new ArrayList<>(List.of(launcher));
					command.addAll(options);
					command.addAll(List.of("install", "--harness", "codex"));
					command.addAll(release);
					requireThat(decisions(content, command), "decisions").withContext(command, "command").isEmpty();
				}
			}
		}
	}

	/**
	 * Install deletes the {@code rules/cat-updater.rules} file that an earlier CAT wrote before the installer was
	 * renamed, because the launcher it names is gone.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void removesLeftoverUpdaterRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path leftover = home.resolve("rules/cat-updater.rules");
			write(leftover, updaterRules(home.resolveSibling(".local/share/cat-updater/codex/bin/cat-updater")));

			provider().installPermissions(home, installer(home, VERSION), replacer());
			requireThat(Files.exists(leftover), "leftoverExists").isFalse();
			requireThat(Files.readString(home.resolve("rules/cat-installer.rules")), "content").
				isEqualTo(rules(installer(home, VERSION)));
		}
	}

	/**
	 * Install keeps a {@code rules/cat-updater.rules} file whose content CAT did not write, including one that asks
	 * before the removed rollback operation.
	 *
	 * @param content the file's content
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "foreignUpdaterRules")
	public void keepsForeignUpdaterRules(String content) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path file = home.resolve("rules/cat-updater.rules");
			write(file, content);

			provider().validateConfiguration(home);
			provider().installPermissions(home, installer(home, VERSION), replacer());
			requireThat(Files.readString(file), "content").isEqualTo(content);
		}
	}

	/**
	 * Supplies {@code rules/cat-updater.rules} contents that CAT did not write.
	 *
	 * @return file contents
	 */
	@DataProvider
	public Object[][] foreignUpdaterRules()
	{
		Path launcher = Path.of(System.getProperty("cat.installer.test.directory")).toAbsolutePath().
			resolve(".local/share/cat-updater/codex/bin/cat-updater");
		String rules = updaterRules(launcher);
		return new Object[][]{{rules + "prefix_rule(pattern=[\"rm\"], decision=\"allow\")\n"},
			{withRollback(rules, launcher)}, {"prefix_rule(pattern=[\"git\"], decision=\"allow\")\n"}};
	}

	/**
	 * Replacing the rules file that CAT wrote for another launcher succeeds although the file system refuses the
	 * first attempt, as Windows does while another process reads the file.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void rulesRetryRefusedReplacement() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			provider().installPermissions(home, installer(home, EARLIER_VERSION), replacer());
			Path launcher = installer(home, VERSION);
			TransientRefusal refusal = new TransientRefusal(home.resolve("rules/cat-installer.rules"));

			provider().installPermissions(home, launcher, refusal.replacer());

			requireThat(Files.readString(home.resolve("rules/cat-installer.rules")), "content").
				isEqualTo(rules(launcher));
			requireThat(refusal.pauses(), "pauses").isEqualTo(1);
		}
	}

	/**
	 * Installing a new version rewrites the rules to name the new version folder's installer, and reports them as
	 * added.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void rewritesRulesForNewVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			provider().installPermissions(home, installer(home, EARLIER_VERSION), replacer());
			Path launcher = installer(home, VERSION);

			requireThat(provider().installPermissions(home, launcher, replacer()), "added").
				isEqualTo(Map.of("rules/cat-installer.rules#", List.of(rules(launcher))));
			requireThat(Files.readString(home.resolve("rules/cat-installer.rules")), "content").
				isEqualTo(rules(launcher));
			requireThat(provider().permissions(launcher), "maintained").
				isEqualTo(Map.of("rules/cat-installer.rules#", List.of(rules(launcher))));
		}
	}

	/**
	 * Install refuses, changing nothing, a rules file that CAT did not write, including a CAT file that was edited and
	 * one that asks before the removed rollback operation.
	 *
	 * @param content the existing file content
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "foreignRules")
	public void refusesForeignRules(String content) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path file = home.resolve("rules/cat-installer.rules");
			Files.createDirectories(file.getParent());
			Files.writeString(file, content);

			for (int attempt = 0; attempt < 2; ++attempt)
			{
				try
				{
					if (attempt == 0)
						provider().validateConfiguration(home);
					else
						provider().installPermissions(home, installer(home, VERSION), replacer());
					throw new AssertionError("Accepted a rules file that CAT did not write");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").contains("CAT did not write");
				}
			}
			requireThat(Files.readString(file), "content").isEqualTo(content);
		}
	}

	/**
	 * Supplies rules files that CAT did not write.
	 *
	 * @return file contents
	 */
	@DataProvider
	public Object[][] foreignRules()
	{
		Path launcher = Path.of(System.getProperty("cat.installer.test.directory")).toAbsolutePath().
			resolve("plugins/cache/cat/cat/" + VERSION + "/bin/installer");
		return new Object[][]{{"prefix_rule(pattern=[\"rm\"], decision=\"allow\")\n"},
			{rules(launcher) + "prefix_rule(pattern=[\"rm\"], decision=\"allow\")\n"}, {""},
			{withRollback(rules(launcher), launcher)}};
	}

	/**
	 * Install refuses a rules directory or rules file that is a symbolic link.
	 *
	 * @param linked the path replaced with a symbolic link, relative to the home
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "linkedRules", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*symbolic link.*")
	public void refusesLinkedRules(String linked) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path link = home.resolve(linked);
			Files.createDirectories(link.getParent());
			Path target = Files.createDirectories(home.resolve("elsewhere"));
			Files.createSymbolicLink(link, target);

			provider().validateConfiguration(home);
		}
	}

	/**
	 * Supplies the rules paths that must not be symbolic links.
	 *
	 * @return paths relative to the home
	 */
	@DataProvider
	public Object[][] linkedRules()
	{
		return new Object[][]{{"rules"}, {"rules/cat-installer.rules"}};
	}

	/**
	 * Removal deletes the rules file only while it holds the recorded content, and keeps the user's rules.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void removesRecordedRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path file = home.resolve("rules/cat-installer.rules");
			Path userRules = home.resolve("rules/default.rules");
			Map<String, List<String>> added = provider().installPermissions(home, installer(home, VERSION), replacer());
			Files.writeString(userRules, "prefix_rule(pattern=[\"git\", \"status\"], decision=\"allow\")\n");
			Files.writeString(file, Files.readString(file) + "# edited\n");

			provider().removePermissions(home, added, replacer());
			requireThat(file, "file").exists();
			Files.writeString(file, added.get("rules/cat-installer.rules#").getFirst());
			provider().removePermissions(home, added, replacer());
			requireThat(Files.exists(file), "fileExists").isFalse();
			requireThat(userRules, "userRules").isRegularFile();
		}
	}

	/**
	 * Returns the rules file that CAT writes for a POSIX launcher.
	 *
	 * @param launcher the installer launcher in the version folder
	 * @return the file content
	 */
	private static String rules(Path launcher)
	{
		String quoted = MAPPER.writeValueAsString(launcher.toString());
		return """
			# Written by CAT's installer, which replaces or deletes this file. Do not edit it.
			prefix_rule(pattern=[%1$s, "install", "--harness", "codex"], decision="allow")
			prefix_rule(pattern=[%1$s, "status"], decision="allow")
			prefix_rule(pattern=[%1$s, "uninstall", "--harness", "codex"], decision="prompt")
			""".formatted(quoted);
	}

	/**
	 * Returns the {@code rules/cat-updater.rules} file that CAT wrote for a POSIX launcher before the installer was
	 * renamed.
	 *
	 * @param launcher the launcher of the earlier layout
	 * @return the file content
	 */
	private static String updaterRules(Path launcher)
	{
		String quoted = MAPPER.writeValueAsString(launcher.toString());
		String status = "prefix_rule(pattern=[" + quoted + ", \"status\"]";
		return rules(launcher).replace("CAT's installer", "CAT's updater").replace(status, "prefix_rule(pattern=[" +
			quoted + ", \"update\", \"--harness\", \"codex\"], decision=\"allow\")\n" + status);
	}

	/**
	 * Adds a rule that asks before the removed rollback operation, before the uninstall rule.
	 *
	 * @param rules    a rules file's content
	 * @param launcher the launcher that the rules name
	 * @return the changed content
	 */
	private static String withRollback(String rules, Path launcher)
	{
		String quoted = MAPPER.writeValueAsString(launcher.toString());
		String uninstall = "prefix_rule(pattern=[" + quoted + ", \"uninstall\"";
		return rules.replace(uninstall, "prefix_rule(pattern=[" + quoted +
			", \"rollback\", \"--harness\", \"codex\"], decision=\"prompt\")\n" + uninstall);
	}

	/**
	 * Returns the decisions of the rules whose pattern starts a command, as Codex matches a prefix rule.
	 *
	 * @param rules   a rules file's content
	 * @param command the command's tokens
	 * @return the decisions in file order
	 */
	private static List<String> decisions(String rules, List<String> command)
	{
		List<String> decisions = new ArrayList<>();
		Matcher matcher = PREFIX_RULE.matcher(rules);
		while (matcher.find())
		{
			List<String> pattern = new ArrayList<>();
			for (JsonNode token : MAPPER.readTree(matcher.group(1)))
				pattern.add(token.stringValue());
			if (pattern.size() <= command.size() && command.subList(0, pattern.size()).equals(pattern))
				decisions.add(MAPPER.readTree(matcher.group(2)).stringValue());
		}
		return decisions;
	}

	/**
	 * Returns the installer launcher of a version folder.
	 *
	 * @param home    the Codex home
	 * @param version the plugin version
	 * @return the launcher
	 */
	private static Path installer(Path home, String version)
	{
		return home.resolve("plugins/cache/cat/cat").resolve(version).resolve("bin/installer");
	}

	/**
	 * Creates the files that activation leaves before registration: the marketplace source and its cache copy.
	 *
	 * @param home the harness home
	 * @throws IOException if a fixture cannot be written
	 */
	private static void activate(Path home) throws IOException
	{
		Path plugin = home.resolve("plugins/cat-marketplace/plugins/cat");
		write(plugin.resolve(".codex-plugin/plugin.json"), "{\"name\":\"cat\",\"version\":\"" + VERSION + "\"}\n");
		write(plugin.resolve("install/codex-marketplace.json"), METADATA);
		write(plugin.resolve("client/runtime-binding"), "/runtime\n");
		copy(plugin, home.resolve("plugins/cache/cat/cat").resolve(VERSION));
	}

	/**
	 * Models {@code codex plugin add}: it replaces the plugin's cache directory with a copy of the marketplace source.
	 *
	 * @param home  the harness home
	 * @param extra a relative file that the copy adds, or empty for an exact copy
	 * @throws IOException if the copy fails
	 */
	private static void codexCopiesPlugin(Path home, String extra) throws IOException
	{
		Path cache = home.resolve("plugins/cache/cat/cat");
		TemporaryDirectories.deleteRecursively(cache);
		copy(home.resolve("plugins/cat-marketplace/plugins/cat"), cache.resolve(VERSION));
		if (!extra.isEmpty())
			write(cache.resolve(VERSION).resolve(extra), "extra");
	}

	/**
	 * Copies a directory tree.
	 *
	 * @param source      the tree
	 * @param destination the new directory
	 * @throws IOException if the copy fails
	 */
	private static void copy(Path source, Path destination) throws IOException
	{
		try (Stream<Path> paths = Files.walk(source))
		{
			for (Path path : (Iterable<Path>) paths::iterator)
			{
				Path target = destination.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path))
					Files.createDirectories(target);
				else
					Files.copy(path, target);
			}
		}
	}

	/**
	 * Writes a fixture file, creating its parent directories.
	 *
	 * @param file    the file
	 * @param content its content
	 * @throws IOException if the file cannot be written
	 */
	private static void write(Path file, String content) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	/**
	 * Returns the variable that names the user's home directory, from which Codex derives its default home.
	 *
	 * @return {@code USERPROFILE} on Windows, {@code HOME} elsewhere
	 */
	private static String userHomeVariable()
	{
		if (System.getProperty("os.name").startsWith("Windows"))
			return "USERPROFILE";
		return "HOME";
	}

	/**
	 * Returns an absolute path beneath this module's test directory without creating it.
	 *
	 * @param relative the path relative to the test directory
	 * @return the absolute, normalized path
	 * @throws IOException if the test directory cannot be created
	 */
	private static Path absoluteTestPath(String relative) throws IOException
	{
		return testRoot().resolve(relative).normalize();
	}

	/**
	 * Locates build-owned fixture storage passed explicitly by Maven.
	 *
	 * @return a directory beneath this module's target directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path testRoot() throws IOException
	{
		return Files.createDirectories(Path.of(System.getProperty("cat.installer.test.directory")).toAbsolutePath());
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
	 * Finds the Codex integration through the same registration that the installer consumes.
	 *
	 * @return the Codex integration
	 */
	private static HarnessIntegration provider()
	{
		return ServiceLoader.load(HarnessIntegration.class).stream().map(ServiceLoader.Provider::get).
			filter(provider -> provider.harness().equals("codex")).findFirst().
			orElseThrow(() -> new AssertionError("The Codex harness integration service is missing"));
	}

	/**
	 * Answers one Codex command in a test.
	 */
	@FunctionalInterface
	private interface Answer
	{
		/**
		 * Answers a command.
		 *
		 * @param arguments the arguments after the program
		 * @return the outcome
		 * @throws IOException if the modelled program's file changes fail
		 */
		CommandResult answer(List<String> arguments) throws IOException;
	}

	/**
	 * Records the argument vectors that the integration runs and answers them from a script.
	 */
	private static final class FakeCli implements HarnessCli
	{
		/**
		 * The argument vectors in the order they ran.
		 */
		private final List<List<String>> calls = new ArrayList<>();
		/**
		 * Answers each command.
		 */
		private final Answer answer;

		/**
		 * Creates a fake.
		 *
		 * @param answer answers each command
		 */
		private FakeCli(Answer answer)
		{
			this.answer = answer;
		}

		@Override
		public CommandResult run(List<String> arguments) throws IOException
		{
			calls.add(List.copyOf(arguments));
			return answer.answer(arguments);
		}
	}
}
