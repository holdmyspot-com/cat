package com.holdmyspot.cat.common.installer.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer;
import com.holdmyspot.cat.common.installer.secrets.StartupCacheTrainerSecrets;
import com.holdmyspot.cat.common.runner.StartupCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Runs startup-cache training in an owned JVM so its sleeping retry boundary belongs to a separate test application.
 */
public final class StartupTrainingApplication
{
	private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(90);
	private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(10);
	private static final BooleanSupplier NOT_ELEVATED = () -> false;

	/** Prevents instantiation. */
	private StartupTrainingApplication()
	{
	}

	/**
	 * Starts a separate JVM to train the plugin's startup cache and returns its observed result.
	 *
	 * @param plugin the plugin directory
	 * @param scratch the training scratch directory
	 * @return whether training wrote the startup cache
	 * @throws IOException if the child cannot run or reports a failure
	 * @throws InterruptedException if this caller is interrupted while waiting for the child
	 */
	public static boolean train(Path plugin, Path scratch) throws IOException, InterruptedException
	{
		return runChild(plugin, null, "train", plugin.toAbsolutePath().toString(), scratch.toAbsolutePath().toString());
	}

	/**
	 * Starts a separate JVM to cancel a live training and verifies that the child observed the cancellation.
	 *
	 * @param plugin the plugin directory
	 * @param scratch the training scratch directory
	 * @param readyPath the fixture process readiness file
	 * @throws IOException if the child cannot run or reports a failure
	 * @throws InterruptedException if this caller is interrupted while waiting for the child
	 */
	public static void cancel(Path plugin, Path scratch, Path readyPath) throws IOException, InterruptedException
	{
		boolean written = runChild(plugin, false, "cancel", plugin.toAbsolutePath().toString(),
			scratch.toAbsolutePath().toString(), readyPath.toAbsolutePath().toString());
		requireThat(written, "written").isFalse();
	}

	/**
	 * Starts this class as a named-module application. This is the only location that supplies real sleeping pauses.
	 *
	 * @param arguments the operation followed by the explicit plugin and scratch paths, and readiness path for cancel
	 * @throws IOException if the application cannot train or clean up
	 * @throws InterruptedException if training or cleanup is interrupted
	 */
	public static void main(String[] arguments) throws IOException, InterruptedException
	{
		Waiting waiting = Pause::sleeping;
		boolean written;
		if (arguments.length == 3 && arguments[0].equals("train"))
			written = trainPlugin(Path.of(arguments[1]), Path.of(arguments[2]), waiting);
		else if (arguments.length == 4 && arguments[0].equals("cancel"))
			written = cancelTraining(Path.of(arguments[1]), Path.of(arguments[2]), Path.of(arguments[3]), waiting);
		else
			throw new IllegalArgumentException("Expected train <plugin> <scratch> or cancel <plugin> <scratch> <ready>");
		System.out.println("written=" + written);
	}

	/**
	 * Starts a child application, retains its complete output on failure and removes its owned directory after reaping.
	 *
	 * @param plugin the plugin that owns the temporary application directory
	 * @param expectedWritten the expected result, or null if either result is valid
	 * @param arguments the child application's arguments
	 * @return the child's observed result
	 * @throws IOException if the child fails to start, terminate or report a valid result
	 * @throws InterruptedException if this caller is interrupted while waiting for the child
	 */
	private static boolean runChild(Path plugin, Boolean expectedWritten, String... arguments)
		throws IOException, InterruptedException
	{
		Path parent = plugin.toAbsolutePath().getParent();
		requireThat(parent, "pluginParent").isNotNull();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(parent, "startup-training-application-"));
			Path stdout = directory.resolve("stdout.txt");
			Path stderr = directory.resolve("stderr.txt");
			Path temporary = Files.createDirectory(directory.resolve("tmp"));
			String executable = "java";
			if (Platform.current().isWindows())
				executable = "java.exe";
			Path java = Path.of(System.getProperty("java.home"), "bin", executable);
			List<String> command = new ArrayList<>(List.of(java.toString(), "-XX:+PerfDisableSharedMem",
				"-Djava.io.tmpdir=" + temporary, "-Duser.home=" + directory.resolve("absent-home"),
				"--module-path", System.getProperty("jdk.module.path"), "--module",
				"com.holdmyspot.cat.common.installer.test/" + StartupTrainingApplication.class.getName()));
			command.addAll(List.of(arguments));
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.directory(directory.toFile());
			builder.environment().clear();
			copyWindowsLaunchEnvironment(builder);
			builder.redirectOutput(stdout.toFile());
			builder.redirectError(stderr.toFile());
			try
			{
				Process started = builder.start();
				try (started)
				{
					try
					{
						if (!started.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
							throw new IOException("Startup training application exceeded " + PROCESS_TIMEOUT);
						String output = Files.readString(stdout, UTF_8).strip();
						if (started.exitValue() != 0)
							throw new IOException("Startup training application exited with " + started.exitValue());
						String errors = Files.readString(stderr, UTF_8);
						boolean written = parseWritten(output);
						if (expectedWritten != null && written != expectedWritten)
							throw new IOException("Startup training application returned " + output +
								diagnostics(output, errors));
						return written;
					}
					catch (IOException | RuntimeException failure)
					{
						terminate(started, failure);
						throw failure;
					}
					catch (InterruptedException failure)
					{
						terminate(started, failure);
						Thread.currentThread().interrupt();
						throw failure;
					}
				}
			}
			catch (IOException | RuntimeException failure)
			{
				throw new IOException(failure.getMessage() + diagnosticsIfAvailable(stdout, stderr), failure);
			}
		}
	}

	/**
	 * Copies only Windows variables needed to start a JVM and its batch wrappers from a clean environment.
	 *
	 * @param builder the child JVM builder
	 */
	private static void copyWindowsLaunchEnvironment(ProcessBuilder builder)
	{
		if (!Platform.current().isWindows())
			return;
		for (String name : List.of("SystemRoot", "ComSpec", "Path"))
		{
			String value = System.getenv(name);
			if (value != null)
				builder.environment().put(name, value);
		}
	}

	/**
	 * Stops and reaps the application and every currently owned descendant.
	 *
	 * @param process the process to stop
	 * @param failure the failure receiving any reaping diagnostics
	 */
	private static void terminate(Process process, Throwable failure)
	{
		List<ProcessHandle> owned = new ArrayList<>(process.toHandle().descendants().toList());
		owned.add(process.toHandle());
		for (ProcessHandle handle : owned)
			handle.destroyForcibly();
		for (ProcessHandle handle : owned)
		{
			try
			{
				handle.onExit().get(TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException | ExecutionException | TimeoutException e)
			{
				failure.addSuppressed(e);
			}
		}
	}

	/**
	 * Reports stdout and stderr as complete diagnostics.
	 *
	 * @param output captured stdout
	 * @param errors captured stderr
	 * @return formatted diagnostics
	 */
	private static String diagnostics(String output, String errors)
	{
		return "\nstdout:\n" + output + "\nstderr:\n" + errors;
	}

	/**
	 * Reads captured diagnostics after an error, including setup failures that happen before the child starts.
	 *
	 * @param stdout captured stdout file
	 * @param stderr captured stderr file
	 * @return formatted diagnostics, or empty if they cannot be read
	 */
	private static String diagnosticsIfAvailable(Path stdout, Path stderr)
	{
		try
		{
			String output = "";
			if (Files.exists(stdout))
				output = Files.readString(stdout, UTF_8);
			String errors = "";
			if (Files.exists(stderr))
				errors = Files.readString(stderr, UTF_8);
			return diagnostics(output, errors);
		}
		catch (IOException _)
		{
			return "\nChild output could not be read.";
		}
	}

	/**
	 * Parses the one fixed result line printed by the child application.
	 *
	 * @param output the child's standard output
	 * @return the reported result
	 * @throws IOException if the child result is malformed
	 */
	private static boolean parseWritten(String output) throws IOException
	{
		return switch (output)
		{
			case "written=true" -> true;
			case "written=false" -> false;
			default -> throw new IOException("Startup training application returned an invalid result: " + output);
		};
	}

	/**
	 * Trains the plugin with the supplied real waiting boundary.
	 *
	 * @param plugin the plugin directory
	 * @param scratch the scratch directory
	 * @param waiting the cleanup pause boundary
	 * @return whether training wrote a cache
	 * @throws IOException if training fails
	 * @throws InterruptedException if training is interrupted
	 */
	private static boolean trainPlugin(Path plugin, Path scratch, Waiting waiting)
		throws IOException, InterruptedException
	{
		return new StartupCacheTrainer(scratch, Duration.ofMinutes(1), NOT_ELEVATED, waiting).train(plugin);
	}

	/**
	 * Interrupts a training after observing its owned Java fixture alive, then checks cache and process cleanup.
	 *
	 * @param plugin the plugin directory
	 * @param scratch the scratch directory
	 * @param readyPath the fixture process readiness file
	 * @param waiting the cleanup pause boundary
	 * @return false when cancellation prevented the cache from being written
	 * @throws IOException if fixture observation or cleanup fails
	 * @throws InterruptedException if this application is interrupted while waiting
	 */
	private static boolean cancelTraining(Path plugin, Path scratch, Path readyPath, Waiting waiting)
		throws IOException, InterruptedException
	{
		Path readinessDirectory = readyPath.toAbsolutePath().getParent();
		AtomicReference<List<ProcessHandle>> ownedProcesses = new AtomicReference<>(List.of());
		StartupCacheTrainer trainer = StartupCacheTrainerSecrets.getStartupCacheTrainerAccess().create(scratch,
			Duration.ofMinutes(1), NOT_ELEVATED, path ->
			{
				List<Long> running = ownedProcesses.get().stream().filter(ProcessHandle::isAlive).
					map(ProcessHandle::pid).toList();
				requireThat(running, "processesAliveBeforeDeleting").isEmpty();
				TemporaryDirectories.deleteRecursively(path);
			}, waiting);
		FutureTask<Boolean> trainingResult = new FutureTask<>(() -> trainer.train(plugin));
		Thread trainingThread = new Thread(trainingResult, "startup-cache-training");
		ProcessHandle fixtureProcess = null;
		try (WatchService readiness = readinessDirectory.getFileSystem().newWatchService())
		{
			readinessDirectory.register(readiness, StandardWatchEventKinds.ENTRY_CREATE);
			trainingThread.start();
			fixtureProcess = awaitFixtureProcess(readiness, readyPath);
			ownedProcesses.set(List.of(fixtureProcess));
			requireThat(fixtureProcess.isAlive(), "fixtureProcess").isTrue();
			ownedProcesses.set(fixtureTree(fixtureProcess));
			trainingThread.interrupt();
			boolean written = awaitTraining(trainingResult);

			requireThat(written, "written").isFalse();
			requireThat(trainingThread.isInterrupted(), "trainingThreadInterrupted").isTrue();
			requireThat(fixtureProcess.isAlive(), "fixtureProcess").isFalse();
			requireThat(Files.exists(plugin.resolve(StartupCache.DIRECTORY), NOFOLLOW_LINKS), "cacheFolder").isFalse();
			requireThat(entries(scratch), "scratchEntries").isEmpty();
			String jar = plugin.resolve("bin/modules/fixture.jar").toString();
			List<ProcessHandle> running = ProcessHandle.allProcesses().
				filter(process -> process.info().commandLine().map(line -> line.contains(jar)).orElse(false)).toList();
			requireThat(running, "stillRunning").isEmpty();
			return written;
		}
		finally
		{
			trainingThread.interrupt();
			trainingThread.join(10_000);
			for (ProcessHandle process : ownedProcesses.get())
				process.destroyForcibly();
			for (ProcessHandle process : ownedProcesses.get())
				awaitProcessExit(process);
			requireThat(trainingThread.isAlive(), "trainingThread").isFalse();
			if (fixtureProcess != null)
				requireThat(fixtureProcess.isAlive(), "fixtureProcess").isFalse();
		}
	}

	/**
	 * Waits for the child training and converts its bounded failures into application diagnostics.
	 *
	 * @param trainingResult the owned training result
	 * @return whether training wrote its cache
	 * @throws IOException if training fails or misses its deadline
	 * @throws InterruptedException if this application is interrupted
	 */
	private static boolean awaitTraining(FutureTask<Boolean> trainingResult) throws IOException, InterruptedException
	{
		try
		{
			return trainingResult.get(10, TimeUnit.SECONDS);
		}
		catch (ExecutionException | TimeoutException e)
		{
			throw new IOException("Startup cache training did not finish after cancellation", e);
		}
	}

	/**
	 * Waits for the fixture process to publish its ID and returns the process named by that ID.
	 *
	 * @param readiness the watch service registered before training starts
	 * @param readyPath the path at which the fixture publishes its ID
	 * @return the observed fixture process
	 * @throws IOException if readiness is malformed or the fixture does not appear before the deadline
	 * @throws InterruptedException if this application is interrupted while waiting
	 */
	private static ProcessHandle awaitFixtureProcess(WatchService readiness, Path readyPath)
		throws IOException, InterruptedException
	{
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (true)
		{
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0)
				throw new IOException("Fixture process did not publish its ID before the readiness deadline");
			WatchKey event = readiness.poll(remaining, TimeUnit.NANOSECONDS);
			if (event == null)
				throw new IOException("Fixture process did not publish its ID before the readiness deadline");
			for (WatchEvent<?> change : event.pollEvents())
			{
				if (change.kind() == StandardWatchEventKinds.OVERFLOW)
					throw new IOException("Fixture process readiness events overflowed");
				if (change.kind() == StandardWatchEventKinds.ENTRY_CREATE &&
					change.context().equals(readyPath.getFileName()))
				{
					long pid = Long.parseLong(Files.readString(readyPath, UTF_8));
					return ProcessHandle.of(pid).orElseThrow(
						() -> new IOException("Fixture process ID does not identify a running process"));
				}
			}
			if (!event.reset())
				throw new IOException("Fixture process readiness watch became invalid");
		}
	}

	/**
	 * Captures the wrapper and descendants while the published fixture process is held running.
	 *
	 * @param fixture the Java process that published readiness
	 * @return the owned wrapper and descendants
	 * @throws IOException if the fixture cannot be traced to this application
	 */
	private static List<ProcessHandle> fixtureTree(ProcessHandle fixture) throws IOException
	{
		ProcessHandle root = fixture;
		while (true)
		{
			ProcessHandle parent = root.parent().orElseThrow(
				() -> new IOException("Fixture process has no ancestor in this application"));
			if (parent.pid() == ProcessHandle.current().pid())
				break;
			root = parent;
		}
		List<ProcessHandle> owned = new ArrayList<>();
		owned.add(root);
		owned.addAll(root.descendants().toList());
		return List.copyOf(owned);
	}

	/**
	 * Waits for a process to finish after cancellation cleanup.
	 *
	 * @param process the owned process
	 * @throws IOException if the process does not terminate
	 * @throws InterruptedException if this application is interrupted while waiting
	 */
	private static void awaitProcessExit(ProcessHandle process) throws IOException, InterruptedException
	{
		try
		{
			process.onExit().get(TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (ExecutionException | TimeoutException e)
		{
			throw new IOException("An owned training process did not terminate", e);
		}
	}

	/**
	 * Lists scratch entries if scratch exists.
	 *
	 * @param scratch the training scratch directory
	 * @return the remaining entries
	 * @throws IOException if scratch cannot be inspected
	 */
	private static List<Path> entries(Path scratch) throws IOException
	{
		if (!Files.exists(scratch, NOFOLLOW_LINKS))
			return List.of();
		try (Stream<Path> paths = Files.list(scratch))
		{
			return paths.toList();
		}
	}
}
