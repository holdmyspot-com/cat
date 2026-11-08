package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.secrets.ProcessElevationAccess;
import com.holdmyspot.cat.common.installer.secrets.ProcessElevationSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells whether this process runs with administrative rights.
 */
public final class ProcessElevation
{
	/**
	 * The program that prints the effective user ID on POSIX systems, at its absolute path so that no search path
	 * selects another.
	 */
	private static final Path ID = Path.of("/usr/bin/id");
	/**
	 * A line of {@code whoami /groups /fo csv /nh} for a mandatory label, whose SID is {@code S-1-16-} followed by
	 * the relative ID of the token's integrity level, which the first group captures.
	 */
	private static final Pattern INTEGRITY_LEVEL =
		Pattern.compile("(?m)^\"[^\"]*\",\"[^\"]*\",\"S-1-16-([0-9]{1,9})\",");
	/**
	 * The relative ID of the high integrity level, the lowest level of an elevated access token.
	 */
	private static final int HIGH_INTEGRITY = 0x3000;
	/**
	 * The longest time that the program of a check may take.
	 */
	private static final Duration CHECK_LIMIT = Duration.ofSeconds(10);
	/**
	 * The longest time that a check waits for the processes that it stopped to end.
	 */
	private static final Duration STOP_LIMIT = Duration.ofSeconds(10);
	/**
	 * The form of a user ID.
	 */
	private static final Pattern USER_ID = Pattern.compile("[0-9]+");

	static
	{
		ProcessElevationSecrets.setProcessElevationAccess(new ProcessElevationAccess()
		{
			@Override
			public boolean runsAsRoot(Path id, Duration limit, Path directory)
			{
				return ProcessElevation.runsAsRoot(id, limit, directory);
			}

			@Override
			public boolean holdsElevatedToken(Path whoami, Duration limit, Path directory)
			{
				return ProcessElevation.holdsElevatedToken(whoami, limit, directory);
			}
		});
	}

	/**
	 * Prevents instantiation.
	 */
	private ProcessElevation()
	{
	}

	/**
	 * Returns whether this process runs with administrative rights. On Windows, that is when its access token is
	 * elevated, which {@code whoami /groups} reports as an integrity level of high or above. Elsewhere, it is when its
	 * effective user ID is 0, which {@code /usr/bin/id -u} prints.
	 *
	 * @param directory a directory that this process may write short-lived files to, which is created when absent
	 * @return {@code true} if the process runs with administrative rights, or the check fails
	 * @throws NullPointerException if {@code directory} is null
	 */
	public static boolean isElevated(Path directory)
	{
		requireThat(directory, "directory").isNotNull();
		if (!Platform.current().isWindows())
			return runsAsRoot(ID, CHECK_LIMIT, directory);
		String systemRoot = System.getenv("SystemRoot");
		if (systemRoot == null || systemRoot.isBlank())
			return true;
		return holdsElevatedToken(Path.of(systemRoot, "System32", "whoami.exe"), CHECK_LIMIT, directory);
	}

	/**
	 * Returns whether this process's access token is elevated, as a program that lists the token's groups reports it.
	 *
	 * @param whoami    the program, run with the arguments {@code /groups /fo csv /nh}
	 * @param limit     the longest time that the program may take before it is stopped
	 * @param directory a directory that the check may write short-lived files to, which is created when absent
	 * @return {@code true} if the program reports an integrity level of high or above, or cannot be run, fails, does
	 *   not end in time or reports no integrity level
	 */
	private static boolean holdsElevatedToken(Path whoami, Duration limit, Path directory)
	{
		requireThat(whoami, "whoami").isNotNull();
		requireThat(limit, "limit").isNotNull();
		requireThat(directory, "directory").isNotNull();
		try
		{
			String groups = output(List.of(whoami.toString(), "/groups", "/fo", "csv", "/nh"), limit, directory);
			Matcher level = INTEGRITY_LEVEL.matcher(groups);
			boolean found = false;
			while (level.find())
			{
				if (Integer.parseInt(level.group(1)) >= HIGH_INTEGRITY)
					return true;
				found = true;
			}
			// A list without an integrity level is not an answer
			return !found;
		}
		catch (IOException | RuntimeException _)
		{
			return true;
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			return true;
		}
	}

	/**
	 * Returns whether this process's effective user ID is 0, as a program that prints the ID reports it.
	 *
	 * @param id        the program, run with the single argument {@code -u}
	 * @param limit     the longest time that the program may take before it is stopped
	 * @param directory a directory that the check may write short-lived files to, which is created when absent
	 * @return {@code true} if the program reports the user ID 0, or cannot be run, fails, does not end in time or
	 *   prints no user ID
	 */
	private static boolean runsAsRoot(Path id, Duration limit, Path directory)
	{
		requireThat(id, "id").isNotNull();
		requireThat(limit, "limit").isNotNull();
		requireThat(directory, "directory").isNotNull();
		try
		{
			String userId = output(List.of(id.toString(), "-u"), limit, directory).strip();
			boolean printedUnprivilegedId = USER_ID.matcher(userId).matches() && Long.parseLong(userId) != 0;
			return !printedUnprivilegedId;
		}
		catch (IOException | RuntimeException _)
		{
			return true;
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			return true;
		}
	}

	/**
	 * Runs a check's program and returns what it printed.
	 *
	 * @param command   the program and its arguments
	 * @param limit     the longest time that the program may take before it is stopped, together with every
	 *                  process that it started
	 * @param directory the directory to write the program's output to, which is created when absent
	 * @return the program's standard output and error, which programs of a check write in ASCII
	 * @throws IOException          if the program cannot be run, fails or does not end in time
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	private static String output(List<String> command, Duration limit, Path directory)
		throws IOException, InterruptedException
	{
		Files.createDirectories(directory);
		Path output = Files.createTempFile(directory, "check-", ".txt");
		try
		{
			ProcessBuilder builder = new ProcessBuilder(command).
				redirectErrorStream(true).
				redirectOutput(output.toFile());
			try (Process process = builder.start())
			{
				process.getOutputStream().close();
				boolean ended = process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS);
				if (!ended)
				{
					stopWithDescendants(process);
					throw new IOException(command.getFirst() + " did not end within " + limit);
				}
				// A program may write in the console's code page; the text that a check looks for is ASCII
				String printed = Files.readString(output, ISO_8859_1);
				if (process.exitValue() != 0)
					throw new IOException(command.getFirst() + " failed: " + printed);
				return printed;
			}
		}
		finally
		{
			try
			{
				Files.deleteIfExists(output);
			}
			catch (IOException _)
			{
				// A file that another process still holds open, or that a virus scanner has locked, must not
				// replace the check's own result or exception. The file is left in the check's own directory.
			}
		}
	}

	/**
	 * Stops a process and every process that it started, and waits until all of them have ended or the stop limit
	 * has passed. On Windows, a process that is still running keeps the file that received the program's output open,
	 * so the file cannot be deleted before the processes have ended.
	 *
	 * @param process the process to stop
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	private static void stopWithDescendants(Process process) throws InterruptedException
	{
		// The descendants must be listed and stopped before their parent ends, because an orphaned process no
		// longer belongs to its parent's tree. A parent that is still starting a child when the list is taken
		// (a slow runner can take most of the limit to start one) would leave that child running, so the list is
		// taken again until a pass finds no process that has not already been stopped.
		List<CompletableFuture<?>> exits = new ArrayList<>();
		exits.add(process.onExit());
		Set<Long> stopped = new HashSet<>();
		boolean foundNew;
		do
		{
			foundNew = false;
			for (ProcessHandle descendant : process.descendants().toList())
			{
				if (stopped.add(descendant.pid()))
				{
					foundNew = true;
					descendant.destroyForcibly();
					exits.add(descendant.onExit());
				}
			}
		}
		while (foundNew);
		process.destroyForcibly();
		try
		{
			CompletableFuture.allOf(exits.toArray(CompletableFuture<?>[]::new)).
				get(STOP_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (ExecutionException | TimeoutException _)
		{
			// The check reports the program's failure to end either way; a process that outlives the wait only
			// leaves the output file behind
		}
	}
}
