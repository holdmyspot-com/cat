package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.engine.test.StartupCacheFixtures.INPUT;
import static com.holdmyspot.cat.common.installer.engine.test.StartupCacheFixtures.ROOT_VARIABLE;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installer.engine.LinkSafeDeletion;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer.Training;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer.TrainingRun;
import com.holdmyspot.cat.common.installer.fakes.test.StartupTrainingApplication;
import com.holdmyspot.cat.common.installer.secrets.StartupCacheTrainerSecrets;
import com.holdmyspot.cat.common.runner.StartupCache;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests writing a plugin directory's startup cache after the plugin directory is activated.
 * <p>
 * What a training runs and what it does with a run's outcome are decisions, so the tests call the methods that make
 * them. The only claim about real Java, that it accepts a cache which a training wrote through a plugin's wrapper, is
 * checked by the distribution module's {@code jlink-launchers.bats} and {@code codex-plugin-launchers-windows.bats},
 * which run the wrapper of the plugins that the build generates.
 */
public final class StartupCacheTrainerTest
{
	/**
	 * Stands in for the check of a process without administrative rights.
	 */
	private static final BooleanSupplier NOT_ELEVATED = () -> false;
	/**
	 * Supplies non-blocking pauses while tests exercise the cleanup decisions.
	 */
	private static final Waiting NO_WAIT = interval -> () ->
	{
	};

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

			StartupCacheTrainer.cleanUp(false, cacheFolder, work.getParent(), NO_WAIT);

			requireThat(Files.exists(cacheFolder, NOFOLLOW_LINKS), "cacheFolder").isFalse();
			requireThat(Files.exists(work.getParent(), NOFOLLOW_LINKS), "scratchFolder").isFalse();
		}
	}

	/**
	 * Final cleanup retries an eligible temporary refusal through the supplied tree-removal operation.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void cleanupRetriesTransientRefusals() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-retry-")).toRealPath();
			Path cache = Files.createDirectories(root.resolve("plugin").resolve(StartupCache.DIRECTORY));
			Path work = Files.createDirectories(root.resolve("scratch/training-1/tmp")).getParent();
			AtomicBoolean refuseCacheOnce = new AtomicBoolean(true);
			AtomicBoolean refuseScratchOnce = new AtomicBoolean(true);
			List<Path> attempts = new ArrayList<>();
			StartupCacheTrainer.TreeDeleter deleteTree = path ->
			{
				attempts.add(path);
				boolean refuseFirstCacheAttempt = path.equals(cache) && refuseCacheOnce.getAndSet(false);
				boolean refuseFirstScratchAttempt = path.equals(work) && refuseScratchOnce.getAndSet(false);
				if (refuseFirstCacheAttempt || refuseFirstScratchAttempt)
					throw new AccessDeniedException(path.toString());
				TemporaryDirectories.deleteRecursively(path);
			};

			StartupCacheTrainerSecrets.getStartupCacheTrainerAccess().
				cleanUp(false, cache, work, deleteTree, NO_WAIT);

			requireThat(Files.exists(cache, NOFOLLOW_LINKS), "cacheFolder").isFalse();
			requireThat(Files.exists(work, NOFOLLOW_LINKS), "scratchFolder").isFalse();
			requireThat(attempts.stream().filter(cache::equals).count(), "cacheAttempts").isEqualTo(2L);
			requireThat(attempts.stream().filter(work::equals).count(), "scratchAttempts").isEqualTo(2L);
		}
	}

	/**
	 * Persistent eligible refusals exhaust a fresh retry schedule for each root, while cache failure remains primary.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void cleanupExhaustsRetriesBothRoots() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-retry-exhausted-")).toRealPath();
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path work = Files.createDirectories(root.resolve("scratch"));
			AccessDeniedException cacheFailure = new AccessDeniedException(cache.toString());
			AccessDeniedException scratchFailure = new AccessDeniedException(work.toString());
			List<Path> attempts = new ArrayList<>();
			List<Duration> waits = new ArrayList<>();
			Waiting recording = interval -> () -> waits.add(interval);

			IOException failure = cleanupFailure(cache, work, path ->
			{
				attempts.add(path);
				if (path.equals(cache))
					throw cacheFailure;
				throw scratchFailure;
			}, recording);

			requireThat(failure, "failure").isEqualTo(cacheFailure);
			requireThat(List.of(failure.getSuppressed()), "suppressed").contains(scratchFailure);
			requireThat(attempts.stream().filter(cache::equals).count(), "cacheAttempts").isEqualTo(9L);
			requireThat(attempts.stream().filter(work::equals).count(), "scratchAttempts").isEqualTo(9L);
			requireThat(waits.size(), "waits").isEqualTo(16);
			requireThat(waits.stream().reduce(Duration.ZERO, Duration::plus), "totalWait").
				isEqualTo(LinkSafeDeletion.RETRY_DEADLINE.multipliedBy(2));
		}
	}

	/**
	 * A generic I/O failure is reported immediately and does not prevent scratch removal.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void cleanupDoesNotRetryGenericIoFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-retry-generic-")).toRealPath();
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path work = Files.createDirectories(root.resolve("scratch"));
			IOException expected = new IOException("cache read failed");
			List<Duration> waits = new ArrayList<>();
			Waiting recording = interval -> () -> waits.add(interval);

			IOException failure = cleanupFailure(cache, work, path ->
			{
				if (path.equals(cache))
					throw expected;
				TemporaryDirectories.deleteRecursively(path);
			}, recording);

			requireThat(failure, "failure").isEqualTo(expected);
			requireThat(waits, "waits").isEmpty();
			requireThat(Files.isDirectory(cache, NOFOLLOW_LINKS), "cache").isTrue();
			requireThat(Files.exists(work, NOFOLLOW_LINKS), "scratch").isFalse();
		}
	}

	/**
	 * A pause failure without interruption is reported and does not prevent the other required tree removal.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void cleanupReportsNonInterruptPauseFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-retry-pause-failure-")).toRealPath();
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path work = Files.createDirectories(root.resolve("scratch"));
			AccessDeniedException deletionFailure = new AccessDeniedException(cache.toString());
			IOException pauseFailure = new IOException("pause failed");
			List<Path> attempts = new ArrayList<>();
			Waiting failing = interval -> () ->
			{
				throw pauseFailure;
			};

			IOException failure = cleanupFailure(cache, work, path ->
			{
				attempts.add(path);
				if (path.equals(cache))
					throw deletionFailure;
				TemporaryDirectories.deleteRecursively(path);
			}, failing);

			requireThat(failure, "failure").isEqualTo(pauseFailure);
			requireThat(attempts.stream().filter(cache::equals).count(), "cacheAttempts").isEqualTo(1L);
			requireThat(attempts.stream().filter(work::equals).count(), "scratchAttempts").isEqualTo(1L);
			requireThat(Files.exists(work, NOFOLLOW_LINKS), "scratch").isFalse();
		}
	}

	/**
	 * Cleanup temporarily clears an existing interruption so both successful and failed cleanup can finish, then
	 * restores the caller's interruption status.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void cleanupRestoresInterruptStatus() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-retry-interrupted-")).toRealPath();
			Path successfulCache = Files.createDirectories(root.resolve("successful-cache"));
			Path successfulWork = Files.createDirectories(root.resolve("successful-scratch"));
			AtomicBoolean refuseCacheOnce = new AtomicBoolean(true);
			List<Path> successfulAttempts = new ArrayList<>();
			List<Duration> successfulWaits = new ArrayList<>();
			Waiting verifyClearedInterruption = interval -> () ->
			{
				requireThat(Thread.currentThread().isInterrupted(), "interruptedDuringPause").isFalse();
				successfulWaits.add(interval);
			};
			try
			{
				Thread.currentThread().interrupt();
				StartupCacheTrainerSecrets.getStartupCacheTrainerAccess().cleanUp(false, successfulCache,
					successfulWork, path ->
					{
						successfulAttempts.add(path);
						if (path.equals(successfulCache) && refuseCacheOnce.getAndSet(false))
							throw new AccessDeniedException(path.toString());
						TemporaryDirectories.deleteRecursively(path);
					}, verifyClearedInterruption);
				requireThat(Thread.currentThread().isInterrupted(), "interruptedAfterSuccess").isTrue();
				requireThat(Files.exists(successfulCache, NOFOLLOW_LINKS), "successfulCache").isFalse();
				requireThat(Files.exists(successfulWork, NOFOLLOW_LINKS), "successfulWork").isFalse();
				requireThat(successfulAttempts.stream().filter(successfulCache::equals).count(), "cacheAttempts").
					isEqualTo(2L);
				requireThat(successfulAttempts.stream().filter(successfulWork::equals).count(), "scratchAttempts").
					isEqualTo(1L);
				requireThat(successfulWaits.size(), "pauseCount").isEqualTo(1);
			}
			finally
			{
				Thread.interrupted();
			}

			Path failedCache = Files.createDirectories(root.resolve("failed-cache"));
			Path failedWork = Files.createDirectories(root.resolve("failed-scratch"));
			IOException expected = new IOException("cache removal failed");
			try
			{
				Thread.currentThread().interrupt();
				IOException failure = cleanupFailure(failedCache, failedWork, path ->
				{
					if (path.equals(failedCache))
						throw expected;
					TemporaryDirectories.deleteRecursively(path);
				}, NO_WAIT);
				requireThat(failure, "failure").isEqualTo(expected);
				requireThat(Thread.currentThread().isInterrupted(), "interruptedAfterFailure").isTrue();
				requireThat(Files.exists(failedWork, NOFOLLOW_LINKS), "scratch").isFalse();
			}
			finally
			{
				Thread.interrupted();
			}
		}
	}

	/**
	 * A new interruption during a retry pause stops that root's retry, remains set, and does not prevent the other root
	 * from being cleaned.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void cleanupPauseInterruptKeepsFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-retry-new-interrupt-")).toRealPath();
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path work = Files.createDirectories(root.resolve("scratch"));
			AccessDeniedException expected = new AccessDeniedException(cache.toString());
			IOException pauseFailure = new IOException("pause interrupted");
			List<Path> attempts = new ArrayList<>();
			Waiting interrupting = interval -> () ->
			{
				Thread.currentThread().interrupt();
				throw pauseFailure;
			};

			try
			{
				IOException failure = cleanupFailure(cache, work, path ->
				{
					attempts.add(path);
					if (path.equals(cache))
						throw expected;
					TemporaryDirectories.deleteRecursively(path);
				}, interrupting);
				requireThat(failure, "failure").isEqualTo(expected);
				requireThat(Thread.currentThread().isInterrupted(), "interrupted").isTrue();
				requireThat(attempts.stream().filter(cache::equals).count(), "cacheAttempts").isEqualTo(1L);
				requireThat(attempts.stream().filter(work::equals).count(), "scratchAttempts").isEqualTo(1L);
				requireThat(Files.exists(work, NOFOLLOW_LINKS), "scratch").isFalse();
				requireThat(Files.exists(cache, NOFOLLOW_LINKS), "cache").isTrue();
				requireThat(List.of(failure.getSuppressed()), "suppressed").containsExactly(new Throwable[] {pauseFailure});
			}
			finally
			{
				Thread.interrupted();
			}
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

			StartupCacheTrainer.cleanUp(true, cache.getParent(), work, NO_WAIT);

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

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMinutes(1), NOT_ELEVATED, NO_WAIT).train(plugin);

			requireThat(written, "written").isFalse();
			requireThat(Files.exists(plugin.resolve(StartupCache.DIRECTORY), NOFOLLOW_LINKS), "cacheFolder").isFalse();
			requireThat(entries(scratch), "scratchEntries").isEmpty();
		}
	}

	/**
	 * On Linux, a training writes the cache through a {@code java} wrapper that the installer has just extracted once
	 * no process holds the wrapper open for writing any more, although the wrapper's first start is refused while one
	 * does.
	 * <p>
	 * The JVM opens files without close-on-exec, so a process that the installer starts while it extracts the wrapper
	 * inherits the open file until it closes the descriptors that it inherited. A write channel that the test keeps
	 * open stands in for that process, and the pause between start attempts closes it, as the inherited descriptor
	 * would close during the retries. The wrapper stands in for Java: it writes the file that each run's options name.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void trainsOnceWrapperWriterCloses() throws IOException
	{
		if (!Platform.current().isLinux())
			throw new SkipException("Only Linux refuses to run a file that a process holds open for writing");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-busy-")).toRealPath();
			Path plugin = describedPlugin(root.resolve("plugin"));
			Path wrapper = Files.writeString(Files.createDirectories(plugin.resolve("bin")).resolve("java"), """
				#!/bin/sh
				for option; do
					case "$option" in
						-XX:AOTConfiguration=*) configuration=${option#*=} ;;
						-XX:AOTCache=*) cache=${option#*=} ;;
					esac
				done
				if [ -n "$cache" ]; then : > "$cache"; else : > "$configuration"; fi
				""");
			Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
			try (FileChannel inherited = FileChannel.open(wrapper, StandardOpenOption.WRITE))
			{
				Waiting closing = _ -> inherited::close;

				boolean written = new StartupCacheTrainer(root.resolve("scratch"), Duration.ofMinutes(1), NOT_ELEVATED,
					closing).train(plugin);

				requireThat(written, "written").isTrue();
				requireThat(Files.isRegularFile(plugin.resolve(StartupCache.FILE), NOFOLLOW_LINKS), "cache").isTrue();
				// Only a refused start pauses, so a closed channel shows that the first start was refused
				requireThat(inherited.isOpen(), "inherited.isOpen").isFalse();
			}
		}
	}

	/**
	 * Interrupting a training after its fixture process publishes its ID and is observed running stops that process and
	 * leaves the plugin without a startup cache. A separate test application owns this real process boundary.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 * @throws InterruptedException if the test thread is interrupted while waiting for the fixture
	 */
	@Test
	public void stoppedTrainingLeavesNoCache() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-stops-")).toRealPath();
			Path plugin = javaPlugin(root.resolve("plugin"));
			Path scratch = root.resolve("scratch");
			Path readyPath = root.resolve("fixture-process.pid");
			Files.writeString(plugin.resolve(StartupCache.TRAINING),
				StartupCacheFixtures.description(List.of(StartupProgram.class.getName(), "--hold", readyPath.toString())),
				UTF_8);
			StartupTrainingApplication.cancel(plugin, scratch, readyPath);

			requireThat(Files.exists(plugin.resolve(StartupCache.DIRECTORY), NOFOLLOW_LINKS), "cacheFolder").isFalse();
			requireThat(entries(scratch), "scratchEntries").isEmpty();
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

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMinutes(1), elevated, NO_WAIT).train(plugin);

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

			boolean written = new StartupCacheTrainer(scratch, Duration.ofMinutes(1), NOT_ELEVATED, NO_WAIT).train(plugin);

			requireThat(written, "written").isFalse();
			requireThat(Files.exists(scratch, NOFOLLOW_LINKS), "scratchMade").isFalse();
			requireThat(Files.exists(plugin.resolve(StartupCache.DIRECTORY), NOFOLLOW_LINKS), "cacheFolder").isFalse();
		}
	}

	/**
	 * A malformed training description fails with its filename and parser cause instead of silently disabling training.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void malformedDescriptionReportsFileAndCause() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-malformed-"));
			Path plugin = root.resolve("plugin");
			Path description = plugin.resolve(StartupCache.TRAINING);
			Files.createDirectories(description.getParent());
			Files.writeString(description, "{", UTF_8);
			StartupCacheTrainer trainer = trainer(root.resolve("scratch"), Files::deleteIfExists, NO_WAIT);

			IOException failure = trainFailure(trainer, plugin);

			requireThat(failure.getMessage(), "message").contains(description.toString());
			requireThat(failure.getCause(), "cause").isNotNull();
		}
	}

	/**
	 * A training description with an invalid required member fails with its filename and the invalid predicate.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void invalidMemberReportsFileAndPredicate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-invalid-member-"));
			Path plugin = root.resolve("plugin");
			Path description = plugin.resolve(StartupCache.TRAINING);
			Files.createDirectories(description.getParent());
			Files.writeString(description, "{\"format\": 1, \"arguments\": [], \"plugin_root_variable\": " +
				"\"PLUGIN_ROOT\", \"input\": \"\"}", UTF_8);
			StartupCacheTrainer trainer = trainer(root.resolve("scratch"), Files::deleteIfExists, NO_WAIT);

			IOException failure = trainFailure(trainer, plugin);

			requireThat(failure.getMessage(), "message").contains(description.toString());
			requireThat(failure.getMessage(), "message").contains("non-empty array of strings");
		}
	}

	/**
	 * Failure to remove the existing cache stops training before it creates scratch state.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void initialCacheRemovalFailureStopsTraining() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-initial-delete-"));
			Path plugin = describedPlugin(root.resolve("plugin"));
			Path cache = Files.createDirectories(plugin.resolve(StartupCache.DIRECTORY));
			Path previousCache = Files.writeString(plugin.resolve(StartupCache.FILE), "previous cache");
			IOException expected = new IOException("initial cache removal failed");
			StartupCacheTrainer trainer = trainer(root.resolve("scratch"), path ->
			{
				throw expected;
			}, NO_WAIT);

			IOException failure = trainFailure(trainer, plugin);

			requireThat(failure, "failure").isEqualTo(expected);
			requireThat(Files.isDirectory(cache, NOFOLLOW_LINKS), "cache").isTrue();
			requireThat(Files.readString(previousCache), "previousCache").isEqualTo("previous cache");
			requireThat(Files.exists(root.resolve("scratch"), NOFOLLOW_LINKS), "scratch").isFalse();
		}
	}

	/**
	 * Failure to remove the cache after an undescribed plugin is reported, even when the cache folder is absent.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void finalCacheRemovalFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-final-delete-"));
			Path plugin = root.resolve("plugin");
			Files.createDirectories(plugin);
			IOException expected = new IOException("final cache removal failed");
			AtomicBoolean initialRemovalCompleted = new AtomicBoolean();
			StartupCacheTrainer trainer = trainer(root.resolve("scratch"), path ->
			{
				if (initialRemovalCompleted.compareAndSet(false, true))
					Files.deleteIfExists(path);
				else
					throw expected;
			}, NO_WAIT);

			IOException failure = trainFailure(trainer, plugin);

			requireThat(failure, "failure").isEqualTo(expected);
		}
	}

	/**
	 * Cache cleanup failure does not prevent scratch cleanup, and both failures remain available to the caller.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void cleanupReportsCacheAndScratchFailures() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-cleanup-errors-"));
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path work = Files.createDirectories(root.resolve("scratch"));
			IOException cacheFailure = new IOException("cache removal failed");
			IOException scratchFailure = new IOException("scratch removal failed");

			IOException failure = cleanupFailure(cache, work, path ->
			{
				if (path.equals(cache))
					throw cacheFailure;
				throw scratchFailure;
			}, NO_WAIT);

			requireThat(failure, "failure").isEqualTo(cacheFailure);
			requireThat(List.of(failure.getSuppressed()), "suppressed").contains(scratchFailure);
			requireThat(Files.isDirectory(cache, NOFOLLOW_LINKS), "cache").isTrue();
			requireThat(Files.isDirectory(work, NOFOLLOW_LINKS), "scratch").isTrue();
		}
	}

	/**
	 * Scratch cleanup failure is reported after the cache has been removed.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void scratchFailureFollowsCacheRemoval() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("startup-cache-scratch-delete-"));
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path work = Files.createDirectories(root.resolve("scratch"));
			IOException expected = new IOException("scratch removal failed");

			IOException failure = cleanupFailure(cache, work, path ->
			{
				if (path.equals(cache))
					Files.delete(path);
				else
					throw expected;
			}, NO_WAIT);

			requireThat(failure, "failure").isEqualTo(expected);
			requireThat(Files.exists(cache, NOFOLLOW_LINKS), "cache").isFalse();
			requireThat(Files.isDirectory(work, NOFOLLOW_LINKS), "scratch").isTrue();
		}
	}

	/**
	 * Creates a trainer whose tree removal is controlled by its test.
	 *
	 * @param scratch    the trainer's scratch directory
	 * @param deleteTree the test's tree-removal operation
	 * @param waiting    controls pauses between attempts
	 * @return the trainer
	 */
	private static StartupCacheTrainer trainer(Path scratch, StartupCacheTrainer.TreeDeleter deleteTree,
		Waiting waiting)
	{
		return StartupCacheTrainerSecrets.getStartupCacheTrainerAccess().create(scratch, Duration.ofMinutes(1),
			NOT_ELEVATED, deleteTree, waiting);
	}

	/**
	 * Returns a training failure.
	 *
	 * @param trainer the trainer
	 * @param plugin  the plugin directory
	 * @return the reported failure
	 */
	private static IOException trainFailure(StartupCacheTrainer trainer, Path plugin)
	{
		return expectThrows(IOException.class, () -> trainer.train(plugin));
	}

	/**
	 * Returns a cleanup failure.
	 *
	 * @param cache      the cache directory
	 * @param work       the scratch directory
	 * @param deleteTree the test's tree-removal operation
	 * @param waiting    controls pauses between attempts
	 * @return the reported failure
	 */
	private static IOException cleanupFailure(Path cache, Path work,
		StartupCacheTrainer.TreeDeleter deleteTree, Waiting waiting)
	{
		return expectThrows(IOException.class, () -> StartupCacheTrainerSecrets.getStartupCacheTrainerAccess().
			cleanUp(false, cache, work, deleteTree, waiting));
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
