package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.HarnessProcessTermination;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Executes owned native commands and guarantees they do not outlive their caller.
 */
public final class ProcessExecution
{
	private static final Set<Process> ACTIVE_PROCESSES = ConcurrentHashMap.newKeySet();

	/**
	 * Prevents construction of this static utility.
	 */
	private ProcessExecution()
	{
	}

	/**
	 * Runs a shell-free build command and reports its retained output when the build fails.
	 *
	 * @param temporaryRoot the CAT work directory that owns transient build diagnostics
	 * @param command the explicit build command arguments
	 * @param workingDirectory the plan-declared build directory
	 * @throws IOException if the build cannot start, is interrupted, or exits unsuccessfully
	 */
	public static void runBuild(Path temporaryRoot, List<String> command, Path workingDirectory) throws IOException
	{
		runBuild(temporaryRoot, command, workingDirectory, Duration.ofMinutes(10));
	}

	/**
	 * Runs a shell-free build command for a bounded duration and reports its retained output when it cannot finish.
	 *
	 * @param temporaryRoot the CAT work directory that owns transient build diagnostics
	 * @param command the explicit build command arguments
	 * @param workingDirectory the plan-declared build directory
	 * @param timeout the maximum time the build may own the evaluator
	 * @throws IOException if the build cannot start, is interrupted, times out, or exits unsuccessfully
	 */
	public static void runBuild(Path temporaryRoot, List<String> command, Path workingDirectory, Duration timeout)
		throws IOException
	{
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(command, "command").isNotEmpty();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(timeout, "timeout").isGreaterThan(Duration.ZERO);
		Path temporary = temporaryRoot.toAbsolutePath().normalize();
		Path invocation = InvocationDirectory.create(temporary, "prompt-evaluation-build");
		Path output = invocation.resolve("build.log");
		try
		{
			List<String> effectiveCommand = commandWithScriptInterpreter(command);
			ProcessBuilder builder = new ProcessBuilder(effectiveCommand).directory(workingDirectory.toFile()).
				redirectErrorStream(true).redirectOutput(output.toFile());
			try (Process process = start(builder))
			{
				try
				{
					if (!waitFor(process, timeout, "build command", effectiveCommand))
					{
						HarnessProcessTermination.terminate(process, "build command");
						String diagnostic = Files.readString(output, StandardCharsets.UTF_8);
						throw new IOException("Build command timed out after " + timeout + ": " + effectiveCommand + "\n" +
							diagnostic);
					}
					int exitCode = process.exitValue();
					if (exitCode != 0)
					{
						String diagnostic = Files.readString(output, StandardCharsets.UTF_8);
						throw new IOException("Build command failed with exit code " + exitCode + ": " + effectiveCommand + "\n" +
							diagnostic);
					}
				}
				finally
				{
					finish(process);
				}
			}
		}
		finally
		{
			InvocationDirectory.delete(temporary, invocation);
		}
	}

	/**
	 * Runs one short runner lifecycle query and returns its standard output.
	 *
	 * @param runner the runner launcher
	 * @param temporaryRoot the CAT work directory that owns the query's transient output
	 * @param arguments the lifecycle command and its arguments
	 * @return the runner's UTF-8 standard output
	 * @throws IOException if the runner exits unsuccessfully or cannot be read
	 */
	public static String runnerOutput(Path runner, Path temporaryRoot, String... arguments) throws IOException
	{
		requireThat(runner, "runner").isNotNull();
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(arguments, "arguments").isNotNull();
		List<String> command = new ArrayList<>();
		command.add(runner.toString());
		command.addAll(List.of(arguments));
		List<String> effectiveCommand = commandWithScriptInterpreter(command);
		Path temporary = temporaryRoot.toAbsolutePath().normalize();
		Path invocation = InvocationDirectory.create(temporary, "prompt-evaluation-runner-query");
		Path output = invocation.resolve("output.txt");
		ProcessBuilder builder = new ProcessBuilder(effectiveCommand).redirectError(ProcessBuilder.Redirect.INHERIT).
			redirectOutput(output.toFile());
		try (Process process = start(builder))
		{
			try
			{
				int exitCode = waitFor(process, "harness runner", effectiveCommand);
				String result = Files.readString(output, StandardCharsets.UTF_8);
				if (exitCode != 0)
				{
					throw new IOException("Harness runner " + String.join(" ", arguments) + " failed with exit code " +
						exitCode + ": " + result);
				}
				return result;
			}
			finally
			{
				finish(process);
			}
		}
		finally
		{
			InvocationDirectory.delete(temporary, invocation);
		}
	}

	/**
	 * Runs one declared command and retains its combined output.
	 *
	 * @param command the shell-free command arguments
	 * @param directory the owned working directory
	 * @param input the JSONL protocol input for the command
	 * @param output the retained command-output path
	 * @return the process-start and completion timing
	 * @throws IOException if process execution fails
	 */
	static Timing run(List<String> command, Path directory, String input, Path output) throws IOException
	{
		requireThat(command, "command").isNotEmpty();
		requireThat(directory, "directory").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		List<String> effectiveCommand = commandWithScriptInterpreter(command);
		ProcessBuilder builder = new ProcessBuilder(effectiveCommand);
		builder.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
		return run(effectiveCommand, builder, input);
	}

	/**
	 * Runs a command whose structured output and diagnostics have separate retained destinations.
	 *
	 * @param command the shell-free command arguments
	 * @param directory the owned working directory
	 * @param input the protocol input for the command
	 * @param output the retained structured-result path
	 * @param diagnostic the retained diagnostic-output path
	 * @return the process-start and completion timing
	 * @throws IOException if process execution fails
	 */
	public static Timing runStructured(List<String> command, Path directory, String input, Path output, Path diagnostic)
		throws IOException
	{
		requireThat(command, "command").isNotEmpty();
		requireThat(directory, "directory").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(diagnostic, "diagnostic").isNotNull();
		List<String> effectiveCommand = commandWithScriptInterpreter(command);
		ProcessBuilder builder = new ProcessBuilder(effectiveCommand);
		builder.directory(directory.toFile()).redirectOutput(output.toFile()).redirectError(diagnostic.toFile());
		return run(effectiveCommand, builder, input);
	}

	/**
	 * Uses a script's declared interpreter instead of asking the operating system to execute a freshly written script.
	 *
	 * <p>
	 * Linux can reject direct execution of a script while another process still holds its executable image open. Invoking
	 * its declared interpreter reads the closed script as data, so the same command remains executable without retrying,
	 * suppressing the error, or weakening process ownership. Native executables and PATH-resolved commands remain
	 * unchanged.
	 *
	 * @param command the requested executable and arguments
	 * @return the equivalent command that invokes a declared script interpreter when applicable
	 * @throws IOException if the executable cannot be read while identifying its declared interpreter
	 */
	private static List<String> commandWithScriptInterpreter(List<String> command) throws IOException
	{
		Path executable = Path.of(command.getFirst());
		if (!Files.isRegularFile(executable))
			return command;
		String header;
		try (InputStream input = Files.newInputStream(executable))
		{
			header = new String(input.readNBytes(256), StandardCharsets.UTF_8);
		}
		int lineEnd = header.indexOf('\n');
		String firstLine = header;
		if (lineEnd != -1)
			firstLine = header.substring(0, lineEnd);
		if (!firstLine.startsWith("#!"))
			return command;
		String[] interpreter = firstLine.substring(2).strip().split("\\s+");
		if (interpreter.length == 0 || interpreter[0].isBlank())
			return command;
		List<String> result = new ArrayList<>(command.size() + interpreter.length);
		result.addAll(List.of(interpreter));
		result.add(executable.toString());
		result.addAll(command.subList(1, command.size()));
		return List.copyOf(result);
	}

	/**
	 * Stops every native command owned by the current evaluator process.
	 */
	static void interruptActive()
	{
		ACTIVE_PROCESSES.forEach(HarnessProcessTermination::terminateImmediately);
	}

	/**
	 * Starts a process and records ownership before any caller can wait on it.
	 *
	 * @param builder the completely configured native-process builder
	 * @return the owned process
	 * @throws IOException if the process cannot start
	 */
	private static Process start(ProcessBuilder builder) throws IOException
	{
		Process process = builder.start();
		ACTIVE_PROCESSES.add(process);
		return process;
	}

	/**
	 * Runs a configured process after its output destinations have been selected.
	 *
	 * @param command the shell-free command arguments
	 * @param builder the configured process builder
	 * @param input the protocol input for the command
	 * @return the process-start and completion timing
	 * @throws IOException if process execution fails
	 */
	private static Timing run(List<String> command, ProcessBuilder builder, String input) throws IOException
	{
		try (Process process = start(builder))
		{
			long startedAtNanos = System.nanoTime();
			try
			{
				try (OutputStream processInput = process.getOutputStream())
				{
					processInput.write(input.getBytes(StandardCharsets.UTF_8));
				}
				int exitCode = waitFor(process, "execution command", command);
				if (exitCode != 0)
					throw new IOException("Execution command failed: " + command);
				return new Timing(startedAtNanos, System.nanoTime());
			}
			finally
			{
				finish(process);
			}
		}
	}

	/**
	 * Waits for one owned process and terminates it when waiting is interrupted.
	 *
	 * @param process the process to wait for
	 * @param role the process's reader-visible role
	 * @param command the shell-free command arguments
	 * @return the native exit code
	 * @throws IOException if waiting is interrupted
	 */
	private static int waitFor(Process process, String role, List<String> command) throws IOException
	{
		try
		{
			return process.waitFor();
		}
		catch (InterruptedException e)
		{
			HarnessProcessTermination.terminateImmediately(process);
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for " + role + "; terminated: " + command, e);
		}
	}

	/**
	 * Waits for a process only through its declared ownership duration.
	 *
	 * @param process the owned process
	 * @param timeout the maximum wait
	 * @param role the process's reader-visible role
	 * @param command the shell-free command arguments
	 * @return {@code true} if the process ended within the timeout
	 * @throws IOException if waiting is interrupted
	 */
	private static boolean waitFor(Process process, Duration timeout, String role, List<String> command)
		throws IOException
	{
		try
		{
			return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e)
		{
			HarnessProcessTermination.terminateImmediately(process);
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for " + role + "; terminated: " + command, e);
		}
	}

	/**
	 * Releases native-process ownership and prevents a surviving descendant from mutating evidence.
	 *
	 * @param process the completed or interrupted process
	 */
	private static void finish(Process process)
	{
		ACTIVE_PROCESSES.remove(process);
		if (process.isAlive())
			HarnessProcessTermination.terminateImmediately(process);
	}

	/**
	 * Captures the process-start and completion boundaries of one configured command.
	 *
	 * @param startedAtNanos the monotonic time immediately after the native process started
	 * @param completedAtNanos the monotonic time immediately after the native process exited
	 */
	record Timing(long startedAtNanos, long completedAtNanos)
	{
		/**
		 * Validates process timing boundaries.
		 *
		 * @param startedAtNanos the monotonic time immediately after the native process started
		 * @param completedAtNanos the monotonic time immediately after the native process exited
		 */
		Timing
		{
			if (startedAtNanos < 0)
				throw new IllegalArgumentException("startedAtNanos must not be negative");
			if (completedAtNanos < startedAtNanos)
				throw new IllegalArgumentException("completedAtNanos must not precede startedAtNanos");
		}
	}
}
