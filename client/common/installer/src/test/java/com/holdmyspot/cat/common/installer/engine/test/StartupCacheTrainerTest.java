package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.engine.test.StartupCacheFixtures.INPUT;
import static com.holdmyspot.cat.common.installer.engine.test.StartupCacheFixtures.ROOT_VARIABLE;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.StartupCache;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests writing a plugin directory's startup cache after the plugin directory is activated.
 * <p>
 * Each fixture plugin has a {@code bin/java} wrapper that runs the {@code java} of the runtime that the
 * plugin's binding names, as a plugin's wrapper does. One test runs real Java: its plugin is bound to a staged copy of
 * the testing JVM's runtime and its wrapper runs that runtime's {@code java} with the plugin's {@link StartupProgram}
 * jar. The other tests concern how training reacts to a run's outcome, so their wrapper runs
 * {@link FakeJava}, which reacts to the cache options as Java documents them but records and writes nothing real.
 */
public final class StartupCacheTrainerTest
{
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
	 * A startup cache that real Java trains with a staged runtime, as an update trains the plugin that it activates with
	 * the runtime that it stages, is accepted by Java through the plugin's wrapper once the staged runtime is renamed
	 * into place and the plugin is bound to it there. The training removes its scratch folders.
	 *
	 * @throws IOException          if the fixture cannot be created or inspected
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void stagedRuntimeCacheIsAccepted() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-trains-")).toRealPath();
			Path staged = linkedRuntime(root.resolve("data/runtime.next"));
			Path plugin = javaPlugin(root.resolve("plugin"), staged);
			Path scratch = root.resolve("scratch");

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMinutes(1), NOT_ELEVATED).train(plugin);
			// The stable launcher renames the staged runtime into place after the installer exits
			Path installed = Files.move(staged, staged.resolveSibling("runtime"));
			bind(plugin, installed);

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
	 * Creates a fixture plugin whose {@code java} wrapper runs real Java with the plugin's {@link StartupProgram}.
	 *
	 * @param plugin  the plugin directory, which must not exist
	 * @param runtime the Java runtime that the plugin is bound to
	 * @return the plugin directory
	 * @throws IOException if the fixture cannot be created
	 */
	private static Path javaPlugin(Path plugin, Path runtime) throws IOException
	{
		return plugin(plugin, runtime, StartupProgram.class, "", List.of(StartupProgram.class.getName()));
	}

	/**
	 * Creates a fixture plugin, bound to the testing JVM's runtime, whose {@code java} wrapper runs {@link FakeJava}.
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
		return plugin(plugin, Path.of(System.getProperty("java.home")), FakeJava.class, FakeJava.class.getName(),
			List.of("-Dfixture.behavior=" + behavior, "-Dfixture.report=" + report,
				"-Dfixture.variables=HOME,TMPDIR,GIT_CEILING_DIRECTORIES," + ROOT_VARIABLE,
				"-Dfixture.properties=user.dir"));
	}

	/**
	 * Creates a fixture plugin: a jar of one program in its {@code bin/modules} folder, a {@code bin/java}
	 * wrapper that runs the bound runtime's {@code java} with that jar as the class path, the binding, and a training
	 * description.
	 *
	 * @param plugin    the plugin directory, which must not exist
	 * @param runtime   the Java runtime that the plugin is bound to
	 * @param program   the program, a class without nested classes
	 * @param main      the main class that the wrapper names before the arguments that it receives, or an empty string
	 * @param arguments the arguments that the training description gives the wrapper after the startup cache options
	 * @return the plugin directory
	 * @throws IOException if the fixture cannot be created
	 */
	private static Path plugin(Path plugin, Path runtime, Class<?> program, String main, List<String> arguments)
		throws IOException
	{
		Path bin = Files.createDirectories(plugin.resolve("bin"));
		Files.createDirectories(bin.resolve("modules"));
		Files.write(plugin.resolve(StartupCacheFixtures.JAR), StartupCacheFixtures.jar(program));
		if (Platform.current().isWindows())
			Files.writeString(bin.resolve("java.cmd"), StartupCacheFixtures.windowsWrapper(main, "java.exe"));
		else
		{
			Path wrapper = Files.writeString(bin.resolve("java"), StartupCacheFixtures.posixWrapper(main));
			requireThat(wrapper.toFile().setExecutable(true), "executable").isTrue();
		}
		bind(plugin, runtime);
		Files.writeString(plugin.resolve(StartupCache.TRAINING), StartupCacheFixtures.description(arguments), UTF_8);
		return plugin;
	}

	/**
	 * Binds a fixture plugin to a Java runtime, as an installation does.
	 *
	 * @param plugin  the plugin directory
	 * @param runtime the runtime directory
	 * @throws IOException if the binding cannot be written
	 */
	private static void bind(Path plugin, Path runtime) throws IOException
	{
		Files.writeString(plugin.resolve("bin/runtime-binding"), runtime + "\n", UTF_8);
	}

	/**
	 * Places a Java runtime at a new location by linking the files of the testing JVM's runtime, so that Java started
	 * from it reads its runtime image at that location. A file that cannot be linked, such as one on another file
	 * system, is copied.
	 *
	 * @param target the runtime directory, which must not exist
	 * @return the runtime directory
	 * @throws IOException if the runtime cannot be placed
	 */
	private static Path linkedRuntime(Path target) throws IOException
	{
		Path source = Path.of(System.getProperty("java.home")).toRealPath();
		for (String part : List.of("bin", "conf", "lib", "release"))
		{
			try (Stream<Path> paths = Files.walk(source.resolve(part)))
			{
				for (Path path : paths.toList())
				{
					Path copy = target.resolve(source.relativize(path).toString());
					if (Files.isDirectory(path, NOFOLLOW_LINKS))
						Files.createDirectories(copy);
					else
						linkOrCopy(path, copy);
				}
			}
		}
		return target;
	}

	/**
	 * Links a file of the testing JVM's runtime at another location, or copies it when it cannot be linked.
	 *
	 * @param file the file, which is never changed
	 * @param copy the new location
	 * @throws IOException if the file can be neither linked nor copied
	 */
	private static void linkOrCopy(Path file, Path copy) throws IOException
	{
		Files.createDirectories(copy.getParent());
		if (Files.isRegularFile(file, NOFOLLOW_LINKS))
		{
			try
			{
				Files.createLink(copy, file);
				return;
			}
			catch (IOException | UnsupportedOperationException _)
			{
				// Copied below
			}
		}
		Files.copy(file, copy, NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
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
		ProcessBuilder builder = new ProcessBuilder(plugin.resolve("bin").resolve(wrapper).toString(),
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
