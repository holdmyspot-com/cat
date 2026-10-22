package com.holdmyspot.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names the host tools that a trial's shell commands ran, with their versions.
 * <p>
 * A host tool is a program that one of the trial's agents ran by name in a shell command, as {@link ShellCommands}
 * finds it, that the host environment's {@code PATH} finds. A program that the command names by its path, such as a
 * command that the prompt ships or a program that the trial built, is not one. Each tool's version is the first
 * version number that {@code <program> --version} prints. The version of each program file is read once, however
 * many trials run it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class HostTools
{
	/**
	 * How long {@code <program> --version} may run.
	 */
	private static final int TIMEOUT_SECONDS = 5;
	/**
	 * The most output of {@code <program> --version} that is read.
	 */
	private static final int MAXIMUM_OUTPUT = 4096;
	/**
	 * A version number of two or more parts, such as {@code 2.51.0}.
	 */
	private static final Pattern DOTTED_VERSION = Pattern.compile("\\d+(?:\\.\\d+)+");
	/**
	 * A word that is a whole number, such as the {@code 27} of {@code openjdk 27 2026-09-15}.
	 */
	private static final Pattern NUMBER = Pattern.compile("(?<![\\w.-])\\d+(?![\\w.-])");
	private final Map<String, String> environment;
	private final Launcher launcher;
	/**
	 * The description of each program file's version, keyed by the file.
	 */
	private final Map<Path, String> versions = new HashMap<>();
	private final Object lock = new Object();

	/**
	 * Creates the host tools of a session port.
	 *
	 * @param environment the host environment, whose {@code PATH} finds each program
	 * @param launcher    runs {@code <program> --version}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public HostTools(Map<String, String> environment, Launcher launcher)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
		this.environment = Map.copyOf(environment);
		this.launcher = launcher;
	}

	/**
	 * Returns the launcher that runs {@code <program> --version} as a process. The process runs in a new empty
	 * directory, which is also its home directory, with the scrubbed host environment and no standard input. Its
	 * standard error joins its standard output. A process that runs longer than 5 seconds or prints more than 4096
	 * bytes is stopped, with its descendants.
	 *
	 * @param environment the host environment
	 * @param scratch     the directory that holds each process's directory while it runs
	 * @return the launcher
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Launcher processes(Map<String, String> environment, Path scratch)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(scratch, "scratch").isNotNull();
		Map<String, String> scrubbed = HarnessEnvironment.scrub(environment);
		return program ->
		{
			Path directory = Files.createTempDirectory(Files.createDirectories(scratch), "version-");
			try
			{
				return run(program, scrubbed, directory);
			}
			finally
			{
				TemporaryDirectories.deleteRecursively(directory);
			}
		};
	}

	/**
	 * Runs {@code <program> --version}.
	 *
	 * @param program     the program's file
	 * @param environment the scrubbed host environment
	 * @param directory   the process's working and home directory
	 * @return what happened
	 * @throws InterruptedException if the thread is interrupted while it waits for the process
	 */
	private static Outcome run(Path program, Map<String, String> environment, Path directory)
		throws InterruptedException
	{
		ProcessBuilder builder = new ProcessBuilder(program.toString(), "--version").directory(directory.toFile()).
			redirectErrorStream(true);
		Map<String, String> variables = builder.environment();
		variables.clear();
		variables.putAll(environment);
		HarnessEnvironment.setHomeDirectory(variables, directory);
		Process process;
		try
		{
			process = builder.start();
			process.getOutputStream().close();
		}
		catch (IOException _)
		{
			return new Unfinished("--version could not start");
		}
		AtomicBoolean overflowed = new AtomicBoolean();
		AtomicReference<byte[]> output = new AtomicReference<>(new byte[0]);
		Thread reader = Thread.ofPlatform().daemon().name("host-tool-version").start(() ->
		{
			try (InputStream input = process.getInputStream())
			{
				output.set(input.readNBytes(MAXIMUM_OUTPUT + 1));
				if (output.get().length > MAXIMUM_OUTPUT)
				{
					overflowed.set(true);
					stop(process);
				}
			}
			catch (IOException _)
			{
				// The process ended or was stopped while its output was read; the output read so far is kept
			}
		});
		try
		{
			boolean exited = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			stop(process);
			boolean read = reader.join(Duration.ofSeconds(TIMEOUT_SECONDS));
			if (overflowed.get())
				return new Unfinished("--version printed more than " + MAXIMUM_OUTPUT + " bytes");
			if (!exited || !read)
				return new Unfinished("--version did not finish within " + TIMEOUT_SECONDS + " seconds");
			return new Exited(process.exitValue(), new String(output.get(), UTF_8));
		}
		finally
		{
			stop(process);
		}
	}

	/**
	 * Stops a process and its descendants.
	 *
	 * @param process the process
	 */
	private static void stop(Process process)
	{
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
	}

	/**
	 * Returns the host tools that a trial ran.
	 *
	 * @param evidence the trial's recorded activity
	 * @return one entry per host tool in name order, each the tool's name followed by its version or by why its version
	 *   is unknown; empty if the trial ran no host tool
	 * @throws NullPointerException if {@code evidence} is null
	 * @throws IOException          if a version process's directory cannot be created or deleted
	 * @throws InterruptedException if the thread is interrupted while a version is read
	 */
	public List<String> describe(SessionEvidence evidence) throws IOException, InterruptedException
	{
		requireThat(evidence, "evidence").isNotNull();
		Map<String, Path> tools = new TreeMap<>();
		Platform platform = Platform.current();
		for (SessionEvidence.Entry entry : evidence.entries())
		{
			if (!(entry instanceof SessionEvidence.Tool tool) || !tool.command())
				continue;
			for (String name : ShellCommands.programs(tool.input()))
			{
				String file = platform.findProgram(name, environment);
				if (!file.equals(name))
					tools.put(name, Path.of(file));
			}
		}
		List<String> entries = new ArrayList<>();
		for (Map.Entry<String, Path> tool : tools.entrySet())
			entries.add(tool.getKey() + " " + version(tool.getValue()));
		return entries;
	}

	/**
	 * Returns the description of a program file's version, reading it the first time.
	 *
	 * @param program the program's file
	 * @return the version number, or why it is unknown in parentheses
	 * @throws IOException          if the version process's directory cannot be created or deleted
	 * @throws InterruptedException if the thread is interrupted while the version is read
	 */
	private String version(Path program) throws IOException, InterruptedException
	{
		synchronized (lock)
		{
			String version = versions.get(program);
			if (version == null)
			{
				version = describe(launcher.run(program));
				versions.put(program, version);
			}
			return version;
		}
	}

	/**
	 * Describes the version that {@code <program> --version} reported.
	 *
	 * @param outcome what happened when it ran
	 * @return the version number, or why it is unknown in parentheses
	 */
	private static String describe(Outcome outcome)
	{
		if (outcome instanceof Exited(int status, String output) && status == 0)
		{
			String number = versionNumber(output);
			if (number != null)
				return number;
		}
		String reason = switch (outcome)
		{
			case Unfinished(String why) -> why;
			case Exited(int status, String _) when status != 0 -> "--version exited with status " + status;
			case Exited _ -> "--version printed no version number";
		};
		return "(no version: " + reason + ")";
	}

	/**
	 * Returns the version number in a program's {@code --version} output.
	 *
	 * @param output the output
	 * @return the first number of two or more parts on the first non-blank line, else that line's first whole number,
	 *   or {@code null} if it has neither
	 */
	private static String versionNumber(String output)
	{
		String line = output.lines().filter(text -> !text.isBlank()).findFirst().orElse("");
		Matcher dotted = DOTTED_VERSION.matcher(line);
		if (dotted.find())
			return dotted.group();
		Matcher number = NUMBER.matcher(line);
		if (number.find())
			return number.group();
		return null;
	}

	/**
	 * Runs {@code <program> --version}.
	 */
	@FunctionalInterface
	public interface Launcher
	{
		/**
		 * Runs {@code <program> --version}.
		 *
		 * @param program the program's file
		 * @return what happened
		 * @throws IOException          if the process's directory cannot be created or deleted
		 * @throws InterruptedException if the thread is interrupted while it waits for the process
		 */
		Outcome run(Path program) throws IOException, InterruptedException;
	}

	/**
	 * What happened when {@code <program> --version} ran.
	 */
	public sealed interface Outcome
	{
	}

	/**
	 * The program exited.
	 *
	 * @param status the exit status
	 * @param output what the program printed
	 */
	public record Exited(int status, String output) implements Outcome
	{
		/**
		 * Creates an outcome.
		 *
		 * @param status the exit status
		 * @param output what the program printed
		 * @throws NullPointerException if {@code output} is null
		 */
		public Exited
		{
			requireThat(output, "output").isNotNull();
		}
	}

	/**
	 * The program did not start or did not exit with its output read.
	 *
	 * @param reason why, starting with {@code --version}
	 */
	public record Unfinished(String reason) implements Outcome
	{
		/**
		 * Creates an outcome.
		 *
		 * @param reason why, starting with {@code --version}
		 * @throws NullPointerException     if {@code reason} is null
		 * @throws IllegalArgumentException if {@code reason} is blank
		 */
		public Unfinished
		{
			requireThat(reason, "reason").isNotBlank();
		}
	}
}
