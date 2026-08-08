package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs external commands for one working directory.
 */
final class CommandRunner
{
	private final Path workingDirectory;

	/**
	 * Creates a command runner.
	 *
	 * @param workingDirectory the directory in which commands run
	 */
	CommandRunner(Path workingDirectory)
	{
		this.workingDirectory = workingDirectory;
	}

	/**
	 * Runs a command and writes its combined output to a file.
	 *
	 * @param command the command and its arguments
	 * @param outputFile the file that receives the command output
	 * @return the command's exit code
	 * @throws IOException if the command cannot run or its output cannot be written
	 */
	int run(List<String> command, Path outputFile) throws IOException
	{
		try (Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true).
			start())
		{
			try (InputStream input = process.getInputStream(); OutputStream output = Files.newOutputStream(outputFile))
			{
				input.transferTo(output);
			}
			try
			{
				return process.waitFor();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while running external command", e);
			}
		}
	}

	/**
	 * Runs an external command and reports periodic progress while it runs.
	 *
	 * @param command the command and its arguments
	 * @param outputFile the file that receives the command output
	 * @param heartbeatInterval the intended interval between progress callbacks
	 * @param heartbeat the progress callback
	 * @return the command's exit code
	 * @throws IOException if the command cannot run or its output cannot be written
	 */
	int run(List<String> command, Path outputFile, Duration heartbeatInterval, Runnable heartbeat) throws IOException
	{
		requireThat(heartbeatInterval, "heartbeatInterval").isGreaterThan(Duration.ZERO);
		requireThat(heartbeat, "heartbeat").isNotNull();
		long heartbeatMilliseconds = heartbeatInterval.toMillis();
		requireThat(heartbeatMilliseconds, "heartbeatMilliseconds").isGreaterThan(0L);
		try (ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
			 Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true).
				 start())
		{
			ScheduledFuture<?> heartbeatTask = executor.scheduleAtFixedRate(heartbeat, heartbeatMilliseconds,
				heartbeatMilliseconds, TimeUnit.MILLISECONDS);
			try
			{
				try (InputStream input = process.getInputStream(); OutputStream output = Files.newOutputStream(outputFile))
				{
					input.transferTo(output);
				}
				try
				{
					return process.waitFor();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while running external command", e);
				}
			}
			finally
			{
				heartbeatTask.cancel(false);
			}
		}
	}
}
