package com.holdmyspot.cat.codex.updater.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.activation.CommandResult;
import com.holdmyspot.cat.common.updater.activation.HarnessCli;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the Codex harness integration through its updater contract.
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
	 * Discovery records the real path of the first executable {@code codex} on PATH, a PATH without relative,
	 * empty, repeated or excluded entries, and only the environment that Codex needs, after {@code codex --version}
	 * succeeds.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void discoversCodexOnPath() throws IOException
	{
		requirePosix();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(testRoot(), "discover-"));
			Path home = Files.createDirectories(root.resolve("codex-home"));
			Path userHome = Files.createDirectories(root.resolve("user"));
			Path xdgData = root.resolve("xdg");
			Path underHome = fake(home.resolve("bin"), 0);
			Path underCat = fake(userHome.resolve(".cat/bin"), 0);
			Path underData = fake(xdgData.resolve("cat-updater/bin"), 0);
			Path temporaryEntry = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("bin");
			Path notExecutable = Files.createDirectories(root.resolve("not-executable"));
			Files.writeString(notExecutable.resolve("codex"), "#!/bin/sh\nexit 0\n");
			Path real = fake(root.resolve("real"), 0);
			Path linked = Files.createDirectories(root.resolve("linked"));
			Files.createSymbolicLink(linked.resolve("codex"), real.resolve("codex"));
			String path = String.join(File.pathSeparator, "relative/bin", "", underHome.toString(),
				underCat.toString(), underData.toString(), temporaryEntry.toString(),
				notExecutable.toString(), linked.toString(), real.toString(), linked.toString());

			HarnessCommand command = provider().discover(home, Map.of("HOME", userHome.toString(), "PATH", path,
				"XDG_DATA_HOME", xdgData.toString(), "CODEX_SECRET", "not recorded"));
			requireThat(command.command(), "command").isEqualTo(List.of(real.resolve("codex").toRealPath().toString()));
			requireThat(command.environment(), "environment").isEqualTo(Map.of("HOME", userHome.toString(),
				"PATH", String.join(File.pathSeparator, notExecutable.toString(), linked.toString(), real.toString()),
				"CODEX_HOME", home.toString()));
			requireThat(Files.readString(real.resolve("invocations")), "invocations").isEqualTo("--version\n");
			requireThat(Files.exists(underHome.resolve("invocations")), "underHomeRan").isFalse();
		}
	}

	/**
	 * Discovery leaves CODEX_HOME unset when the home is Codex's default, {@code <HOME>/.codex}.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void omitsDefaultCodexHome() throws IOException
	{
		requirePosix();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(testRoot(), "discover-"));
			Path userHome = Files.createDirectories(root.resolve("user"));
			Path home = Files.createDirectories(userHome.resolve(".codex"));
			Path real = fake(root.resolve("real"), 0);

			HarnessCommand command = provider().discover(home, Map.of("HOME", userHome.toString(), "PATH",
				real.toString()));
			requireThat(command.environment(), "environment").
				isEqualTo(Map.of("HOME", userHome.toString(), "PATH", real.toString()));
		}
	}

	/**
	 * Discovery fails, naming what the user must install, when no executable {@code codex} is on PATH.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*not on PATH; install Codex.*")
	public void refusesMissingCodex() throws IOException
	{
		requirePosix();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(testRoot(), "discover-"));
			Path home = Files.createDirectories(root.resolve("codex-home"));
			Path empty = Files.createDirectories(root.resolve("empty"));

			provider().discover(home, Map.of("HOME", root.toString(), "PATH", empty.toString()));
		}
	}

	/**
	 * Discovery fails when {@code codex --version} fails.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "(?s).*failed with status 3.*")
	public void refusesFailingCodex() throws IOException
	{
		requirePosix();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(testRoot(), "discover-"));
			Path home = Files.createDirectories(root.resolve("codex-home"));
			Path real = fake(root.resolve("real"), 3);

			provider().discover(home, Map.of("HOME", root.toString(), "PATH", real.toString()));
		}
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

			provider().register(home, cli);
			requireThat(cli.calls, "calls").isEqualTo(List.of(
				List.of("plugin", "marketplace", "add", home.resolve("plugins/cat-marketplace").toString()),
				List.of("plugin", "add", "cat@cat")));
			requireThat(Files.readString(home.resolve("plugins/cat-marketplace/.agents/plugins/marketplace.json")),
				"marketplace").isEqualTo(METADATA);
		}
	}

	/**
	 * Registration fails, and unregisters CAT again, when Codex's cache copy differs from the validated release.
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
				provider().register(home, cli);
				throw new AssertionError("Registration accepted a divergent cache");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("differs from the validated release");
			}
			requireThat(cli.calls.subList(2, cli.calls.size()), "unregisterCalls").isEqualTo(List.of(
				List.of("plugin", "remove", "cat@cat"), List.of("plugin", "marketplace", "remove", "cat")));
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
				provider().register(home, cli);
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
	 * The rules file permits install, update and status without asking, asks before rollback and uninstall, never
	 * covers setup, and is reported as added only by the call that wrote it.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void writesExactRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path launcher = home.resolveSibling("updater data/bin/cat-updater");
			String expected = rules(launcher);

			Map<String, List<String>> added = provider().installPermissions(home, launcher);
			requireThat(added, "added").isEqualTo(Map.of("rules/cat-updater.rules#", List.of(expected)));
			requireThat(Files.readString(home.resolve("rules/cat-updater.rules")), "content").isEqualTo(expected);
			requireThat(provider().installPermissions(home, launcher), "repeated").isEmpty();
			provider().validateConfiguration(home);
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
			provider().installPermissions(home, home.resolveSibling("old/bin/cat-updater"));
			Path launcher = home.resolveSibling("new/bin/cat-updater");

			requireThat(provider().installPermissions(home, launcher), "added").
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
						provider().installPermissions(home, home.resolveSibling("bin/cat-updater"));
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
		Path launcher = Path.of(System.getProperty("cat.updater.test.directory")).toAbsolutePath().
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
			Map<String, List<String>> added = provider().installPermissions(home, home.resolveSibling("bin/cat-updater"));
			Files.writeString(userRules, "prefix_rule(pattern=[\"git\", \"status\"], decision=\"allow\")\n");
			Files.writeString(file, Files.readString(file) + "# edited\n");

			provider().removePermissions(home, added);
			requireThat(file, "file").exists();
			Files.writeString(file, added.get("rules/cat-updater.rules#").getFirst());
			provider().removePermissions(home, added);
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
			# Written by CAT's updater, which replaces or deletes this file. Do not edit it.
			prefix_rule(pattern=[%1$s, "install", "--harness", "codex"], decision="allow")
			prefix_rule(pattern=[%1$s, "update", "--harness", "codex"], decision="allow")
			prefix_rule(pattern=[%1$s, "status"], decision="allow")
			prefix_rule(pattern=[%1$s, "rollback", "--harness", "codex"], decision="prompt")
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
		write(plugin.resolve(".codex-plugin/plugin.json"), "{\"name\":\"cat\",\"version\":\"1.0\"}\n");
		write(plugin.resolve("install/codex-marketplace.json"), METADATA);
		write(plugin.resolve("client/runtime-binding"), "/runtime\n");
		copy(plugin, home.resolve("plugins/cache/cat/cat/1.0"));
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
		copy(home.resolve("plugins/cat-marketplace/plugins/cat"), cache.resolve("1.0"));
		if (!extra.isEmpty())
			write(cache.resolve("1.0").resolve(extra), "extra");
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
	 * Creates a fake {@code codex} program that appends its arguments to {@code invocations} beside itself, prints a
	 * version and exits with a status.
	 *
	 * @param directory the directory to create the program in
	 * @param status    the exit status
	 * @return the directory
	 * @throws IOException if the program cannot be written
	 */
	private static Path fake(Path directory, int status) throws IOException
	{
		Files.createDirectories(directory);
		Path program = directory.resolve("codex");
		Files.writeString(program, """
			#!/bin/sh
			printf '%%s\\n' "$@" >> "${0%%/*}/invocations"
			echo "codex-cli 0.0.0"
			exit %d
			""".formatted(status));
		Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
		return directory;
	}

	/**
	 * Skips a discovery test on Windows, whose fake program would need a batch file run through cmd.exe.
	 */
	private static void requirePosix()
	{
		if (System.getProperty("os.name").startsWith("Windows"))
			throw new SkipException("The fake Codex program is a POSIX shell script");
	}

	/**
	 * Locates build-owned fixture storage passed explicitly by Maven.
	 *
	 * @return a directory beneath this module's target directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path testRoot() throws IOException
	{
		return Files.createDirectories(Path.of(System.getProperty("cat.updater.test.directory")).toAbsolutePath());
	}

	/**
	 * Finds the Codex integration through the same registration that the updater consumes.
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
