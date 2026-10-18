package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Waits until the first of one or more external operations finishes, such as a hosted build, a deployment or a
 * review, or until a deadline passes, so that an agent can block on one process instead of checking the operations
 * itself.
 * <p>
 * Usage: {@code wait-until --deadline <duration> [--interval <duration>] (--check <command> | --watch <command>)...}.
 * A duration is a whole number followed by {@code ms}, {@code s}, {@code m} or {@code h}, such as {@code 30m}.
 * <ul>
 *   <li>{@code --check <command>} names a command that looks up an operation's state once and exits with 0 when the
 *   operation finished successfully, 1 when it finished unsuccessfully, or 75 while it has not finished. The command
 *   runs at once and again each {@code --interval} (30 seconds by default) after its previous run ends, until it
 *   reports that the operation finished.</li>
 *   <li>{@code --watch <command>} names a command that blocks until an operation finishes and exits with 0 when it
 *   finished successfully, or with any other status when it did not, such as {@code gh run watch <run> --exit-status}.
 *   It runs once.</li>
 * </ul>
 * The commands run in the harness's shell, in the current folder.
 * <p>
 * As soon as one operation finishes, the wait prints {@code succeeded: <command>} or {@code failed: <command>}
 * followed by the command's last output, stops every other command, and exits with 0 or 1 respectively. When the
 * deadline passes first, it prints each command with its last output, stops them, and exits with 124. When a command
 * cannot start, or a check exits with any other status three times in a row, it prints that command and its last
 * output, stops the others, and exits with 3. A usage error exits with 2. Running the same wait again after any of
 * these is safe: each check looks the operation's state up anew, and a watch of a finished operation ends at once.
 */
public final class WaitUntil
{
	/**
	 * Starts the process that runs one command.
	 */
	@FunctionalInterface
	public interface CommandStarter
	{
		/**
		 * Starts a command.
		 *
		 * @param command the command, in the harness's shell
		 * @return the process, whose standard output and standard error arrive through
		 *   {@link Process#getInputStream()}
		 * @throws IOException if the command cannot start
		 */
		Process start(String command) throws IOException;
	}

	/**
	 * The exit status of a check whose operation has not finished.
	 */
	private static final int PENDING = 75;
	/**
	 * The exit status of a wait whose deadline passed first.
	 */
	private static final int DEADLINE_PASSED = 124;
	/**
	 * The exit status of a wait that ended because a command cannot start or a check misbehaved.
	 */
	private static final int COMMAND_ERROR = 3;
	/**
	 * The number of consecutive unexpected exit statuses after which a check ends the wait.
	 */
	private static final int MAXIMUM_UNEXPECTED = 3;
	/**
	 * The number of bytes of each command's output that the wait keeps, counted from the end of that output.
	 */
	private static final int OUTPUT_TAIL_BYTES = 16_384;
	/**
	 * How long the wait keeps reading a command's output after the command exits. A process that the command started
	 * in the background can hold the output open after the command itself exits.
	 */
	private static final Duration OUTPUT_GRACE = Duration.ofSeconds(1);
	private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h)");
	private static final String USAGE = "usage: wait-until --deadline <duration> [--interval <duration>] " +
		"(--check <command> | --watch <command>)...\n" +
		"A duration is a whole number followed by ms, s, m or h, such as 30m.";

	private final CommandStarter starter;
	private final PrintStream output;
	private final PrintStream error;
	/**
	 * The commands of the current wait.
	 */
	private final List<Command> commands = new ArrayList<>();
	/**
	 * The commands whose current run ended, in the order that they ended.
	 */
	private final BlockingQueue<Command> ended = new LinkedBlockingQueue<>();

	/**
	 * Creates a wait.
	 *
	 * @param starter starts the commands
	 * @param output receives the outcome
	 * @param error receives usage errors
	 * @throws NullPointerException if any argument is null
	 */
	public WaitUntil(CommandStarter starter, PrintStream output, PrintStream error)
	{
		requireThat(starter, "starter").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(error, "error").isNotNull();
		this.starter = starter;
		this.output = output;
		this.error = error;
	}

	/**
	 * Returns a starter that runs each command in a shell, in a folder.
	 *
	 * @param shell returns the program and arguments that run a command in the harness's shell
	 * @param directory the folder that the commands run in
	 * @return the starter
	 * @throws NullPointerException if any argument is null
	 */
	public static CommandStarter shellStarter(Function<String, List<String>> shell, Path directory)
	{
		requireThat(shell, "shell").isNotNull();
		requireThat(directory, "directory").isNotNull();
		return command ->
		{
			Process process = new ProcessBuilder(shell.apply(command)).directory(directory.toFile()).
				redirectErrorStream(true).start();
			process.getOutputStream().close();
			return process;
		};
	}

	/**
	 * Waits until the first operation finishes or the deadline passes.
	 *
	 * @param args the command-line arguments
	 * @return 0 if the first operation to finish succeeded, 1 if it failed, 2 for a usage error, 3 if a command cannot
	 *   start or a check exits with an unexpected status three times in a row, or 124 if the deadline passed first
	 * @throws NullPointerException if {@code args} is null
	 * @throws InterruptedException if the thread is interrupted while waiting; the commands are stopped first
	 */
	public int run(List<String> args) throws InterruptedException
	{
		requireThat(args, "args").isNotNull();
		Duration deadline = null;
		Duration interval = Duration.ofSeconds(30);
		for (int i = 0; i < args.size(); i += 2)
		{
			String option = args.get(i);
			if (i + 1 == args.size())
				return usage("Missing the value of " + option);
			String value = args.get(i + 1);
			switch (option)
			{
				case "--deadline" ->
				{
					deadline = parseDuration(value).orElse(null);
					if (deadline == null)
						return usage("Malformed duration: " + value);
				}
				case "--interval" ->
				{
					interval = parseDuration(value).orElse(null);
					if (interval == null)
						return usage("Malformed duration: " + value);
				}
				case "--check" -> commands.add(new Command(value, false));
				case "--watch" -> commands.add(new Command(value, true));
				default ->
				{
					return usage("Unknown option: " + option);
				}
			}
		}
		if (deadline == null)
			return usage("Missing --deadline");
		if (commands.isEmpty())
			return usage("Missing --check or --watch");
		try
		{
			return await(deadline, interval);
		}
		finally
		{
			terminate();
		}
	}

	/**
	 * Prints a usage error.
	 *
	 * @param problem what is wrong with the arguments
	 * @return the exit status of a usage error
	 */
	private int usage(String problem)
	{
		error.println(problem);
		error.println(USAGE);
		return 2;
	}

	/**
	 * Parses a duration.
	 *
	 * @param value a whole number followed by {@code ms}, {@code s}, {@code m} or {@code h}
	 * @return the positive duration, or empty if {@code value} is malformed or zero
	 */
	private static Optional<Duration> parseDuration(String value)
	{
		Matcher matcher = DURATION.matcher(value);
		if (!matcher.matches())
			return Optional.empty();
		long amount;
		try
		{
			amount = Long.parseLong(matcher.group(1));
		}
		catch (NumberFormatException _)
		{
			return Optional.empty();
		}
		if (amount == 0)
			return Optional.empty();
		Duration duration = switch (matcher.group(2))
		{
			case "ms" -> Duration.ofMillis(amount);
			case "s" -> Duration.ofSeconds(amount);
			case "m" -> Duration.ofMinutes(amount);
			default -> Duration.ofHours(amount);
		};
		return Optional.of(duration);
	}

	/**
	 * Runs the commands until the first operation finishes, a command misbehaves or the deadline passes.
	 *
	 * @param deadline the time after which the wait ends
	 * @param interval the time between the end of a check's run and its next run
	 * @return the wait's exit status
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	private int await(Duration deadline, Duration interval) throws InterruptedException
	{
		long start = System.nanoTime();
		long deadlineAt = start + deadline.toNanos();
		for (Command command : commands)
		{
			command.nextRunAt = start;
			if (command.watch && !command.start())
				return commandError(command);
		}
		while (true)
		{
			long now = System.nanoTime();
			long wakeAt = deadlineAt;
			for (Command command : commands)
			{
				if (command.watch || command.run != null)
					continue;
				if (command.nextRunAt - now <= 0)
				{
					if (!command.start() && command.unexpected >= MAXIMUM_UNEXPECTED)
						return commandError(command);
					if (command.run == null)
						command.nextRunAt = now + interval.toNanos();
				}
				if (command.run == null && command.nextRunAt - wakeAt < 0)
					wakeAt = command.nextRunAt;
			}
			if (deadlineAt - now <= 0)
				return deadlinePassed(deadline);
			Command command = ended.poll(wakeAt - now, TimeUnit.NANOSECONDS);
			if (command == null)
				continue;
			int status = command.finish();
			if (command.watch)
				return finished(command, status == 0);
			switch (status)
			{
				case 0 ->
				{
					return finished(command, true);
				}
				case 1 ->
				{
					return finished(command, false);
				}
				case PENDING -> command.unexpected = 0;
				default ->
				{
					++command.unexpected;
					if (command.unexpected >= MAXIMUM_UNEXPECTED)
						return commandError(command);
				}
			}
			command.nextRunAt = System.nanoTime() + interval.toNanos();
		}
	}

	/**
	 * Reports the operation that finished first.
	 *
	 * @param command the command that reported it
	 * @param succeeded {@code true} if the operation succeeded
	 * @return 0 if the operation succeeded, otherwise 1
	 */
	private int finished(Command command, boolean succeeded)
	{
		if (succeeded)
			output.println("succeeded: " + command.text);
		else
			output.println("failed: " + command.text);
		printOutput(command.lastOutput);
		if (succeeded)
			return 0;
		return 1;
	}

	/**
	 * Reports a command that cannot start or a check that exited with unexpected statuses.
	 *
	 * @param command the command
	 * @return the exit status of a command error
	 */
	private int commandError(Command command)
	{
		if (command.watch)
			output.println("command error: " + command.text);
		else
		{
			output.println("command error: " + command.text + " (" + command.unexpected +
				" runs in a row ended with a status other than 0, 1 or " + PENDING + ")");
		}
		printOutput(command.lastOutput);
		return COMMAND_ERROR;
	}

	/**
	 * Reports each command's last output after the deadline passed.
	 *
	 * @param deadline the deadline
	 * @return the exit status of a deadline that passed
	 */
	private int deadlinePassed(Duration deadline)
	{
		output.println("deadline passed after " + deadline + " before any operation finished");
		for (Command command : commands)
		{
			output.println("pending: " + command.text);
			Run run = command.run;
			if (command.watch && run != null)
				printOutput(run.output());
			else
				printOutput(command.lastOutput);
		}
		return DEADLINE_PASSED;
	}

	/**
	 * Prints a command's output, ending it with a line break.
	 *
	 * @param text the output
	 */
	private void printOutput(String text)
	{
		if (text.isEmpty())
			return;
		output.print(text);
		if (!text.endsWith("\n"))
			output.println();
	}

	/**
	 * Stops every command that is still running, together with the processes that it started. A caller that is
	 * terminated before the wait ends uses this to leave no command behind.
	 */
	public void terminate()
	{
		for (Command command : List.copyOf(commands))
		{
			Run run = command.run;
			if (run != null)
				run.stop();
		}
	}

	/**
	 * One {@code --check} or {@code --watch} command and the state of its runs.
	 */
	private final class Command
	{
		final String text;
		/**
		 * {@code true} for a {@code --watch} command, {@code false} for a {@code --check} command.
		 */
		final boolean watch;
		/**
		 * The current run, or {@code null} between runs.
		 */
		volatile Run run;
		/**
		 * The output of the last run that ended, or the reason that the last run could not start.
		 */
		String lastOutput = "";
		/**
		 * The {@link System#nanoTime()} at which a check's next run is due.
		 */
		long nextRunAt;
		/**
		 * The number of consecutive runs that could not start or ended with an unexpected status.
		 */
		int unexpected;

		/**
		 * Creates a command.
		 *
		 * @param text the command
		 * @param watch {@code true} for a {@code --watch} command
		 */
		Command(String text, boolean watch)
		{
			this.text = text;
			this.watch = watch;
		}

		/**
		 * Starts a run, which adds this command to {@link #ended} when its process exits.
		 *
		 * @return {@code false} if the run cannot start
		 */
		boolean start()
		{
			try
			{
				run = new Run(starter.start(text), () -> ended.add(this));
			}
			catch (IOException e)
			{
				++unexpected;
				lastOutput = e.getMessage() + "\n";
				return false;
			}
			return true;
		}

		/**
		 * Ends the current run.
		 *
		 * @return the run's exit status
		 * @throws InterruptedException if the thread is interrupted while the run's output is read
		 */
		int finish() throws InterruptedException
		{
			int status = run.finish();
			lastOutput = run.output();
			run = null;
			return status;
		}
	}

	/**
	 * One run of a command, which owns the run's process and reads its output.
	 */
	private static final class Run
	{
		private final Process process;
		/**
		 * Collects the run's output.
		 */
		private final OutputTail tail = new OutputTail();
		/**
		 * The reader of the run's output, which completes when the output ends.
		 */
		private final CompletableFuture<Void> reader;

		/**
		 * Starts reading the output of a started process.
		 *
		 * @param process the started process, which the run closes when it finishes
		 * @param onExit runs when the process exits
		 */
		Run(Process process, Runnable onExit)
		{
			this.process = process;
			reader = CompletableFuture.runAsync(() -> tail.readFrom(process.getInputStream()),
				Thread::startVirtualThread);
			process.onExit().thenRun(onExit);
		}

		/**
		 * Ends the run after its process exited: waits briefly for the rest of its output, then closes the process.
		 *
		 * @return the process's exit status
		 * @throws InterruptedException if the thread is interrupted while the output is read
		 */
		int finish() throws InterruptedException
		{
			try
			{
				reader.get(OUTPUT_GRACE.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (ExecutionException | TimeoutException _)
			{
				// Keep the output read so far
			}
			int status = process.exitValue();
			try
			{
				process.close();
			}
			catch (IOException _)
			{
				// The exit status and the output are already read, so a stream that fails to close changes no result
			}
			return status;
		}

		/**
		 * Returns the output read so far.
		 *
		 * @return the end of the run's output
		 */
		String output()
		{
			return tail.text();
		}

		/**
		 * Stops the run's process and the processes that it started.
		 */
		void stop()
		{
			process.descendants().forEach(ProcessHandle::destroy);
			process.destroy();
		}
	}

	/**
	 * Keeps the end of a command's output.
	 */
	private static final class OutputTail
	{
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		/**
		 * Reads a stream until it ends, keeping its last {@link #OUTPUT_TAIL_BYTES} bytes.
		 *
		 * @param stream the stream
		 */
		void readFrom(InputStream stream)
		{
			byte[] buffer = new byte[8192];
			try (stream)
			{
				while (true)
				{
					int count = stream.read(buffer);
					if (count < 0)
						return;
					append(buffer, count);
				}
			}
			catch (IOException _)
			{
				// The process closed its output, such as when it was stopped
			}
		}

		/**
		 * Appends bytes, dropping the oldest ones beyond the limit.
		 *
		 * @param buffer the bytes
		 * @param count the number of bytes to append
		 */
		private void append(byte[] buffer, int count)
		{
			synchronized (bytes)
			{
				bytes.write(buffer, 0, count);
				if (bytes.size() > 2 * OUTPUT_TAIL_BYTES)
				{
					byte[] all = bytes.toByteArray();
					bytes.reset();
					bytes.write(all, all.length - OUTPUT_TAIL_BYTES, OUTPUT_TAIL_BYTES);
				}
			}
		}

		/**
		 * Returns the kept output.
		 *
		 * @return the last {@link #OUTPUT_TAIL_BYTES} bytes, decoded as UTF-8 with malformed bytes replaced
		 */
		String text()
		{
			byte[] all;
			synchronized (bytes)
			{
				all = bytes.toByteArray();
			}
			int from = Math.max(0, all.length - OUTPUT_TAIL_BYTES);
			try
			{
				return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).
					onUnmappableCharacter(CodingErrorAction.REPLACE).
					decode(ByteBuffer.wrap(all, from, all.length - from)).toString();
			}
			catch (java.nio.charset.CharacterCodingException e)
			{
				throw new AssertionError(e);
			}
		}
	}
}
