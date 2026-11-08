package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.FakeProcessLauncher;
import com.holdmyspot.cat.common.fakes.test.ProcessFolder;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.launcher.LauncherRecord;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart.DetachedStarter;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.secrets.HarnessProcessesSecrets;
import com.holdmyspot.cat.common.secrets.SessionLauncherStartSecrets;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that CAT's SessionStart hook starts one session launcher for each harness process, outside CAT's own runs and
 * native Windows, and leaves it running without waiting for it.
 */
public final class SessionLauncherStartTest
{
	/**
	 * How long the process-level test waits for a fixture process.
	 */
	private static final Duration FIXTURE_WAIT = Duration.ofSeconds(30);
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public SessionLauncherStartTest()
	{
	}

	/**
	 * Verifies that the hook writes its data to the launcher's folder and starts the plugin's launcher on that
	 * folder, reading the null device and with its output and error output appended to the folder's log. A stand-in
	 * for the operating system records the process that the real starter starts.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsLauncherWithHookData() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-launcher-start-")).toRealPath();
			SessionLauncherChannel channel = new SessionLauncherChannel(root.resolve("home"));
			LauncherRecord record = record(root);
			Path plugin = root.resolve("plugin");
			FakeProcessLauncher processes = new FakeProcessLauncher();

			boolean started = SessionLauncherStart.start(channel, record, plugin, Platform.LINUX_X64,
				Map.of("PATH", ""), new FileLocks(), starter(processes));

			Path folder = channel.launcherDirectory("claude", 4242, record.process().start());
			requireThat(started, "started").isTrue();
			requireThat(commands(processes), "commands").isEqualTo(List.of(List.of(
				plugin.resolve("bin").resolve("session-launcher").toString(), folder.toString())));
			Path log = channel.logFile("claude", 4242, record.process().start());
			ProcessBuilder launcher = processes.started().getFirst();
			requireThat(launcher.redirectInput(), "input").isEqualTo(Redirect.from(new File("/dev/null")));
			requireThat(launcher.redirectOutput(), "output").isEqualTo(Redirect.appendTo(log.toFile()));
			requireThat(launcher.redirectError(), "error").isEqualTo(Redirect.appendTo(log.toFile()));
			requireThat(log.getParent(), "logsDirectory").isDirectory();
			requireThat(LauncherRecord.read(json, folder.resolve(SessionLauncherChannel.LAUNCHER_FILE)), "written").
				isEqualTo(record);
		}
	}

	/**
	 * Verifies that the hook creates the inbox before it returns, so that a sandbox that lets a session write only
	 * folders that exist when a command starts lets the session's first command send a request.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void createsInboxBeforeSessionCommands() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-launcher-start-")).toRealPath();
			SessionLauncherChannel channel = new SessionLauncherChannel(root.resolve("home"));

			boolean started = SessionLauncherStart.start(channel, record(root), root.resolve("plugin"),
				Platform.LINUX_X64, Map.of("PATH", ""), new FileLocks(), starter(new FakeProcessLauncher()));

			requireThat(started, "started").isTrue();
			requireThat(channel.inbox(), "inbox").isDirectory();
		}
	}

	/**
	 * Verifies that the launcher starts in a session of its own when {@code setsid} is on the hook's {@code PATH}, so
	 * that the harness's terminal signals do not reach it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsUnderSetsidWhenOnPath() throws IOException
	{
		if (Platform.current().isWindows())
		{
			throw new SkipException("A POSIX PATH cannot name a Windows folder, and native Windows has no session " +
				"launcher");
		}
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-launcher-start-")).toRealPath();
			Path programs = Files.createDirectory(root.resolve("programs"));
			Path setsid = Files.writeString(programs.resolve("setsid"), "#!/bin/sh\n");
			requireThat(setsid.toFile().setExecutable(true, false), "executable").isTrue();
			SessionLauncherChannel channel = new SessionLauncherChannel(root.resolve("home"));
			LauncherRecord record = record(root);
			Path plugin = root.resolve("plugin");
			FakeProcessLauncher processes = new FakeProcessLauncher();

			SessionLauncherStart.start(channel, record, plugin, Platform.LINUX_X64,
				Map.of("PATH", programs.toString()), new FileLocks(), starter(processes));

			Path folder = channel.launcherDirectory("claude", 4242, record.process().start());
			requireThat(commands(processes), "commands").isEqualTo(List.of(List.of(setsid.toString(),
				plugin.resolve("bin").resolve("session-launcher").toString(), folder.toString())));
		}
	}

	/**
	 * Verifies that the hook starts no launcher on native Windows.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsNoneOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-launcher-start-")).toRealPath();
			SessionLauncherChannel channel = new SessionLauncherChannel(root.resolve("home"));
			FakeProcessLauncher processes = new FakeProcessLauncher();

			boolean started = SessionLauncherStart.start(channel, record(root), root.resolve("plugin"),
				Platform.WINDOWS_X64, Map.of(), new FileLocks(), starter(processes));

			requireThat(started, "started").isFalse();
			requireThat(commands(processes), "commands").isEmpty();
			requireThat(Files.exists(channel.launchers(), LinkOption.NOFOLLOW_LINKS), "launchers").isFalse();
		}
	}

	/**
	 * Verifies that the hook of a session that CAT's runner started starts no launcher.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsNoneInCatRun() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-launcher-start-")).toRealPath();
			SessionLauncherChannel channel = new SessionLauncherChannel(root.resolve("home"));
			FakeProcessLauncher processes = new FakeProcessLauncher();

			boolean started = SessionLauncherStart.start(channel, record(root), root.resolve("plugin"),
				Platform.LINUX_X64, Map.of(HarnessEnvironment.RUN_VARIABLE, HarnessEnvironment.RUN_VALUE),
				new FileLocks(), starter(processes));

			requireThat(started, "started").isFalse();
			requireThat(commands(processes), "commands").isEmpty();
			requireThat(Files.exists(channel.launchers(), LinkOption.NOFOLLOW_LINKS), "launchers").isFalse();
		}
	}

	/**
	 * Verifies that the hook starts no second launcher while a live launcher holds the folder's lock, and leaves
	 * that launcher's data as it is.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsNoneWhileLauncherHoldsLock() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-launcher-start-")).toRealPath();
			SessionLauncherChannel channel = new SessionLauncherChannel(root.resolve("home"));
			LauncherRecord record = record(root);
			Path folder = Files.createDirectories(channel.launcherDirectory("claude", 4242,
				record.process().start()));
			Files.writeString(folder.resolve(SessionLauncherChannel.LAUNCHER_FILE), "running launcher's data");
			FileLocks fileLocks = new FileLocks();
			FakeProcessLauncher processes = new FakeProcessLauncher();
			Optional<ExclusiveFileLock> held = fileLocks.tryLock(folder.resolve(SessionLauncherChannel.LOCK_FILE),
				file -> FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE));
			try (ExclusiveFileLock _ = held.orElseThrow())
			{
				boolean started = SessionLauncherStart.start(channel, record, root.resolve("plugin"),
					Platform.LINUX_X64, Map.of(), fileLocks, starter(processes));

				requireThat(started, "started").isFalse();
				requireThat(commands(processes), "commands").isEmpty();
				requireThat(Files.readString(folder.resolve(SessionLauncherChannel.LAUNCHER_FILE)), "data").
					isEqualTo("running launcher's data");
			}
		}
	}

	/**
	 * Verifies that a later SessionStart of a harness process whose launcher runs starts no second launcher after
	 * the wall clock was set back, which moves the boot time that Linux derives from it: the hook names the harness
	 * process by the start that the kernel records, as the first hook did, and finds the running launcher's lock. A
	 * folder stands for Linux's process folder, and the test's own process plays the harness process.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsNoneAfterBootTimeMoves() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-launcher-start-")).toRealPath();
			ProcessFolder folder = new ProcessFolder(Files.createDirectories(root.resolve("proc")), 1_794_069_622);
			long harnessPid = ProcessHandle.current().pid();
			folder.start(harnessPid, "claude", 1_234_563);
			SessionLauncherChannel channel = new SessionLauncherChannel(root.resolve("home"));
			FileLocks fileLocks = new FileLocks();
			FakeProcessLauncher processes = new FakeProcessLauncher();
			ProcessIdentity first = harnessProcess(folder, harnessPid);
			boolean startedFirst = SessionLauncherStart.start(channel, record(root, first), root.resolve("plugin"),
				Platform.LINUX_X64, Map.of(), fileLocks, starter(processes));
			Path launcher = channel.launcherDirectory("claude", harnessPid, first.start());
			Optional<ExclusiveFileLock> running = fileLocks.tryLock(launcher.resolve(SessionLauncherChannel.LOCK_FILE),
				file -> FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE));
			try (ExclusiveFileLock _ = running.orElseThrow())
			{
				folder.setBootTime(1_794_069_343);

				boolean startedLater = SessionLauncherStart.start(channel, record(root, harnessProcess(folder,
					harnessPid)), root.resolve("plugin"), Platform.LINUX_X64, Map.of(), fileLocks, starter(processes));

				requireThat(startedFirst, "startedFirst").isTrue();
				requireThat(startedLater, "startedLater").isFalse();
				requireThat(commands(processes).size(), "launchers").isEqualTo(1);
				try (Stream<Path> folders = Files.list(launcher.getParent()))
				{
					requireThat(folders.toList(), "folders").isEqualTo(List.of(launcher));
				}
			}
		}
	}

	/**
	 * Returns the identity of a harness process, as a SessionStart hook reads it.
	 *
	 * @param folder the stand-in for Linux's process folder
	 * @param pid    the harness process's identifier
	 * @return the identity
	 * @throws IOException if the folder does not show when the process started
	 */
	private static ProcessIdentity harnessProcess(ProcessFolder folder, long pid) throws IOException
	{
		HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());
		return SessionLauncherStart.identity(processes.find(pid).orElseThrow());
	}

	/**
	 * Verifies that a hook that starts a launcher can end with its output and error output closed while the launcher
	 * keeps running: the launcher reads end-of-input at once and writes its own output to its log. A separate JVM
	 * plays the hook, and a shell that keeps running plays the launcher.
	 *
	 * @throws IOException          if the fixture cannot be created
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException   if the hook's output cannot be read
	 */
	@Test
	public void launcherOutlivesHookOutput() throws IOException, InterruptedException, ExecutionException
	{
		// CAT starts no session launcher on native Windows
		if (Platform.current().isWindows())
			return;
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-launcher-start-")).toRealPath();
			Path log = root.resolve("launcher.log");
			Path pidFile = root.resolve("launcher.pid");
			Path java = Path.of(System.getProperty("java.home"), "bin", "java");
			String classes = System.getProperty("jdk.module.path", System.getProperty("java.class.path"));
			ProcessBuilder builder = new ProcessBuilder(java.toString(), "-XX:+PerfDisableSharedMem",
				"-XX:TieredStopAtLevel=1", "-cp", classes, DetachedStartProcess.class.getName(),
				log.toString(), pidFile.toString()).directory(root.toFile()).redirectErrorStream(true);
			try (Process hook = builder.start())
			{
				CompletableFuture<String> hookOutput = CompletableFuture.supplyAsync(() ->
					readAll(hook.getInputStream()));
				try
				{
					String output = hookOutput.get(FIXTURE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
					requireThat(hook.waitFor(FIXTURE_WAIT.toMillis(), TimeUnit.MILLISECONDS), "hookEnded").isTrue();
					requireThat(hook.exitValue(), "hookExit").withContext(output, "output").isEqualTo(0);
					requireThat(output, "hookOutput").contains("hook output").contains("hook error");
					ProcessHandle launcher = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip())).
						orElseThrow();
					requireThat(launcher.isAlive(), "launcherAlive").isTrue();
					String launcherOutput = awaitLog(log, "launcher-error");
					requireThat(launcherOutput, "launcherOutput").contains("stdin-ended").contains("launcher-error");
					requireThat(launcher.isAlive(), "launcherStillAlive").isTrue();
				}
				catch (TimeoutException _)
				{
					throw new AssertionError("The hook's output stayed open while the launcher ran");
				}
				finally
				{
					hook.destroyForcibly();
					stopLauncher(pidFile);
				}
			}
		}
	}

	/**
	 * Reads a stream to its end.
	 *
	 * @param stream the stream
	 * @return the text
	 */
	private static String readAll(InputStream stream)
	{
		try
		{
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Waits for a log to hold a line.
	 *
	 * @param log  the log
	 * @param line the text that the log must hold
	 * @return the log's text
	 * @throws IOException if the log cannot be read, or does not hold the text in time
	 */
	private static String awaitLog(Path log, String line) throws IOException
	{
		long deadline = System.nanoTime() + FIXTURE_WAIT.toNanos();
		while (true)
		{
			if (Files.exists(log))
			{
				String text = Files.readString(log);
				if (text.contains(line))
					return text;
			}
			if (System.nanoTime() - deadline > 0)
				throw new IOException("The launcher's log " + log + " never held " + line);
			Thread.yield();
		}
	}

	/**
	 * Stops the fixture launcher, if it started.
	 *
	 * @param pidFile the file that names the launcher's process identifier
	 * @throws IOException if the file cannot be read
	 */
	private static void stopLauncher(Path pidFile) throws IOException
	{
		if (!Files.exists(pidFile))
			return;
		String pid = Files.readString(pidFile).strip();
		if (pid.isEmpty())
			return;
		ProcessHandle.of(Long.parseLong(pid)).ifPresent(ProcessHandle::destroyForcibly);
	}

	/**
	 * Returns the real starter of launcher processes, which starts them through a stand-in for the operating system.
	 *
	 * @param processes the stand-in for the operating system's process start
	 * @return the starter
	 */
	private static DetachedStarter starter(FakeProcessLauncher processes)
	{
		return SessionLauncherStartSecrets.getSessionLauncherStartAccess().processes(processes);
	}

	/**
	 * Returns the command lines of the processes that were started.
	 *
	 * @param processes the stand-in for the operating system's process start
	 * @return the command lines, in start order
	 */
	private static List<List<String>> commands(FakeProcessLauncher processes)
	{
		return processes.started().stream().map(builder -> List.copyOf(builder.command())).toList();
	}

	/**
	 * Returns the hook data of a Claude Code session.
	 *
	 * @param root the test's folder
	 * @return the data
	 */
	private static LauncherRecord record(Path root)
	{
		return record(root, new ProcessIdentity(4242, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t4242"));
	}

	/**
	 * Returns the hook data of a Claude Code session.
	 *
	 * @param root    the test's folder
	 * @param process the Claude Code process
	 * @return the data
	 */
	private static LauncherRecord record(Path root, ProcessIdentity process)
	{
		return new LauncherRecord("claude", process, "session-1", root.resolve("project"),
			Optional.of(root.resolve("transcript.jsonl")), "startup", root.resolve("home/.claude"), "1.0");
	}
}
