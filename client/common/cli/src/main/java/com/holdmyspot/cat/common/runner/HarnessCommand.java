package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.ExecutableFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.nio.charset.StandardCharsets.UTF_8;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Runs a short-lived harness CLI setup command and captures its result.
 */
public final class HarnessCommand
{
	private static final int MAXIMUM_COMMAND_OUTPUT_BYTES = 1_048_576;

	/**
	 * Executes a harness setup command and rejects non-zero exit codes.
	 *
	 * @param command the non-empty command and arguments
	 *
	 * @param workingDirectory the command working directory
	 *
	 * @param environment the complete scrubbed environment
	 *
	 * @param executables runs the command's file
	 *
	 * @throws IOException if the command fails or is interrupted
	 */
	public static void execute(List<String> command, Path workingDirectory, Map<String, String> environment,
		ExecutableFiles executables) throws IOException
	{
		execute(command, workingDirectory, environment, executables, null, null, null);
	}

	/**
	 * Executes a harness setup command, retaining bounded diagnostics under an owned run.
	 *
	 * @param command the non-empty command and arguments
	 *
	 * @param workingDirectory the command working directory
	 *
	 * @param environment the complete scrubbed environment
	 *
	 * @param executables runs the command's file
	 *
	 * @param manager the owned-run lifecycle manager
	 *
	 * @param run the owned run
	 *
	 * @param commandName the generated command artifact name
	 *
	 * @throws IOException if the command fails, is interrupted, or diagnostics cannot be persisted
	 */
	public static void execute(List<String> command, Path workingDirectory, Map<String, String> environment,
		ExecutableFiles executables, HarnessRunManager manager, HarnessRun run, String commandName) throws IOException
	{
		requireThat(command, "command").isNotEmpty();
		requireThat(executables, "executables").isNotNull();
		if (manager != null || run != null || commandName != null)
		{
			requireThat(manager, "manager").isNotNull();
			requireThat(run, "run").isNotNull();
			requireThat(commandName, "commandName").isNotBlank();
		}
		try (Process process = HarnessProcessStarter.start(command, workingDirectory, environment,
			executables))
		{
			try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor())
			{
				Future<String> standardOutput = readers.submit(() -> readOutput(process.getInputStream()));
				Future<String> standardError = readers.submit(() -> readOutput(process.getErrorStream()));
				int exitCode = process.waitFor();
				String output = standardOutput.get();
				String error = standardError.get();
				if (manager != null)
					manager.writeCommandOutput(run, commandName, output, error);
				if (exitCode != 0)
					throw new IOException("Harness setup command failed: " + command + ": " + error.strip());
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while running harness setup command", e);
		}
		catch (ExecutionException e)
		{
			throw new IOException("Unable to capture harness setup command output", e.getCause());
		}
	}

	/**
	 * Drains one command stream while retaining a bounded diagnostic excerpt.
	 *
	 * @param input the process stream
	 *
	 * @return the retained text, with a truncation marker when necessary
	 *
	 * @throws IOException if the stream cannot be read
	 */
	private static String readOutput(InputStream input) throws IOException
	{
		try (input)
		{
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			boolean truncated = false;
			int read = input.read(buffer);
			while (read != -1)
			{
				int retained = Math.min(read, MAXIMUM_COMMAND_OUTPUT_BYTES - output.size());
				output.write(buffer, 0, retained);
				if (retained < read)
					truncated = true;
				read = input.read(buffer);
			}
			String result = output.toString(UTF_8);
			if (truncated)
				return result + "\n[command output truncated after " + MAXIMUM_COMMAND_OUTPUT_BYTES + " bytes]";
			return result;
		}
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessCommand()
	{
	}
}
