package com.holdmyspot.cat.codex.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
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
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
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
	 * The release's marketplace metadata in the fixtures.
	 */
	private static final String METADATA = "{\"name\":\"cat\",\"plugins\":[]}\n";

	/**
	 * The plugin version of the activated release, which names its build.
	 */
	private static final String VERSION = "1.0+b0123456789ab";

	/**
	 * Setup finds the Codex command-line program {@code codex}.
	 */
	@Test
	public void namesCodexProgram()
	{
		requireThat(provider().programName(), "programName").isEqualTo("codex");
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

			provider().register(home, cli, replacer());
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

			provider().register(home, cli, refusal.replacer());

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
				provider().register(home, cli, replacer());
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
				provider().register(home, cli, replacer());
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

		provider().unregister(home, cli);
		requireThat(cli.calls, "calls").isEqualTo(List.of(List.of("plugin", "remove", "cat@cat"),
			List.of("plugin", "marketplace", "remove", "cat")));
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
			new FakeCli(_ -> new CommandResult(1, "permission denied")));
	}

	/**
	 * The rules file permits install, update and status without asking, asks before uninstall, never covers setup, and
	 * is reported as added only by the call that wrote it.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void writesExactRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path launcher = home.resolveSibling("installer data/bin/cat-updater");
			String expected = rules(launcher);

			Map<String, List<String>> added = provider().installPermissions(home, launcher, replacer());
			requireThat(added, "added").isEqualTo(Map.of("rules/cat-updater.rules#", List.of(expected)));
			requireThat(Files.readString(home.resolve("rules/cat-updater.rules")), "content").isEqualTo(expected);
			requireThat(provider().installPermissions(home, launcher, replacer()), "repeated").isEmpty();
			provider().validateConfiguration(home);
		}
	}

	/**
	 * A rules file that an earlier build wrote, which also asked before an operation that the installer no longer has,
	 * is accepted as CAT's own, replaced with the maintained rules and kept when the earlier content is removed.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void replacesEarlierRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path launcher = home.resolveSibling("installer data/bin/cat-updater");
			String quoted = MAPPER.writeValueAsString(launcher.toString());
			String uninstall = "prefix_rule(pattern=[" + quoted + ", \"uninstall\"";
			String earlier = rules(launcher).replace(uninstall, "prefix_rule(pattern=[" + quoted +
				", \"rollback\", \"--harness\", \"codex\"], decision=\"prompt\")\n" + uninstall);
			write(home.resolve("rules/cat-updater.rules"), earlier);

			provider().validateConfiguration(home);
			Map<String, List<String>> added = provider().installPermissions(home, launcher, replacer());
			provider().removePermissions(home, Map.of("rules/cat-updater.rules#", List.of(earlier)), replacer());

			requireThat(added, "added").isEqualTo(Map.of("rules/cat-updater.rules#", List.of(rules(launcher))));
			requireThat(provider().permissions(launcher), "maintained").isEqualTo(added);
			requireThat(Files.readString(home.resolve("rules/cat-updater.rules")), "content").
				isEqualTo(rules(launcher));
		}
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
			provider().installPermissions(home, home.resolveSibling("old/bin/cat-updater"), replacer());
			Path launcher = home.resolveSibling("bin/cat-updater");
			TransientRefusal refusal = new TransientRefusal(home.resolve("rules/cat-updater.rules"));

			provider().installPermissions(home, launcher, refusal.replacer());

			requireThat(Files.readString(home.resolve("rules/cat-updater.rules")), "content").
				isEqualTo(rules(launcher));
			requireThat(refusal.pauses(), "pauses").isEqualTo(1);
		}
	}

	/**
	 * A rules file that CAT wrote for another launcher is replaced and reported as added.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void replacesRulesForMovedLauncher() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			provider().installPermissions(home, home.resolveSibling("old/bin/cat-updater"), replacer());
			Path launcher = home.resolveSibling("new/bin/cat-updater");

			requireThat(provider().installPermissions(home, launcher, replacer()), "added").
				isEqualTo(Map.of("rules/cat-updater.rules#", List.of(rules(launcher))));
		}
	}

	/**
	 * Setup refuses, changing nothing, a rules file that CAT did not write, including a CAT file that was edited.
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
			Path file = home.resolve("rules/cat-updater.rules");
			Files.createDirectories(file.getParent());
			Files.writeString(file, content);

			for (int attempt = 0; attempt < 2; ++attempt)
			{
				try
				{
					if (attempt == 0)
						provider().validateConfiguration(home);
					else
						provider().installPermissions(home, home.resolveSibling("bin/cat-updater"), replacer());
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
			resolve("bin/cat-updater");
		return new Object[][]{{"prefix_rule(pattern=[\"rm\"], decision=\"allow\")\n"},
			{rules(launcher) + "prefix_rule(pattern=[\"rm\"], decision=\"allow\")\n"}, {""}};
	}

	/**
	 * Setup refuses a rules directory or rules file that is a symbolic link.
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
		return new Object[][]{{"rules"}, {"rules/cat-updater.rules"}};
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
			Path file = home.resolve("rules/cat-updater.rules");
			Path userRules = home.resolve("rules/default.rules");
			Map<String, List<String>> added = provider().installPermissions(home, home.resolveSibling("bin/cat-updater"),
				replacer());
			Files.writeString(userRules, "prefix_rule(pattern=[\"git\", \"status\"], decision=\"allow\")\n");
			Files.writeString(file, Files.readString(file) + "# edited\n");

			provider().removePermissions(home, added, replacer());
			requireThat(file, "file").exists();
			Files.writeString(file, added.get("rules/cat-updater.rules#").getFirst());
			provider().removePermissions(home, added, replacer());
			requireThat(Files.exists(file), "fileExists").isFalse();
			requireThat(userRules, "userRules").isRegularFile();
		}
	}

	/**
	 * Returns the rules file that CAT writes for a POSIX launcher.
	 *
	 * @param launcher the stable launcher
	 * @return the file content
	 */
	private static String rules(Path launcher)
	{
		String quoted = MAPPER.writeValueAsString(launcher.toString());
		return """
			# Written by CAT's installer, which replaces or deletes this file. Do not edit it.
			prefix_rule(pattern=[%1$s, "install", "--harness", "codex"], decision="allow")
			prefix_rule(pattern=[%1$s, "update", "--harness", "codex"], decision="allow")
			prefix_rule(pattern=[%1$s, "status"], decision="allow")
			prefix_rule(pattern=[%1$s, "uninstall", "--harness", "codex"], decision="prompt")
			""".formatted(quoted);
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
