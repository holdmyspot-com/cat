package io.github.cowwoc.cat.common.git;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs Git commands for one repository.
 */
public final class Git
{
	private static final int MAXIMUM_RETAINED_OUTPUT_BYTES = 64 * 1024 * 1024;
	private final Path repository;

	/**
	 * Creates a Git access object.
	 *
	 * @param repository the repository directory
	 */
	public Git(Path repository)
	{
		this.repository = repository;
	}

	/**
	 * Runs Git and returns its standard-output lines.
	 *
	 * @param arguments the Git subcommand and arguments
	 * @return Git's standard-output lines
	 * @throws IOException if Git cannot run or rejects the request
	 */
	public List<String> lines(String... arguments) throws IOException
	{
		return lines(Map.of(), arguments);
	}

	/**
	 * Runs Git and returns its standard output as text.
	 *
	 * @param arguments the Git subcommand and arguments
	 * @return Git's standard output
	 * @throws IOException if Git cannot run or rejects the request
	 */
	public String run(String... arguments) throws IOException
	{
		return new String(bytes(arguments), UTF_8);
	}

	/**
	 * Runs Git with environment overrides and returns its standard-output lines.
	 *
	 * @param environment variable values that override this process's environment
	 * @param arguments the Git subcommand and arguments
	 * @return Git's standard-output lines
	 * @throws IOException if Git cannot run or rejects the request
	 */
	public List<String> lines(Map<String, String> environment, String... arguments) throws IOException
	{
		return new String(bytes(environment, arguments), UTF_8).lines().toList();
	}

	/**
	 * Runs Git with an isolated index and returns its standard-output lines.
	 *
	 * @param indexFile the temporary index file
	 * @param arguments the Git subcommand and arguments
	 * @return Git's standard-output lines
	 * @throws IOException if Git cannot run or rejects the request
	 */
	public List<String> lines(Path indexFile, String... arguments) throws IOException
	{
		return lines(Map.of("GIT_INDEX_FILE", indexFile.toAbsolutePath().normalize().toString()), arguments);
	}

	/**
	 * Runs Git and returns its unmodified standard output.
	 *
	 * @param arguments the Git subcommand and arguments
	 * @return Git's standard output bytes
	 * @throws IOException if Git cannot run or rejects the request
	 */
	public byte[] bytes(String... arguments) throws IOException
	{
		return bytes(Map.of(), arguments);
	}

	/**
	 * Runs Git with environment overrides and returns its unmodified standard output.
	 *
	 * @param environment variable values that override this process's environment
	 * @param arguments the Git subcommand and arguments
	 * @return Git's standard output bytes
	 * @throws IOException if Git cannot run or rejects the request
	 */
	private byte[] bytes(Map<String, String> environment, String... arguments) throws IOException
	{
		try (Process process = start(environment, arguments))
		{
			ProcessOutput output = readBoundedOutput(process.getInputStream());
			if (output.exceededLimit())
			{
				throw new IOException("Git command output exceeds the maximum supported size of " +
					MAXIMUM_RETAINED_OUTPUT_BYTES +
					" bytes; reduce the selected paths or split the replay work");
			}
			waitForSuccess(process, arguments, new String(output.bytes(), UTF_8));
			return output.bytes();
		}
	}

	/**
	 * Runs Git and returns its exit status, without treating a nonzero status as a failure.
	 * <p>
	 * Use this for a command whose nonzero status is an answer, such as {@code git check-ignore}, where status 1 means
	 * "not ignored" and 128 means a real failure. Git's output is discarded. On Unix, a process that a signal killed
	 * reports status 128 plus the signal number, so callers must accept only the statuses they expect.
	 *
	 * @param arguments the Git subcommand and arguments
	 * @return Git's exit status
	 * @throws IOException if Git cannot start or the wait is interrupted
	 */
	public int status(String... arguments) throws IOException
	{
		try (Process process = start(Map.of(), arguments))
		{
			// Drain the output so that Git cannot block on a full pipe
			readBoundedOutput(process.getInputStream());
			return waitFor(process);
		}
	}

	/**
	 * Returns Git's three-way merge of an earlier file, the source's parent version, and the source version.
	 *
	 * @param earlierFile the earlier file version that receives the source change
	 * @param parentFile the source commit's parent version
	 * @param sourceFile the source commit's version
	 * @return the merged file, including conflict markers when reviewer action is needed
	 * @throws IOException if Git cannot run or cannot produce a merge candidate
	 */
	public byte[] mergeFile(Path earlierFile, Path parentFile, Path sourceFile) throws IOException
	{
		String[] arguments = {"merge-file", "-p", earlierFile.toString(), parentFile.toString(), sourceFile.toString()};
		try (Process process = start(Map.of(), arguments))
		{
			ProcessOutput output = readBoundedOutput(process.getInputStream());
			if (output.exceededLimit())
			{
				throw new IOException("Git merge candidate exceeds the maximum supported size of " +
					MAXIMUM_RETAINED_OUTPUT_BYTES +
					" bytes; prepare a smaller file-level change");
			}
			try
			{
				if (process.waitFor() > 127)
				{
					throw new IOException("Git could not prepare a merge candidate: " + new String(output.bytes(), UTF_8));
				}
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while preparing a merge candidate", e);
			}
			return output.bytes();
		}
	}

	/**
	 * Runs Git with the supplied environment overrides.
	 *
	 * @param environment variable values that override this process's environment
	 * @param arguments the Git subcommand and arguments
	 * @return the started process
	 * @throws IOException if Git cannot start
	 */
	private Process start(Map<String, String> environment, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(arguments.length + 1);
		command.add("git");
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true);
		builder.environment().putAll(environment);
		return builder.start();
	}

	/**
	 * Waits for Git to complete successfully.
	 *
	 * @param process the started Git process
	 * @param arguments the Git subcommand and arguments
	 * @param output the retained process output for an error message
	 * @throws IOException if Git fails or the wait is interrupted
	 */
	private void waitForSuccess(Process process, String[] arguments, String output) throws IOException
	{
		if (waitFor(process) != 0)
			throw new IOException("Git command failed: git " + String.join(" ", arguments) + ": " + output);
	}

	/**
	 * Waits for Git to exit.
	 *
	 * @param process the started Git process
	 * @return Git's exit status
	 * @throws IOException if the wait is interrupted
	 */
	private int waitFor(Process process) throws IOException
	{
		try
		{
			return process.waitFor();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while running Git", e);
		}
	}

	/**
	 * Drains process output while retaining only the amount a caller needs in memory.
	 *
	 * @param input the process output stream
	 * @return the retained bytes and whether the process produced more
	 * @throws IOException if the process output cannot be read
	 */
	private ProcessOutput readBoundedOutput(InputStream input) throws IOException
	{
		try (input)
		{
			ByteArrayOutputStream retained = new ByteArrayOutputStream();
			byte[] buffer = new byte[8 * 1024];
			boolean exceededLimit = false;
			while (true)
			{
				int count = input.read(buffer);
				if (count == -1)
					break;
				int remaining = MAXIMUM_RETAINED_OUTPUT_BYTES - retained.size();
				int retainedCount = Math.min(count, Math.max(remaining, 0));
				retained.write(buffer, 0, retainedCount);
				exceededLimit |= retainedCount != count;
			}
			return new ProcessOutput(retained.toByteArray(), exceededLimit);
		}
	}

	/**
	 * Holds the bounded in-memory portion of one completed process output stream.
	 *
	 * @param bytes the retained leading bytes
	 * @param exceededLimit whether additional output was discarded
	 */
	private record ProcessOutput(byte[] bytes, boolean exceededLimit)
	{
	}
}
