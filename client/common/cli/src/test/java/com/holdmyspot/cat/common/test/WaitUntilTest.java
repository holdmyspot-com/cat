package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.WaitUntil;
import com.holdmyspot.cat.common.secrets.WaitUntilSecrets;
import com.holdmyspot.cat.common.secrets.WaitUntilTimer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests {@link WaitUntil}.
 * <p>
 * Every test of a wait except {@link #systemTimerRunsCheckAgain()} measures the wait's time through a
 * {@link ManualTimer}, so that a deadline or an interval passes when the wait would otherwise block, without waiting in
 * real time, and starts its commands through a {@link FakeStarter}. The {@link WaitUntilTimerContract} runs on both the
 * manual timer and the timer of a real wait, and the {@link CommandStarterContract} on both the fake starter and the
 * shell starter, whose shell process is replaced by one that has already exited.
 */
public final class WaitUntilTest
{
	/**
	 * Creates a test instance.
	 */
	public WaitUntilTest()
	{
	}

	/**
	 * Verifies that a check that reports a pending operation runs again one interval after each run ends, until it
	 * reports success, and that the wait then prints the check's last output and exits with 0.
	 */
	@Test
	public void checkRunsUntilOperationSucceeds() throws InterruptedException
	{
		FakeStarter starter = new FakeStarter();
		starter.add("status", FakeProcess.exits(75, "running"), FakeProcess.exits(75, "running"),
			FakeProcess.exits(0, "deployed"));
		Outcome outcome = run(starter, "--deadline", "5s", "--interval", "10ms", "--check", "status");

		requireThat(outcome.status(), "status").isEqualTo(0);
		requireThat(outcome.output(), "output").contains("succeeded: status").contains("deployed");
		requireThat(starter.startTimes("status"), "startTimes").isEqualTo(List.of(Duration.ZERO,
			Duration.ofMillis(10), Duration.ofMillis(20)));
	}

	/**
	 * Verifies that a wait that measures real time runs a pending check again and reports the operation's success.
	 * This is the end-to-end check of the system timer: it shows only that the real wait ends and its result reaches
	 * the caller.
	 */
	@Test
	public void systemTimerRunsCheckAgain() throws InterruptedException
	{
		FakeStarter starter = new FakeStarter();
		starter.add("status", FakeProcess.exits(75, "running"), FakeProcess.exits(0, "deployed"));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		int status = new WaitUntil(starter, new PrintStream(output, true, UTF_8),
			new PrintStream(OutputStream.nullOutputStream(), true, UTF_8)).
			run(List.of("--deadline", "5s", "--interval", "1ms", "--check", "status"));

		requireThat(status, "status").isEqualTo(0);
	}

	/**
	 * Verifies that the wait closes the process of every run that ended, which releases the run's output pipe.
	 */
	@Test
	public void endedRunsAreClosed() throws InterruptedException
	{
		FakeStarter starter = new FakeStarter();
		starter.add("status", FakeProcess.exits(75, "running"), FakeProcess.exits(0, "deployed"));
		run(starter, "--deadline", "5s", "--interval", "10ms", "--check", "status");

		requireThat(starter.allClosed("status"), "allClosed").isTrue();
	}

	/**
	 * Verifies that a check that reports an unsuccessful operation ends the wait with 1.
	 */
	@Test
	public void checkReportsFailure() throws InterruptedException
	{
		FakeStarter starter = new FakeStarter();
		starter.add("status", FakeProcess.exits(1, "health check timed out"));
		Outcome outcome = run(starter, "--deadline", "5s", "--check", "status");

		requireThat(outcome.status(), "status").isEqualTo(1);
		requireThat(outcome.output(), "output").contains("failed: status").contains("health check timed out");
	}

	/**
	 * Verifies that a watch's exit status decides the result: 0 succeeds and any other status fails.
	 */
	@Test
	public void watchExitStatusDecidesResult() throws InterruptedException
	{
		FakeStarter succeeded = new FakeStarter();
		succeeded.add("gh run watch", FakeProcess.exitsAfter(succeeded.timer, Duration.ofMillis(20), 0, "completed"));
		requireThat(run(succeeded, "--deadline", "5s", "--watch", "gh run watch").status(), "succeeded").isEqualTo(0);

		FakeStarter failed = new FakeStarter();
		failed.add("gh run watch", FakeProcess.exitsAfter(failed.timer, Duration.ofMillis(20), 8, "failure"));
		Outcome outcome = run(failed, "--deadline", "5s", "--watch", "gh run watch");
		requireThat(outcome.status(), "failed").isEqualTo(1);
		requireThat(outcome.output(), "output").contains("failed: gh run watch").contains("failure");
	}

	/**
	 * Verifies that the first operation to finish ends the wait at once and that the other commands are stopped.
	 */
	@Test
	public void firstOperationEndsWait() throws InterruptedException
	{
		FakeStarter starter = new FakeStarter();
		starter.add("web", FakeProcess.running("web: running"));
		starter.add("api", FakeProcess.exits(75, "running"), FakeProcess.exits(1, "test_invoice_totals failed"));
		Outcome outcome = run(starter, "--deadline", "5s", "--interval", "10ms", "--watch", "web", "--check", "api");

		requireThat(outcome.status(), "status").isEqualTo(1);
		requireThat(outcome.output(), "output").contains("failed: api").contains("test_invoice_totals failed");
		requireThat(starter.destroyed("web"), "destroyed").isTrue();
	}

	/**
	 * Verifies that when the deadline passes first, the wait ends at the deadline, prints each command with its last
	 * output, stops the commands, and exits with 124.
	 */
	@Test
	public void deadlineEndsWait() throws InterruptedException
	{
		FakeStarter starter = new FakeStarter();
		starter.add("watch", FakeProcess.running("queued"));
		starter.repeat("status", () -> FakeProcess.exits(75, "running"));
		Outcome outcome = run(starter, "--deadline", "150ms", "--interval", "20ms", "--watch", "watch", "--check",
			"status");

		requireThat(outcome.status(), "status").isEqualTo(124);
		requireThat(outcome.output(), "output").contains("pending: watch").contains("queued").
			contains("pending: status").contains("running");
		requireThat(starter.destroyed("watch"), "destroyed").isTrue();
		requireThat(starter.timer.elapsed(), "elapsed").isEqualTo(Duration.ofMillis(150));
	}

	/**
	 * Verifies that a check that exits with an unexpected status three times in a row ends the wait with 3, and that
	 * a pending result in between restarts the count.
	 */
	@Test
	public void repeatedUnexpectedStatusEndsWait() throws InterruptedException
	{
		FakeStarter starter = new FakeStarter();
		starter.add("status", FakeProcess.exits(127, "gh: not found"), FakeProcess.exits(127, "gh: not found"),
			FakeProcess.exits(75, "running"), FakeProcess.exits(127, "gh: not found"),
			FakeProcess.exits(127, "gh: not found"), FakeProcess.exits(127, "gh: not found"));
		Outcome outcome = run(starter, "--deadline", "5s", "--interval", "10ms", "--check", "status");

		requireThat(outcome.status(), "status").isEqualTo(3);
		requireThat(outcome.output(), "output").contains("status").contains("gh: not found");
		requireThat(starter.starts("status"), "starts").isEqualTo(6);
	}

	/**
	 * Verifies that a command that cannot start ends the wait with 3.
	 */
	@Test
	public void commandThatCannotStartEndsWait() throws InterruptedException
	{
		WaitUntil.CommandStarter starter = command ->
		{
			throw new IOException("Cannot run program \"gh\"");
		};
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		int status = new WaitUntil(starter, new PrintStream(output, true, UTF_8),
			new PrintStream(OutputStream.nullOutputStream(), true, UTF_8)).
			run(List.of("--deadline", "5s", "--watch", "gh run watch 1"));

		requireThat(status, "status").isEqualTo(3);
		requireThat(output.toString(UTF_8), "output").contains("gh run watch 1").contains("Cannot run program");
	}

	/**
	 * Verifies that a missing deadline, a malformed duration and a wait without commands are usage errors.
	 */
	@Test
	public void rejectsInvalidUsage() throws InterruptedException
	{
		FakeStarter starter = new FakeStarter();
		requireThat(run(starter, "--check", "status").status(), "missingDeadline").isEqualTo(2);
		requireThat(run(starter, "--deadline", "5x", "--check", "status").status(), "malformedDuration").isEqualTo(2);
		requireThat(run(starter, "--deadline", "5s").status(), "noCommand").isEqualTo(2);
		requireThat(starter.starts("status"), "starts").isEqualTo(0);
	}

	/**
	 * Verifies that the shell starter runs the shell's program in the given folder and delivers its standard error
	 * with its standard output.
	 */
	@Test
	public void shellStarterRunsShellProgram() throws IOException, InterruptedException
	{
		String java = ProcessHandle.current().info().command().orElseThrow();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder = cleanup.add(Files.createTempDirectory("wait-until-shell-")).toAbsolutePath();
			WaitUntil.CommandStarter starter = WaitUntil.shellStarter(command -> List.of(java, "-version"), folder);
			try (Process process = starter.start("ignored"))
			{
				String output = new String(process.getInputStream().readAllBytes(), UTF_8);

				requireThat(process.waitFor(), "exitStatus").isEqualTo(0);
				requireThat(output, "output").contains("version");
			}
		}
	}

	/**
	 * Verifies that the manual timer of these tests hands a wait each run that ends and measures the wait's time as the
	 * timer of a real wait does.
	 *
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void manualTimerMeasuresWait() throws InterruptedException
	{
		ManualTimer timer = new ManualTimer();
		verifyTimer(new WaitUntilTimerContract(new WaitUntilTimerContract.Subject()
		{
			@Override
			public WaitUntilTimer timer()
			{
				return timer;
			}

			@Override
			public void endAfter(BlockingQueue<String> ended, String run, Duration delay)
			{
				timer.schedule(delay, () -> ended.add(run));
			}
		}));
	}

	/**
	 * Verifies that the timer of a real wait hands the wait each run that ends and measures the wait's time, waiting a
	 * few milliseconds in real time.
	 *
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void systemTimerMeasuresWait() throws InterruptedException
	{
		WaitUntilTimer timer = WaitUntilSecrets.getWaitUntilAccess().systemTimer();
		verifyTimer(new WaitUntilTimerContract(new WaitUntilTimerContract.Subject()
		{
			@Override
			public WaitUntilTimer timer()
			{
				return timer;
			}

			@Override
			public void endAfter(BlockingQueue<String> ended, String run, Duration delay)
			{
				// The run ends on another thread, as a command's process exits while the wait blocks
				CompletableFuture.delayedExecutor(delay.toNanos(), TimeUnit.NANOSECONDS).execute(() -> ended.add(run));
			}
		}));
	}

	/**
	 * Verifies that the fake starter of these tests starts a command's process as the shell starter does.
	 *
	 * @throws IOException          if the command cannot start
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void fakeStarterStartsCommand() throws IOException, InterruptedException
	{
		new CommandStarterContract((command, status, stdout, stderr) ->
		{
			FakeStarter starter = new FakeStarter();
			starter.add(command, FakeProcess.exits(status, stdout + stderr));
			return starter;
		}).startsCommand();
	}

	/**
	 * Verifies that the shell starter runs a command in the shell, in its folder, with the shell's standard error
	 * delivered with its standard output. The shell process is replaced by one that has already exited, so no shell
	 * starts; a process that the starter configures any other way fails the test.
	 *
	 * @throws IOException          if the command cannot start
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void shellStarterStartsCommand() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder = cleanup.add(Files.createTempDirectory("wait-until-shell-")).toAbsolutePath();
			new CommandStarterContract((command, status, stdout, stderr) -> WaitUntilSecrets.getWaitUntilAccess().
				shellStarter(text -> List.of("sh", "-c", text), folder, builder ->
				{
					if (!builder.command().equals(List.of("sh", "-c", command)))
						throw new AssertionError("Unexpected program: " + builder.command());
					if (!folder.toFile().equals(builder.directory()))
						throw new AssertionError("The shell runs in " + builder.directory() + " instead of " + folder);
					return new ExitedProcess(builder, status, stdout, stderr);
				})).startsCommand();
		}
	}

	/**
	 * Runs every case of the timer contract.
	 *
	 * @param contract the contract of one timer
	 * @throws InterruptedException if the thread is interrupted
	 */
	private static void verifyTimer(WaitUntilTimerContract contract) throws InterruptedException
	{
		contract.returnsEndedRun();
		contract.returnsRunThatEndsDuringWait();
		contract.returnsNothingAfterTimeout();
	}

	/**
	 * Runs a wait.
	 *
	 * @param starter starts the commands, and supplies the timer that measures the wait's time
	 * @param args    the command-line arguments
	 * @return the exit status and output
	 * @throws InterruptedException if the thread is interrupted
	 */
	private static Outcome run(FakeStarter starter, String... args) throws InterruptedException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		int status = WaitUntilSecrets.getWaitUntilAccess().create(starter, starter.timer,
			new PrintStream(output, true, UTF_8), new PrintStream(OutputStream.nullOutputStream(), true, UTF_8)).
			run(List.of(args));
		return new Outcome(status, output.toString(UTF_8));
	}

	/**
	 * The result of a wait.
	 *
	 * @param status the exit status
	 * @param output what the wait printed
	 */
	private record Outcome(int status, String output)
	{
	}

	/**
	 * A timer whose time passes only when the wait would block.
	 * <p>
	 * It stands in for the monotonic clock and the blocking queue wait of a real wait. When the wait asks to block
	 * until a run ends or a timeout passes, the timer moves its time to the earliest scheduled process exit within the
	 * timeout and performs it, or else to the end of the timeout. A wait under this timer uses it from one thread.
	 */
	private static final class ManualTimer implements WaitUntilTimer
	{
		/**
		 * The scheduled process exits, earliest first.
		 */
		private final Queue<ScheduledExit> exits = new PriorityQueue<>();
		/**
		 * The time that passed since the timer was created, in nanoseconds.
		 */
		private long now;

		/**
		 * Returns the time that passed since the timer was created.
		 *
		 * @return the time
		 */
		Duration elapsed()
		{
			return Duration.ofNanos(now);
		}

		/**
		 * Schedules a process exit.
		 *
		 * @param delay the time from now until the exit
		 * @param exit  performs the exit
		 */
		void schedule(Duration delay, Runnable exit)
		{
			exits.add(new ScheduledExit(now + delay.toNanos(), exit));
		}

		@Override
		public long nanoTime()
		{
			return now;
		}

		@Override
		public <E> E poll(BlockingQueue<E> queue, Duration timeout)
		{
			long until = now + timeout.toNanos();
			while (true)
			{
				E head = queue.poll();
				if (head != null)
					return head;
				ScheduledExit next = exits.peek();
				if (next == null || next.at() > until)
				{
					now = until;
					return null;
				}
				exits.remove();
				now = next.at();
				next.exit().run();
			}
		}

		/**
		 * A process exit at a time.
		 *
		 * @param at   the timer's reading at which the exit happens, in nanoseconds
		 * @param exit performs the exit
		 */
		private record ScheduledExit(long at, Runnable exit) implements Comparable<ScheduledExit>
		{
			@Override
			public int compareTo(ScheduledExit other)
			{
				return Long.compare(at, other.at);
			}
		}
	}

	/**
	 * Starts the scripted processes of each command in order.
	 */
	private static final class FakeStarter implements WaitUntil.CommandStarter
	{
		/**
		 * Measures the time of the wait that uses this starter.
		 */
		final ManualTimer timer = new ManualTimer();
		private final Map<String, Deque<FakeProcess>> processes = new HashMap<>();
		private final Map<String, Supplier<FakeProcess>> repeated = new HashMap<>();
		private final Map<String, Integer> starts = new HashMap<>();
		/**
		 * The timer's reading at each start of each command, in order.
		 */
		private final Map<String, List<Duration>> startTimes = new HashMap<>();
		/**
		 * The processes that each command's runs started, in order.
		 */
		private final Map<String, List<FakeProcess>> started = new HashMap<>();

		/**
		 * Adds the processes that a command's runs start, in order.
		 *
		 * @param command the command
		 * @param runs the processes
		 */
		void add(String command, FakeProcess... runs)
		{
			processes.computeIfAbsent(command, key -> new ArrayDeque<>()).addAll(List.of(runs));
		}

		/**
		 * Makes every run of a command start a new process.
		 *
		 * @param command the command
		 * @param run creates each run's process
		 */
		void repeat(String command, Supplier<FakeProcess> run)
		{
			repeated.put(command, run);
		}

		/**
		 * Returns how many times a command started.
		 *
		 * @param command the command
		 * @return the number of starts
		 */
		int starts(String command)
		{
			synchronized (starts)
			{
				return starts.getOrDefault(command, 0);
			}
		}

		/**
		 * Returns when a command started.
		 *
		 * @param command the command
		 * @return the time since the wait began at each start, in order
		 */
		List<Duration> startTimes(String command)
		{
			synchronized (starts)
			{
				return List.copyOf(startTimes.getOrDefault(command, List.of()));
			}
		}

		/**
		 * Returns whether the wait destroyed a run of a command.
		 *
		 * @param command the command
		 * @return {@code true} if any of the command's runs was destroyed
		 */
		boolean destroyed(String command)
		{
			synchronized (starts)
			{
				return started.getOrDefault(command, List.of()).stream().anyMatch(process -> process.destroyed);
			}
		}

		/**
		 * Returns whether the wait closed every run of a command.
		 *
		 * @param command the command
		 * @return {@code true} if the command started and each of its runs was closed
		 */
		boolean allClosed(String command)
		{
			synchronized (starts)
			{
				List<FakeProcess> runs = started.getOrDefault(command, List.of());
				return !runs.isEmpty() && runs.stream().allMatch(process -> process.closed);
			}
		}

		@Override
		public Process start(String command) throws IOException
		{
			synchronized (starts)
			{
				starts.merge(command, 1, Integer::sum);
				startTimes.computeIfAbsent(command, key -> new ArrayList<>()).add(timer.elapsed());
				FakeProcess process;
				Supplier<FakeProcess> run = repeated.get(command);
				if (run != null)
					process = run.get();
				else
				{
					Deque<FakeProcess> runs = processes.get(command);
					if (runs == null || runs.isEmpty())
						throw new AssertionError("Unexpected run of " + command);
					process = runs.removeFirst();
				}
				started.computeIfAbsent(command, key -> new ArrayList<>()).add(process);
				return process;
			}
		}
	}

	/**
	 * A process whose output, exit status and duration a test scripts.
	 */
	private static final class FakeProcess extends Process
	{
		private final byte[] output;
		private final CompletableFuture<Process> exit = new CompletableFuture<>();
		private volatile int status;
		volatile boolean destroyed;
		volatile boolean closed;

		/**
		 * Creates a process.
		 *
		 * @param output the process's output
		 * @param status the exit status
		 */
		private FakeProcess(String output, int status)
		{
			this.output = output.getBytes(UTF_8);
			this.status = status;
		}

		/**
		 * Returns a process that has already exited.
		 *
		 * @param status the exit status
		 * @param output the process's output
		 * @return the process
		 */
		static FakeProcess exits(int status, String output)
		{
			FakeProcess process = new FakeProcess(output, status);
			process.exit.complete(process);
			return process;
		}

		/**
		 * Returns a process that exits once a delay passes on a timer.
		 *
		 * @param timer  the timer that measures the delay
		 * @param delay  the time until the process exits
		 * @param status the exit status
		 * @param output the process's output
		 * @return the process
		 */
		static FakeProcess exitsAfter(ManualTimer timer, Duration delay, int status, String output)
		{
			FakeProcess process = new FakeProcess(output, status);
			timer.schedule(delay, () -> process.exit.complete(process));
			return process;
		}

		/**
		 * Returns a process that runs until it is destroyed.
		 *
		 * @param output the process's output
		 * @return the process
		 */
		static FakeProcess running(String output)
		{
			return new FakeProcess(output, 143);
		}

		@Override
		public OutputStream getOutputStream()
		{
			return OutputStream.nullOutputStream();
		}

		@Override
		public InputStream getInputStream()
		{
			return new ByteArrayInputStream(output);
		}

		@Override
		public InputStream getErrorStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor()
		{
			exit.join();
			return status;
		}

		@Override
		public int exitValue()
		{
			if (!exit.isDone())
				throw new IllegalThreadStateException("The process is running");
			return status;
		}

		@Override
		public void destroy()
		{
			destroyed = true;
			status = 143;
			exit.complete(this);
		}

		@Override
		public void close() throws IOException
		{
			closed = true;
			super.close();
		}

		@Override
		public boolean isAlive()
		{
			return !exit.isDone();
		}

		@Override
		public CompletableFuture<Process> onExit()
		{
			return exit;
		}

		@Override
		public Stream<ProcessHandle> descendants()
		{
			return Stream.empty();
		}

		@Override
		public Stream<ProcessHandle> children()
		{
			return Stream.empty();
		}
	}
}
