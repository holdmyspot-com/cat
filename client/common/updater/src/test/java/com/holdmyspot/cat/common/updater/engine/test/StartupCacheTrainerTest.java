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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests writing a plugin directory's startup cache after the plugin directory is activated.
 * <p>
 * Each fixture plugin has a {@code client/bin/java} wrapper, as a plugin has one that runs the bound runtime's
 * {@code java}. One test runs real Java: its wrapper runs the testing JVM's {@code java} with the plugin's
 * {@link StartupProgram} jar. The other tests concern how training reacts to a run's outcome, so their wrapper runs
 * {@link FakeJava}, which reacts to the cache options as Java documents them but records and writes nothing real.
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
	 * Stands in for the check of a process without administrative rights.
	 */
	private static final BooleanSupplier NOT_ELEVATED = () -> false;

	/**
	 * Creates the test.
	 */
	public StartupCacheTrainerTest()
	{
	}

	/**
	 * Training with real Java writes a startup cache that Java accepts for the plugin's own class path, and removes
	 * the training's scratch folders.
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
			Path plugin = javaPlugin(root.resolve("plugin"));
			Path scratch = root.resolve("scratch");

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMinutes(1), NOT_ELEVATED).train(plugin);

			requireThat(written, "written").isTrue();
			requireThat(entries(plugin.resolve(StartupCache.DIRECTORY)), "cacheFolder").
				isEqualTo(List.of(StartupCache.FILE.getFileName().toString()));
			requireThat(acceptedBy(plugin), "acceptedCache").isEqualTo("");
			requireThat(entries(scratch), "scratchEntries").isEmpty();
		}
	}

	/**
	 * Training runs the start-up program with the training input, the plugin directory in the plugin's variable, and
	 * a scratch home, temporary folder, project and Git ceiling that the training removes afterwards.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void trainsInScratchEnvironment() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-environment-")).toRealPath();
			Path report = root.resolve("report.properties");
			Path plugin = fakePlugin(root.resolve("plugin"), "report", report);
			Path scratch = root.resolve("scratch");

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMinutes(1), NOT_ELEVATED).train(plugin);

			requireThat(written, "written").isTrue();
			Properties observed = new Properties();
			try (InputStream in = Files.newInputStream(report))
			{
				observed.load(in);
			}
			requireThat(observed.getProperty("input"), "input").isEqualTo(INPUT);
			requireThat(observed.getProperty("variable." + ROOT_VARIABLE), "root").isEqualTo(plugin.toString());
			requireThat(isIn(observed, "variable.HOME", scratch), "scratchHome").isTrue();
			requireThat(isIn(observed, "property.user.dir", scratch), "scratchProject").isTrue();
			requireThat(isIn(observed, "variable.TMPDIR", scratch), "scratchTemporary").isTrue();
			requireThat(isIn(observed, "variable.GIT_CEILING_DIRECTORIES", scratch), "ceiling").isTrue();
			requireThat(entries(scratch), "scratchEntries").isEmpty();
		}
	}

	/**
	 * A start-up program whose cache-writing run fails after writing part of the cache leaves the plugin without a
	 * startup cache or partial file, removing the cache of an earlier activation, and reports that no cache was
	 * written.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void failedTrainingLeavesNoCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-fails-")).toRealPath();
			Path plugin = fakePlugin(root.resolve("plugin"), "fail", root.resolve("report.properties"));
			Files.createDirectories(plugin.resolve(StartupCache.DIRECTORY));
			Files.writeString(plugin.resolve(StartupCache.FILE), "a cache of an earlier activation");
			Path scratch = root.resolve("scratch");

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMinutes(1), NOT_ELEVATED).train(plugin);

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
			Path plugin = fakePlugin(root.resolve("plugin"), "hang", root.resolve("report.properties"));
			Path scratch = root.resolve("scratch");

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMillis(300), NOT_ELEVATED).train(plugin);

			requireThat(written, "written").isFalse();
			requireThat(Files.exists(plugin.resolve(StartupCache.DIRECTORY), NOFOLLOW_LINKS), "cacheFolder").isFalse();
			requireThat(entries(scratch), "scratchEntries").isEmpty();
		}
	}

	/**
	 * A process with administrative rights does not run the plugin's start-up program, and leaves the plugin without a
	 * startup cache, removing the cache of an earlier activation.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void elevatedProcessLeavesNoCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-elevated-")).toRealPath();
			Path report = root.resolve("report.properties");
			Path plugin = fakePlugin(root.resolve("plugin"), "report", report);
			Files.createDirectories(plugin.resolve(StartupCache.DIRECTORY));
			Files.writeString(plugin.resolve(StartupCache.FILE), "a cache of an earlier activation");
			// Stands in for the check of a process with administrative rights
			BooleanSupplier elevated = () -> true;

			boolean written = new StartupCacheTrainer(root.resolve("scratch"), Duration.ofMinutes(1), elevated).
				train(plugin);

			requireThat(written, "written").isFalse();
			requireThat(Files.exists(report, NOFOLLOW_LINKS), "programRan").isFalse();
			requireThat(Files.exists(plugin.resolve(StartupCache.DIRECTORY), NOFOLLOW_LINKS), "cacheFolder").isFalse();
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
			Path plugin = fakePlugin(root.resolve("plugin"), "report", root.resolve("report.properties"));
			Files.delete(plugin.resolve(StartupCache.TRAINING));

			boolean written = new StartupCacheTrainer(root.resolve("scratch"), Duration.ofMinutes(1), NOT_ELEVATED).
				train(plugin);

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
			fakePlugin(home.resolve("live"), "report", root.resolve("report.properties"));
			StartupCacheTrainer trainer = new StartupCacheTrainer(root.resolve("scratch"), Duration.ofMinutes(1),
				NOT_ELEVATED);
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
			fakePlugin(home.resolve("live"), "fail", root.resolve("report.properties"));
			UpdateResult installed = new UpdateResult(Result.INSTALLED, "harness-a", Optional.of("1.1"),
				Optional.empty(), "CAT was installed.", List.of(), true, List.of());

			UpdateResult trained = new StartupCacheTrainer(root.resolve("scratch"), Duration.ofMinutes(1), NOT_ELEVATED).
				afterActivation(installed, new LiveProvider(), home);

			requireThat(trained, "trained").isEqualTo(installed.withAotCache(false));
		}
	}

	/**
	 * Creates a fixture plugin whose {@code java} wrapper runs real Java with the plugin's {@link StartupProgram}.
	 *
	 * @param plugin the plugin directory, which must not exist
	 * @return the plugin directory
	 * @throws IOException if the fixture cannot be created
	 */
	private static Path javaPlugin(Path plugin) throws IOException
	{
		return plugin(plugin, StartupProgram.class, Optional.empty(), List.of(StartupProgram.class.getName()));
	}

	/**
	 * Creates a fixture plugin whose {@code java} wrapper runs {@link FakeJava}.
	 *
	 * @param plugin   the plugin directory, which must not exist
	 * @param behavior what the start-up program does
	 * @param report   the file that the start-up program writes its observations to
	 * @return the plugin directory
	 * @throws IOException if the fixture cannot be created
	 */
	private static Path fakePlugin(Path plugin, String behavior, Path report) throws IOException
	{
		// The fake receives every argument, including the cache options, as a program receives its arguments
		return plugin(plugin, FakeJava.class, Optional.of(FakeJava.class.getName()),
			List.of("-Dfixture.behavior=" + behavior, "-Dfixture.report=" + report,
				"-Dfixture.variables=HOME,TMPDIR,GIT_CEILING_DIRECTORIES," + ROOT_VARIABLE,
				"-Dfixture.properties=user.dir"));
	}

	/**
	 * Creates a fixture plugin: a jar of one program in its {@code client/modules} folder, a {@code client/bin/java}
	 * wrapper that runs the testing JVM's {@code java} with that jar as the class path, and a training description.
	 *
	 * @param plugin    the plugin directory, which must not exist
	 * @param program   the program, a class without nested classes
	 * @param main      the main class that the wrapper names before the arguments that it receives, if any
	 * @param arguments the arguments that the training description gives the wrapper after the startup cache options
	 * @return the plugin directory
	 * @throws IOException if the fixture cannot be created
	 */
	private static Path plugin(Path plugin, Class<?> program, Optional<String> main, List<String> arguments)
		throws IOException
	{
		String mainArgument = main.map(name -> " " + name).orElse("");
		Path client = Files.createDirectories(plugin.resolve("client"));
		Path modules = Files.createDirectories(client.resolve("modules"));
		String entry = program.getName().replace('.', '/') + ".class";
		try (InputStream in = program.getResourceAsStream(program.getSimpleName() + ".class");
			OutputStream out = Files.newOutputStream(modules.resolve("fixture.jar"));
			JarOutputStream jar = new JarOutputStream(out))
		{
			requireThat(in, "programClass").isNotNull();
			jar.putNextEntry(new JarEntry(entry));
			in.transferTo(jar);
			jar.closeEntry();
		}
		Path bin = Files.createDirectories(client.resolve("bin"));
		String java = tool(Path.of(System.getProperty("java.home")), "java");
		if (Platform.current().isWindows())
		{
			Files.writeString(bin.resolve("java.cmd"), "@echo off\r\n" +
				"for %%I in (\"%~dp0..\") do set \"CLIENT=%%~fI\"\r\n" +
				"\"" + java + "\" -cp \"%CLIENT%\\modules\\fixture.jar\"" + mainArgument + " %*\r\n");
		}
		else
		{
			Path wrapper = Files.writeString(bin.resolve("java"), "#!/bin/sh\n" +
				"client=$(CDPATH= cd -P -- \"$(dirname -- \"$0\")/..\" && pwd -P)\n" +
				"exec '" + java + "' -cp \"$client/modules/fixture.jar\"" + mainArgument + " \"$@\"\n");
			requireThat(wrapper.toFile().setExecutable(true), "executable").isTrue();
		}
		Map<String, Object> description = Map.of("format", 1, "arguments", arguments, "plugin_root_variable",
			ROOT_VARIABLE, "input", INPUT);
		Files.writeString(plugin.resolve(StartupCache.TRAINING), JsonMapper.builder().build().
			writeValueAsString(description), UTF_8);
		return plugin;
	}

	/**
	 * Runs the fixture plugin's start-up program with the plugin's startup cache, requiring Java to use the cache.
	 *
	 * @param plugin the fixture plugin, whose wrapper runs real Java
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
			"-XX:AOTMode=on", "-XX:AOTCache=" + plugin.resolve(StartupCache.FILE), StartupProgram.class.getName()).
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
			return standardError.strip();
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
	 * Indicates whether an observed path lies in a directory.
	 *
	 * @param observed  what the fixture program observed
	 * @param name      the name of the observed path
	 * @param directory the directory
	 * @return {@code true} if the observed path is {@code directory} or below it
	 */
	private static boolean isIn(Properties observed, String name, Path directory)
	{
		return Path.of(observed.getProperty(name)).startsWith(directory);
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
