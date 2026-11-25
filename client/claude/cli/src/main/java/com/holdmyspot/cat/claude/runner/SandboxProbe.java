package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeSessionSandbox.ProbeResult;
import com.holdmyspot.cat.common.ExecutableFiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Observes what the start of Claude Code's command sandbox needs from the machine, as
 * {@link ClaudeSessionSandbox#startRefusal} reads it: the result of a program that tries to start a sandbox, and
 * whether a file can be created in the temporary folder.
 */
public final class SandboxProbe
{
	/**
	 * How long a probe program may run before it counts as failed.
	 */
	public static final Duration TIMEOUT = Duration.ofSeconds(30);
	/**
	 * The most bytes of a program's output that the probe keeps.
	 */
	private static final int MAXIMUM_OUTPUT_BYTES = 8192;
	/**
	 * How long the probe waits for the rest of a finished program's output.
	 */
	private static final Duration OUTPUT_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Runs a program that tries to start a sandbox, once.
	 *
	 * @param command     the program and its arguments
	 * @param directory   the directory that the program runs in
	 * @param environment the program's complete environment
	 * @param executables runs the program's file
	 * @return the program's exit status and its output; when the program cannot be started or does not end within
	 *   {@link #TIMEOUT}, {@link ProbeResult#NOT_FINISHED} and the reason
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code command} is empty
	 */
	@SuppressWarnings("PMD.CloseResource")
	public static ProbeResult run(List<String> command, Path directory, Map<String, String> environment,
		ExecutableFiles executables)
	{
		requireThat(command, "command").isNotEmpty();
		requireThat(directory, "directory").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(executables, "executables").isNotNull();
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(directory.toFile());
		builder.redirectErrorStream(true);
		builder.environment().clear();
		builder.environment().putAll(environment);
		Process process;
		try
		{
			process = executables.run(builder);
		}
		catch (IOException e)
		{
			return new ProbeResult(ProbeResult.NOT_FINISHED, "Could not start " + String.join(" ", command) + ": " +
				e.getMessage());
		}
		AtomicReference<String> output = new AtomicReference<>("");
		Thread reader = Thread.ofVirtual().start(() -> output.set(readOutput(process.getInputStream())));
		try
		{
			process.getOutputStream().close();
			if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
			{
				process.destroyForcibly();
				return new ProbeResult(ProbeResult.NOT_FINISHED, String.join(" ", command) + " did not end within " +
					TIMEOUT.toSeconds() + " seconds");
			}
			reader.join(OUTPUT_TIMEOUT);
		}
		catch (IOException e)
		{
			process.destroyForcibly();
			return new ProbeResult(ProbeResult.NOT_FINISHED, "Could not run " + String.join(" ", command) + ": " +
				e.getMessage());
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			return new ProbeResult(ProbeResult.NOT_FINISHED, String.join(" ", command) + " was interrupted");
		}
		return new ProbeResult(process.exitValue(), output.get().strip());
	}

	/**
	 * Reads the start of a program's output and discards the rest, so that the program never blocks on a full pipe.
	 *
	 * @param stream the program's output
	 * @return the first bytes of the output
	 */
	private static String readOutput(InputStream stream)
	{
		try (stream)
		{
			String start = new String(stream.readNBytes(MAXIMUM_OUTPUT_BYTES), StandardCharsets.UTF_8);
			stream.transferTo(OutputStream.nullOutputStream());
			return start;
		}
		catch (IOException _)
		{
			// The program ended or was destroyed; what it printed before that is all that there is
			return "";
		}
	}

	/**
	 * Tries to create a file in a folder and delete it.
	 *
	 * @param folder the folder
	 * @return the failure to create the file, or empty if a file could be created and deleted
	 * @throws NullPointerException if {@code folder} is null
	 */
	public static Optional<IOException> fileCreationFailure(Path folder)
	{
		requireThat(folder, "folder").isNotNull();
		try
		{
			Files.delete(Files.createTempFile(folder, "cat-sandbox-probe-", ".tmp"));
			return Optional.empty();
		}
		catch (IOException e)
		{
			return Optional.of(e);
		}
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private SandboxProbe()
	{
	}
}
