package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.StartupCache;
import com.holdmyspot.cat.common.updater.engine.StartupCacheTrainer;
import com.holdmyspot.cat.common.updater.engine.UpdateResult;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests writing a plugin directory's startup cache after the plugin directory is activated.
 * <p>
 * Each fixture plugin holds a small Java program in its {@code client/modules} folder and a {@code client/bin/java}
 * wrapper that runs the testing JVM's {@code java} with that folder as the module path, as a plugin's wrapper runs the
 * bound runtime's {@code java}. The program follows the system property {@code fixture.behavior}: {@code report}
 * writes what it observed to the file that {@code fixture.report} names, {@code fail} exits with status 1, and
 * {@code hang} never ends.
 */
public final class StartupCacheTrainerTest
{
	/**
	 * The variable that the fixture plugin's start-up program reads its plugin directory from.
	 */
	private static final String ROOT_VARIABLE = "FIXTURE_PLUGIN_ROOT";
	/**
	 * The input that the fixture plugin's start-up program receives.
	 */
	private static final String INPUT = "{\"event\":\"start\"}";
	/**
	 * The source of the fixture program.
	 */
	private static final String PROGRAM = """
		package fixture;

		import java.nio.charset.StandardCharsets;
		import java.nio.file.Files;
		import java.nio.file.Path;
		import java.util.Properties;
		import java.util.concurrent.CountDownLatch;

		public final class Main
		{
			public static void main(String[] arguments) throws Exception
			{
				System.out.println("output that must not reach the caller");
				System.err.println("error output that must not reach the caller");
				switch (System.getProperty("fixture.behavior"))
				{
					case "fail" -> System.exit(1);
					case "hang" -> new CountDownLatch(1).await();
					default ->
					{
						Properties observed = new Properties();
						observed.setProperty("input", new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
						observed.setProperty("home", String.valueOf(System.getenv("HOME")));
						observed.setProperty("root", String.valueOf(System.getenv("FIXTURE_PLUGIN_ROOT")));
						observed.setProperty("directory", System.getProperty("user.dir"));
						observed.setProperty("temporary", String.valueOf(System.getenv("TMPDIR")));
						observed.setProperty("ceiling", String.valueOf(System.getenv("GIT_CEILING_DIRECTORIES")));
						try (var out = Files.newOutputStream(Path.of(System.getProperty("fixture.report"))))
						{
							observed.store(out, null);
						}
					}
				}
			}
		}
		""";

	/**
	 * Creates the test.
	 */
	public StartupCacheTrainerTest()
	{
	}

	/**
	 * Training writes a startup cache that Java accepts for the plugin's own module path. The start-up program ran
	 * with the training input, the plugin directory in the plugin's variable, and a scratch home, temporary folder and
	 * project that the training removes afterwards.
	 *
	 * @throws IOException          if the fixture cannot be created or inspected
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void writesCacheThatJavaAccepts() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-trains-")).toRealPath();
			Path report = root.resolve("report.properties");
			Path plugin = plugin(root.resolve("plugin"), "report", report);
			Path scratch = root.resolve("scratch");

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMinutes(1)).train(plugin);

			requireThat(written, "written").isTrue();
			requireThat(entries(plugin.resolve(StartupCache.DIRECTORY)), "cacheFolder").
				isEqualTo(List.of(StartupCache.FILE.getFileName().toString()));
			requireThat(acceptedBy(plugin), "acceptedCache").isEqualTo("");
			Properties observed = new Properties();
			try (InputStream in = Files.newInputStream(report))
			{
				observed.load(in);
			}
			requireThat(observed.getProperty("input"), "input").isEqualTo(INPUT);
			requireThat(observed.getProperty("root"), "root").isEqualTo(plugin.toString());
			requireThat(Path.of(observed.getProperty("home")).startsWith(scratch), "scratchHome").isTrue();
			requireThat(Path.of(observed.getProperty("directory")).startsWith(scratch), "scratchProject").isTrue();
			requireThat(Path.of(observed.getProperty("temporary")).startsWith(scratch), "scratchTemporary").isTrue();
			requireThat(Path.of(observed.getProperty("ceiling")).startsWith(scratch), "ceiling").isTrue();
			requireThat(entries(scratch), "scratchEntries").isEmpty();
		}
	}

	/**
	 * A start-up program that fails leaves the plugin without a startup cache, removing the cache of an earlier
	 * activation, and reports that no cache was written.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void failedTrainingLeavesNoCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-fails-")).toRealPath();
			Path plugin = plugin(root.resolve("plugin"), "fail", root.resolve("report.properties"));
			Files.createDirectories(plugin.resolve(StartupCache.DIRECTORY));
			Files.writeString(plugin.resolve(StartupCache.FILE), "a cache of an earlier activation");
			Path scratch = root.resolve("scratch");

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMinutes(1)).train(plugin);

			requireThat(written, "written").isFalse();
			requireThat(Files.exists(plugin.resolve(StartupCache.DIRECTORY), NOFOLLOW_LINKS), "cacheFolder").isFalse();
			requireThat(entries(scratch), "scratchEntries").isEmpty();
		}
	}

	/**
	 * A start-up program that does not end within the time limit is stopped, and the plugin is left without a startup
	 * cache.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void stoppedTrainingLeavesNoCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-hangs-")).toRealPath();
			Path plugin = plugin(root.resolve("plugin"), "hang", root.resolve("report.properties"));
			Path scratch = root.resolve("scratch");

			boolean written = new StartupCacheTrainer(scratch, Duration.ofSeconds(1)).train(plugin);

			requireThat(written, "written").isFalse();
			requireThat(Files.exists(plugin.resolve(StartupCache.DIRECTORY), NOFOLLOW_LINKS), "cacheFolder").isFalse();
			requireThat(entries(scratch), "scratchEntries").isEmpty();
		}
	}

	/**
	 * A plugin that does not describe how to train its startup cache gets none.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void undescribedPluginGetsNoCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-undescribed-")).toRealPath();
			Path plugin = plugin(root.resolve("plugin"), "report", root.resolve("report.properties"));
			Files.delete(plugin.resolve(StartupCache.TRAINING));

			boolean written = new StartupCacheTrainer(root.resolve("scratch"), Duration.ofMinutes(1)).train(plugin);

			requireThat(written, "written").isFalse();
			requireThat(Files.exists(plugin.resolve(StartupCache.DIRECTORY), NOFOLLOW_LINKS), "cacheFolder").isFalse();
		}
	}

	/**
	 * After an install, the startup cache of every plugin directory that the harness's plan names is written and the
	 * outcome reports it; an outcome that activated nothing is returned unchanged.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void reportsCacheOfActivatedPlugin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-activation-")).toRealPath();
			Path home = Files.createDirectories(root.resolve("home"));
			plugin(home.resolve("live"), "report", root.resolve("report.properties"));
			StartupCacheTrainer trainer = new StartupCacheTrainer(root.resolve("scratch"), Duration.ofMinutes(1));
			UpdateResult installed = new UpdateResult(Result.INSTALLED, "harness-a", Optional.of("1.1"),
				Optional.empty(), "CAT was installed.", List.of(), true, List.of());
			UpdateResult upToDate = new UpdateResult(Result.UP_TO_DATE, "harness-a", Optional.of("1.1"),
				Optional.empty(), "CAT is up to date.", List.of(), true, List.of());

			UpdateResult trained = trainer.afterActivation(installed, new LiveProvider(), home);
			UpdateResult unchanged = trainer.afterActivation(upToDate, new LiveProvider(), home);

			requireThat(trained.aotCache(), "trained").isEqualTo(Optional.of(true));
			requireThat(Files.isRegularFile(home.resolve("live").resolve(StartupCache.FILE)), "cache").isTrue();
			requireThat(unchanged, "unchanged").isEqualTo(upToDate);
		}
	}

	/**
	 * After an install whose plugin directory cannot be trained, the outcome stays an install and reports that no
	 * startup cache was written.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void reportsMissingCacheWithoutFailing() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-untrained-")).toRealPath();
			Path home = Files.createDirectories(root.resolve("home"));
			plugin(home.resolve("live"), "fail", root.resolve("report.properties"));
			UpdateResult installed = new UpdateResult(Result.INSTALLED, "harness-a", Optional.of("1.1"),
				Optional.empty(), "CAT was installed.", List.of(), true, List.of());

			UpdateResult trained = new StartupCacheTrainer(root.resolve("scratch"), Duration.ofMinutes(1)).
				afterActivation(installed, new LiveProvider(), home);

			requireThat(trained, "trained").isEqualTo(installed.withAotCache(false));
		}
	}

	/**
	 * Creates a fixture plugin.
	 *
	 * @param plugin   the plugin directory, which must not exist
	 * @param behavior what the start-up program does
	 * @param report   the file that the start-up program writes its observations to
	 * @return the plugin directory
	 * @throws IOException if the fixture cannot be created
	 */
	private static Path plugin(Path plugin, String behavior, Path report) throws IOException
	{
		Path client = Files.createDirectories(plugin.resolve("client"));
		Path sources = Files.createDirectories(plugin.resolveSibling(plugin.getFileName() + "-sources/fixture"));
		Files.writeString(sources.resolveSibling("module-info.java"), "module fixture {}\n");
		Files.writeString(sources.resolve("Main.java"), PROGRAM);
		Path classes = sources.getParent().resolveSibling(plugin.getFileName() + "-classes");
		Path jdk = Path.of(System.getProperty("java.home"));
		run(List.of(tool(jdk, "javac"), "-d", classes.toString(), sources.resolveSibling("module-info.java").toString(),
			sources.resolve("Main.java").toString()));
		Path modules = Files.createDirectories(client.resolve("modules"));
		run(List.of(tool(jdk, "jar"), "--create", "--file", modules.resolve("fixture.jar").toString(), "-C",
			classes.toString(), "."));
		Path bin = Files.createDirectories(client.resolve("bin"));
		if (Platform.current().isWindows())
		{
			Files.writeString(bin.resolve("java.cmd"), "@echo off\r\n" +
				"for %%I in (\"%~dp0..\") do set \"CLIENT=%%~fI\"\r\n" +
				"\"" + tool(jdk, "java") + "\" --module-path \"%CLIENT%\\modules\" %*\r\n");
		}
		else
		{
			Path wrapper = Files.writeString(bin.resolve("java"), "#!/bin/sh\n" +
				"client=$(CDPATH= cd -P -- \"$(dirname -- \"$0\")/..\" && pwd -P)\n" +
				"exec '" + tool(jdk, "java") + "' --module-path \"$client/modules\" \"$@\"\n");
			requireThat(wrapper.toFile().setExecutable(true), "executable").isTrue();
		}
		String arguments = "\"-Dfixture.behavior=" + behavior + "\", \"-Dfixture.report=" +
			report.toString().replace("\\", "\\\\") + "\", \"-m\", \"fixture/fixture.Main\"";
		Files.writeString(plugin.resolve(StartupCache.TRAINING), "{\"format\": 1, \"arguments\": [" + arguments +
			"], \"plugin_root_variable\": \"" + ROOT_VARIABLE + "\", \"input\": \"" + INPUT.replace("\"", "\\\"") +
			"\"}\n", UTF_8);
		return plugin;
	}

	/**
	 * Runs the fixture plugin's start-up program with the plugin's startup cache, requiring Java to use the cache.
	 *
	 * @param plugin the fixture plugin
	 * @return the program's standard error, empty when Java used the cache
	 * @throws IOException          if the program cannot be run, or ends with a failure status
	 * @throws InterruptedException if the test is interrupted
	 */
	private static String acceptedBy(Path plugin) throws IOException, InterruptedException
	{
		String wrapper = "java";
		if (Platform.current().isWindows())
			wrapper = "java.cmd";
		Path error = plugin.resolveSibling("accepted-error.txt");
		ProcessBuilder builder = new ProcessBuilder(plugin.resolve("client/bin").resolve(wrapper).toString(),
			"-XX:AOTMode=on", "-XX:AOTCache=" + plugin.resolve(StartupCache.FILE), "-Dfixture.behavior=report",
			"-Dfixture.report=" + plugin.resolveSibling("accepted.properties"), "-m", "fixture/fixture.Main").
			redirectInput(Files.writeString(plugin.resolveSibling("input.txt"), INPUT).toFile()).
			redirectOutput(ProcessBuilder.Redirect.DISCARD).
			redirectError(error.toFile());
		try (Process process = builder.start())
		{
			if (!process.waitFor(1, TimeUnit.MINUTES))
			{
				process.destroyForcibly();
				throw new IOException("The program did not end: " + builder.command());
			}
			String standardError = Files.readString(error);
			if (process.exitValue() != 0)
				throw new IOException("Java refused the cache: " + standardError);
			return standardError.replace("error output that must not reach the caller", "").strip();
		}
	}

	/**
	 * Returns the path of a JDK tool.
	 *
	 * @param jdk  the JDK
	 * @param name the tool's name without an extension
	 * @return the tool's path
	 */
	private static String tool(Path jdk, String name)
	{
		if (Platform.current().isWindows())
			return jdk.resolve("bin").resolve(name + ".exe").toString();
		return jdk.resolve("bin").resolve(name).toString();
	}

	/**
	 * Runs a JDK tool, requiring success.
	 *
	 * @param command the command line
	 * @throws IOException if the tool cannot be run or fails
	 */
	private static void run(List<String> command) throws IOException
	{
		try (Process process = new ProcessBuilder(command).redirectErrorStream(true).start())
		{
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			if (process.waitFor() != 0)
				throw new IOException(command + " failed: " + output);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while running " + command, e);
		}
	}

	/**
	 * Lists the names in a directory.
	 *
	 * @param directory the directory, which may be absent
	 * @return the names, sorted; empty if the directory is absent
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<String> entries(Path directory) throws IOException
	{
		if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
			return List.of();
		List<String> names = new ArrayList<>();
		try (Stream<Path> stream = Files.list(directory))
		{
			stream.forEach(path -> names.add(path.getFileName().toString()));
		}
		names.sort(null);
		return names;
	}
}
