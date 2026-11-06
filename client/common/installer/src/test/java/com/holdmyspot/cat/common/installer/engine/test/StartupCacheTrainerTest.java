package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.engine.test.StartupCacheFixtures.INPUT;
import static com.holdmyspot.cat.common.installer.engine.test.StartupCacheFixtures.ROOT_VARIABLE;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer.Training;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer.TrainingRun;
import com.holdmyspot.cat.common.runner.StartupCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests writing a plugin directory's startup cache after the plugin directory is activated.
 * <p>
 * What a training runs and what it does with a run's outcome are decisions, so the tests call the methods that make
 * them. The only claim about real Java, that it accepts a cache which a training wrote through a plugin's wrapper, is
 * checked once, by {@code PluginLaunchersTest.wrapperTrainsAndPassesStartupCache} in the distribution module, which
 * has the wrapper that plugins ship.
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
	 * A run starts the plugin's {@code java} wrapper with the run's options and then the training's arguments, in a
	 * working directory below the scratch folder, with an environment that holds the caller's program search path
	 * and nothing else of the caller's: a scratch home and temporary folder, a Git ceiling at the scratch folder, and the
	 * plugin directory in the plugin's variable. The run reads its standard input from a file in the scratch folder.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void runUsesScratchEnvironment() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(testDirectory("startup-cache-plan-")).toRealPath();
			Path plugin = base.resolve("plugin");
			Path work = base.resolve("scratch/training-1");
			Training training = new Training(List.of("-m", "app/example.Main", "--flag"), ROOT_VARIABLE, INPUT);
			Map<String, String> caller = Map.of("PATH", "/usr/bin", "HOME", "/home/caller", "SECRET", "token",
				"TMPDIR", "/tmp/caller", ROOT_VARIABLE, "/other/plugin", "GIT_CEILING_DIRECTORIES", "/caller");

			TrainingRun run = StartupCacheTrainer.plan(plugin, training, work, List.of("-XX:AOTMode=record"), caller,
				Platform.LINUX_X64);

			requireThat(run.command(), "command").isEqualTo(List.of(plugin.resolve("bin/java").toString(),
				"-XX:AOTMode=record", "-m", "app/example.Main", "--flag"));
			requireThat(run.environment(), "environment").isEqualTo(Map.of("PATH", "/usr/bin",
				"HOME", work.resolve("home").toString(),
				"TMPDIR", work.resolve("tmp").toString(),
				"TMP", work.resolve("tmp").toString(),
				"TEMP", work.resolve("tmp").toString(),
				"GIT_CEILING_DIRECTORIES", work.toString(),
				ROOT_VARIABLE, plugin.toString()));
			requireThat(run.directory(), "directory").isEqualTo(work.resolve("project"));
			requireThat(run.input(), "input").isEqualTo(work.resolve("input"));
			requireThat(run.directories(), "directories").
				isEqualTo(List.of(work.resolve("home"), work.resolve("tmp"), work.resolve("project")));
		}
	}

	/**
	 * On Windows, a run starts the plugin's {@code java.cmd} wrapper, and its environment also holds the variables that
	 * a Windows program needs to start, under the names that the caller gave them, and a scratch profile.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void runOnWindowsUsesBatchWrapper() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(testDirectory("startup-cache-plan-")).toRealPath();
			Path plugin = base.resolve("plugin");
			Path work = base.resolve("scratch/training-1");
			Training training = new Training(List.of("--flag"), ROOT_VARIABLE, INPUT);
			Map<String, String> caller = Map.of("Path", "C:\\Windows", "SYSTEMROOT", "C:\\Windows", "ComSpec", "cmd.exe",
				"USERPROFILE", "C:\\Users\\caller", "SECRET", "token");

			TrainingRun run = StartupCacheTrainer.plan(plugin, training, work, List.of(), caller, Platform.WINDOWS_X64);

			requireThat(run.command(), "command").
				isEqualTo(List.of(plugin.resolve("bin/java.cmd").toString(), "--flag"));
			requireThat(run.environment(), "environment").isEqualTo(Map.ofEntries(
				Map.entry("Path", "C:\\Windows"),
				Map.entry("SYSTEMROOT", "C:\\Windows"),
				Map.entry("ComSpec", "cmd.exe"),
				Map.entry("HOME", work.resolve("home").toString()),
				Map.entry("USERPROFILE", work.resolve("home").toString()),
				Map.entry("LOCALAPPDATA", work.resolve("home/AppData/Local").toString()),
				Map.entry("TMPDIR", work.resolve("tmp").toString()),
				Map.entry("TMP", work.resolve("tmp").toString()),
				Map.entry("TEMP", work.resolve("tmp").toString()),
				Map.entry("GIT_CEILING_DIRECTORIES", work.toString()),
				Map.entry(ROOT_VARIABLE, plugin.toString())));
		}
	}

	/**
	 * A run counts only if it ended within the time limit with status 0 and wrote the file that it is to write.
	 */
	@Test
	public void runNeedsTimelyStatusZeroAndOutput()
	{
		requireThat(StartupCacheTrainer.succeeded(OptionalInt.of(0), true), "ended").isTrue();
		requireThat(StartupCacheTrainer.succeeded(OptionalInt.of(1), true), "failed").isFalse();
		requireThat(StartupCacheTrainer.succeeded(OptionalInt.empty(), true), "timedOut").isFalse();
		requireThat(StartupCacheTrainer.succeeded(OptionalInt.of(0), false), "wroteNothing").isFalse();
	}

	/**
	 * A training that did not write the cache, such as one whose cache-writing run failed after writing part of the
	 * cache, leaves the plugin without a startup cache folder or partial file, removing the cache of an earlier
	 * activation, and removes its scratch folder.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void failedTrainingLeavesNoCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-fails-")).toRealPath();
			Path cacheFolder = root.resolve("plugin").resolve(StartupCache.DIRECTORY);
			Files.createDirectories(cacheFolder);
			Files.writeString(cacheFolder.resolve(StartupCache.FILE.getFileName() + ".partial"), "part of a cache");
			Path work = Files.createDirectories(root.resolve("scratch/training-1/tmp"));

			StartupCacheTrainer.cleanUp(false, cacheFolder, work.getParent());

			requireThat(Files.exists(cacheFolder, NOFOLLOW_LINKS), "cacheFolder").isFalse();
			requireThat(Files.exists(work.getParent(), NOFOLLOW_LINKS), "scratchFolder").isFalse();
		}
	}

	/**
	 * A training that wrote the cache keeps it and removes its scratch folder.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void writtenCacheIsKept() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-written-")).toRealPath();
			Path cache = root.resolve("plugin").resolve(StartupCache.FILE);
			Files.createDirectories(cache.getParent());
			Files.writeString(cache, "a cache");
			Path work = Files.createDirectories(root.resolve("scratch/training-1/tmp")).getParent();

			StartupCacheTrainer.cleanUp(true, cache.getParent(), work);

			requireThat(Files.readString(cache), "cache").isEqualTo("a cache");
			requireThat(Files.exists(work, NOFOLLOW_LINKS), "scratchFolder").isFalse();
		}
	}

	/**
	 * A plugin whose {@code java} wrapper cannot be started gets no startup cache, and an earlier activation's cache
	 * is removed.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void unstartableProgramLeavesNoCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-unstartable-")).toRealPath();
			Path plugin = describedPlugin(root.resolve("plugin"));
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
	 * <p>
	 * The program is real Java, which cannot end in one millisecond.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void stoppedTrainingLeavesNoCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-stops-")).toRealPath();
			Path plugin = javaPlugin(root.resolve("plugin"));
			Path scratch = root.resolve("scratch");

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMillis(1), NOT_ELEVATED).train(plugin);

			requireThat(written, "written").isFalse();
			requireThat(Files.exists(plugin.resolve(StartupCache.DIRECTORY), NOFOLLOW_LINKS), "cacheFolder").isFalse();
			requireThat(entries(scratch), "scratchEntries").isEmpty();
			String jar = plugin.resolve(StartupCacheFixtures.JAR).toString();
			List<ProcessHandle> running = ProcessHandle.allProcesses().
				filter(process -> process.info().commandLine().map(line -> line.contains(jar)).orElse(false)).toList();
			requireThat(running, "stillRunning").isEmpty();
		}
	}

	/**
	 * A process with administrative rights does not run the plugin's start-up program, and leaves the plugin without a
	 * startup cache, removing the cache of an earlier activation.
	 * <p>
	 * A training makes its scratch folder before it runs anything, so a scratch folder that was never made shows that
	 * nothing ran.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void elevatedProcessLeavesNoCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-elevated-")).toRealPath();
			Path plugin = describedPlugin(root.resolve("plugin"));
			Files.createDirectories(plugin.resolve(StartupCache.DIRECTORY));
			Files.writeString(plugin.resolve(StartupCache.FILE), "a cache of an earlier activation");
			Path scratch = root.resolve("scratch");
			// Stands in for the check of a process with administrative rights
			BooleanSupplier elevated = () -> true;

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMinutes(1), elevated).train(plugin);

			requireThat(written, "written").isFalse();
			requireThat(Files.exists(scratch, NOFOLLOW_LINKS), "scratchMade").isFalse();
			requireThat(Files.exists(plugin.resolve(StartupCache.DIRECTORY), NOFOLLOW_LINKS), "cacheFolder").isFalse();
		}
	}

	/**
	 * A plugin that does not describe how to train its startup cache gets none, and an earlier activation's cache is
	 * removed.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void undescribedPluginGetsNoCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-undescribed-")).toRealPath();
			Path plugin = describedPlugin(root.resolve("plugin"));
			Files.delete(plugin.resolve(StartupCache.TRAINING));
			Files.createDirectories(plugin.resolve(StartupCache.DIRECTORY));
			Files.writeString(plugin.resolve(StartupCache.FILE), "a cache of an earlier activation");
			Path scratch = root.resolve("scratch");

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMinutes(1), NOT_ELEVATED).train(plugin);

			requireThat(written, "written").isFalse();
			requireThat(Files.exists(scratch, NOFOLLOW_LINKS), "scratchMade").isFalse();
			requireThat(Files.exists(plugin.resolve(StartupCache.DIRECTORY), NOFOLLOW_LINKS), "cacheFolder").isFalse();
		}
	}

	/**
	 * Creates a plugin that has a training description and no {@code java} wrapper.
	 *
	 * @param plugin the plugin directory, which must not exist
	 * @return the plugin directory
	 * @throws IOException if the fixture cannot be created
	 */
	private static Path describedPlugin(Path plugin) throws IOException
	{
		Files.createDirectories(plugin.resolve(StartupCache.TRAINING).getParent());
		Files.writeString(plugin.resolve(StartupCache.TRAINING),
			StartupCacheFixtures.description(List.of("--flag")), UTF_8);
		return plugin;
	}

	/**
	 * Creates a plugin, bound to the testing JVM's runtime, whose {@code java} wrapper runs real Java with the
	 * {@link StartupProgram} in a jar as the class path.
	 *
	 * @param plugin the plugin directory, which must not exist
	 * @return the plugin directory
	 * @throws IOException if the fixture cannot be created
	 */
	private static Path javaPlugin(Path plugin) throws IOException
	{
		Path bin = Files.createDirectories(plugin.resolve("bin"));
		Files.createDirectories(bin.resolve("modules"));
		Files.write(plugin.resolve(StartupCacheFixtures.JAR), StartupCacheFixtures.jar(StartupProgram.class));
		if (Platform.current().isWindows())
			Files.writeString(bin.resolve("java.cmd"), StartupCacheFixtures.windowsWrapper());
		else
		{
			Path wrapper = Files.writeString(bin.resolve("java"), StartupCacheFixtures.posixWrapper());
			requireThat(wrapper.toFile().setExecutable(true), "executable").isTrue();
		}
		Files.writeString(bin.resolve("runtime-binding"), Path.of(System.getProperty("java.home")) + "\n", UTF_8);
		Files.writeString(plugin.resolve(StartupCache.TRAINING),
			StartupCacheFixtures.description(List.of(StartupProgram.class.getName())), UTF_8);
		return plugin;
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
