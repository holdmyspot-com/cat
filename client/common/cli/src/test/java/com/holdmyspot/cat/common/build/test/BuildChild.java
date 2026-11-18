package com.holdmyspot.cat.common.build.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Small controllable child process for process-tree lifecycle checks.
 */
public final class BuildChild
{
	private static final String TEST_MODULE = "com.holdmyspot.cat.common.cli.test";
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * How long a fixture process may stay live without publishing its readiness before the wait for it fails.
	 * <p>
	 * A fixture's start takes longer the busier the host is, and a wait does not fail while the fixture starts, so this
	 * limit only stops a fixture that hangs.
	 */
	private static final Duration HANG_LIMIT = Duration.ofMinutes(2);
	/**
	 * How often a wait checks its condition when no file system event arrives, such as when a process ends.
	 */
	private static final Duration CHECK_INTERVAL = Duration.ofMillis(100);

	/**
	 * Prevents direct construction.
	 */
	private BuildChild()
	{
	}

	/**
	 * Starts a grandchild, publishes its PID, waits for release, and exits.
	 *
	 * @param arguments fixture directory and optional child mode
	 * @throws IOException if readiness cannot be published
	 * @throws InterruptedException if the fixture wait is interrupted
	 */
	public static void main(String[] arguments) throws IOException, InterruptedException
	{
		Path directory = Path.of(arguments[0]);
		if (arguments.length > 1 && arguments[1].equals("lock"))
		{
			Path lockFile = Path.of(arguments[2]);
			try (WatchService watch = watchFor(directory); java.nio.channels.FileChannel channel =
				java.nio.channels.FileChannel.open(lockFile, java.nio.file.StandardOpenOption.WRITE);
				java.nio.channels.FileLock ignored = channel.lock())
			{
				if (!ignored.isValid())
					throw new IOException("Fixture lock became invalid");
				Files.writeString(directory.resolve("lock.ready"), Long.toString(ProcessHandle.current().pid()));
				awaitFile(watch, directory.resolve("release"), Duration.ofDays(1));
			}
			return;
		}
		if (arguments.length > 1 && arguments[1].equals("grandchild"))
		{
			try (WatchService watch = watchFor(directory))
			{
				Files.writeString(directory.resolve("grandchild.ready"), Long.toString(ProcessHandle.current().pid()));
				awaitFile(watch, directory.resolve("release"), Duration.ofDays(1));
			}
			return;
		}
		if (arguments.length > 1 && arguments[1].equals("exit"))
		{
			Path log = Path.of(arguments[2]);
			Path ledger = Path.of(arguments[3]);
			int exitCode = Integer.parseInt(arguments[4]);
			long pid = ProcessHandle.current().pid();
			try (WatchService watch = watchFor(ledger.getParent()))
			{
				awaitCondition(watch, () -> ledgerBindsPid(ledger, pid), Duration.ofSeconds(30),
					"Controller ledger did not bind child PID " + pid);
			}
			Files.createDirectories(log.getParent());
			Files.writeString(log, "synthetic Maven diagnostic\n");
			System.exit(exitCode);
		}
		try (WatchService parentWatch = watchFor(directory))
		{
			try (Process child = new ProcessBuilder(fixtureCommand(directory.toString(), "grandchild")).start())
			{
				Files.writeString(directory.resolve("parent.ready"), Long.toString(ProcessHandle.current().pid()));
				awaitFile(parentWatch, directory.resolve("release"), Duration.ofDays(1));
				child.destroyForcibly();
				child.waitFor();
			}
		}
	}

	/**
	 * Builds a module-path command for this fixture and its descendants.
	 *
	 * @param arguments fixture main arguments
	 * @return command line
	 */
	public static List<String> fixtureCommand(String... arguments)
	{
		String modulePath = System.getProperty("jdk.module.path");
		if (modulePath == null || modulePath.isBlank())
			throw new IllegalStateException("The test JVM did not expose jdk.module.path");
		List<String> command = new ArrayList<>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		command.add("-XX:+PerfDisableSharedMem");
		command.add("--module-path");
		command.add(modulePath);
		command.add("--module");
		command.add(TEST_MODULE + "/" + BuildChild.class.getName());
		command.addAll(List.of(arguments));
		return List.copyOf(command);
	}

	/**
	 * Registers a fixture directory for creation and modification events.
	 *
	 * @param directory watched directory
	 * @return registered watcher
	 * @throws IOException if registration fails
	 */
	public static WatchService watchFor(Path directory) throws IOException
	{
		WatchService watch = directory.getFileSystem().newWatchService();
		directory.register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
		return watch;
	}

	/**
	 * Waits for a fixture path using filesystem events while checking the predicate before blocking.
	 *
	 * @param watch registered directory watcher
	 * @param path path whose existence signals readiness
	 * @param timeout maximum wait
	 * @throws IOException if the path is not published before timeout
	 * @throws InterruptedException if the wait is interrupted
	 */
	public static void awaitFile(WatchService watch, Path path, Duration timeout) throws IOException,
		InterruptedException
	{
		awaitCondition(watch, () -> Files.exists(path), timeout, "Timed out waiting for fixture path: " + path);
	}

	/**
	 * Waits until a fixture process publishes a file, failing if the process ends without publishing it.
	 * <p>
	 * The wait lasts as long as the process takes to start, however busy the host is, and fails after
	 * {@link #HANG_LIMIT} only if the process stays live without publishing.
	 *
	 * @param watch   registered watcher of the file's directory
	 * @param path    path whose existence signals readiness
	 * @param process the fixture process that publishes the file
	 * @throws IOException if the process ends, or hangs, without publishing the file
	 * @throws InterruptedException if the wait is interrupted
	 */
	public static void awaitFile(WatchService watch, Path path, Process process) throws IOException,
		InterruptedException
	{
		awaitCondition(watch, () ->
		{
			if (Files.exists(path))
				return true;
			if (process.isAlive())
				return false;
			// The process may have published the file between the two checks
			if (Files.exists(path))
				return true;
			throw new IOException("Fixture process " + process.pid() + " exited with " + process.exitValue() +
				" before publishing " + path);
		}, HANG_LIMIT, "Fixture process " + process.pid() + " stayed live for " + HANG_LIMIT + " without publishing " +
			path);
	}

	/**
	 * Waits until a fixture has published non-empty file contents.
	 *
	 * @param watch registered directory watcher
	 * @param path readiness file
	 * @param timeout maximum wait
	 * @throws IOException if the file remains empty before timeout
	 * @throws InterruptedException if the wait is interrupted
	 */
	public static void awaitNonEmptyFile(WatchService watch, Path path, Duration timeout) throws IOException,
		InterruptedException
	{
		awaitCondition(watch, () -> Files.isRegularFile(path) && Files.size(path) > 0, timeout,
			"Timed out waiting for non-empty fixture file: " + path);
	}

	/**
	 * Waits for an I/O predicate, checking it after each file system event and at least every
	 * {@link #CHECK_INTERVAL}.
	 *
	 * @param watch registered event watcher
	 * @param condition state predicate
	 * @param timeout maximum duration
	 * @param message timeout failure message
	 * @throws IOException if the predicate fails, or is not satisfied before timeout
	 * @throws InterruptedException if the wait is interrupted
	 */
	private static void awaitCondition(WatchService watch, IoCondition condition, Duration timeout, String message)
		throws IOException, InterruptedException
	{
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!condition.test())
		{
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0)
				throw new IOException(message);
			java.nio.file.WatchKey key = watch.poll(Math.min(remaining, CHECK_INTERVAL.toNanos()),
				TimeUnit.NANOSECONDS);
			if (key != null)
			{
				// Resetting a key whose events were not taken signals it again at once, which would spin this loop
				key.pollEvents();
				key.reset();
			}
		}
	}

	/**
	 * Checks whether a persisted reservation binds the fixture process identity.
	 *
	 * @param ledger controller ledger path
	 * @param pid fixture process identifier
	 * @return whether the ledger contains the process identifier
	 * @throws IOException if the ledger cannot be read
	 */
	private static boolean ledgerBindsPid(Path ledger, long pid) throws IOException
	{
		if (!Files.isRegularFile(ledger))
			return false;
		JsonNode state;
		try
		{
			state = JSON.readTree(Files.readAllBytes(ledger));
		}
		catch (tools.jackson.core.JacksonException _)
		{
			return false;
		}
		for (JsonNode reservation : state.path("reservations"))
		{
			JsonNode process = reservation.path("process");
			if (process.path("pid").longValue() == pid)
				return true;
		}
		return false;
	}

	@FunctionalInterface
	private interface IoCondition
	{
		/**
		 * Evaluates the watched state.
		 *
		 * @return whether the awaited state holds
		 * @throws IOException if reading state fails
		 */
		boolean test() throws IOException;
	}
}
