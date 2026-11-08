package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.secrets.ProcessElevationAccess;
import com.holdmyspot.cat.common.installer.secrets.ProcessElevationSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
	 * The most bytes of output that a check accepts from its program, which prints a line.
	 */
	private static final int OUTPUT_LIMIT = 64 * 1024;
	/**
	 * The time between two reads of the output that a program has printed.
	 */
	private static final Duration POLL_INTERVAL = Duration.ofMillis(20);
	/**
	 * The form of a user ID.
	 */
	private static final Pattern USER_ID = Pattern.compile("[0-9]+");

	static
	{
		ProcessElevationSecrets.setProcessElevationAccess(new ProcessElevationAccess()
		{
			@Override
			public boolean runsAsRoot(Path id, Duration limit)
			{
				return ProcessElevation.runsAsRoot(id, limit);
			}

			@Override
			public boolean holdsElevatedToken(Path whoami, Duration limit)
			{
				return ProcessElevation.holdsElevatedToken(whoami, limit);
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
	 * @return {@code true} if the process runs with administrative rights, or the check fails
	 */
	public static boolean isElevated()
	{
		if (!Platform.current().isWindows())
			return runsAsRoot(ID, CHECK_LIMIT);
		String systemRoot = System.getenv("SystemRoot");
		if (systemRoot == null || systemRoot.isBlank())
			return true;
		return holdsElevatedToken(Path.of(systemRoot, "System32", "whoami.exe"), CHECK_LIMIT);
	}

	/**
	 * Returns whether this process's access token is elevated, as a program that lists the token's groups reports it.
	 *
	 * @param whoami the program, run with the arguments {@code /groups /fo csv /nh}
	 * @param limit  the longest time that the program may take before it is stopped
	 * @return {@code true} if the program reports an integrity level of high or above, or cannot be run, fails, does
	 *   not end in time or reports no integrity level
	 */
	private static boolean holdsElevatedToken(Path whoami, Duration limit)
	{
		requireThat(whoami, "whoami").isNotNull();
		requireThat(limit, "limit").isNotNull();
		try
		{
			String groups = output(List.of(whoami.toString(), "/groups", "/fo", "csv", "/nh"), limit);
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
	 * @param id    the program, run with the single argument {@code -u}
	 * @param limit the longest time that the program may take before it is stopped
	 * @return {@code true} if the program reports the user ID 0, or cannot be run, fails, does not end in time or
	 *   prints no user ID
	 */
	private static boolean runsAsRoot(Path id, Duration limit)
	{
		requireThat(id, "id").isNotNull();
		requireThat(limit, "limit").isNotNull();
		try
		{
			String userId = output(List.of(id.toString(), "-u"), limit).strip();
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
	 * Runs a check's program and returns what it printed. The output is read through the program's pipe, but only the
	 * part that has already arrived, so that a process which keeps the pipe open after the program ended cannot block
	 * the check past its limit and no thread is left blocked on the pipe.
	 *
	 * @param command the program and its arguments
	 * @param limit   the longest time that the program may take before it is stopped, together with every process
	 *                that it started
	 * @return the program's standard output and error, which programs of a check write in ASCII
	 * @throws IOException          if the program cannot be run, fails, prints more than {@value #OUTPUT_LIMIT} bytes
	 *                              or does not end in time
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	private static String output(List<String> command, Duration limit) throws IOException, InterruptedException
	{
		long deadline = System.nanoTime() + limit.toNanos();
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		try (Process process = builder.start(); InputStream pipe = process.getInputStream())
		{
			process.getOutputStream().close();
			try
			{
				ByteArrayOutputStream printed = new ByteArrayOutputStream();
				boolean ended = false;
				while (!ended)
				{
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0)
						throw new IOException(command.getFirst() + " did not end within " + limit);
					// Returns as soon as the program ends, so a program that prints and ends is not delayed
					ended = process.waitFor(Math.min(remaining, POLL_INTERVAL.toNanos()), TimeUnit.NANOSECONDS);
					readArrived(pipe, printed);
				}
				// A program may write in the console's code page; the text that a check looks for is ASCII
				String text = printed.toString(ISO_8859_1);
				if (process.exitValue() != 0)
					throw new IOException(command.getFirst() + " failed: " + text);
				return text;
			}
			finally
			{
				if (process.isAlive())
					stopWithDescendants(process);
			}
		}
	}

	/**
	 * Reads the output that a program has printed so far, without waiting for more.
	 *
	 * @param pipe    the program's output
	 * @param printed receives the bytes
	 * @throws IOException if the pipe cannot be read, or the output grows beyond the most that a check accepts
	 */
	private static void readArrived(InputStream pipe, ByteArrayOutputStream printed) throws IOException
	{
		byte[] buffer = new byte[8192];
		while (true)
		{
			int arrived = pipe.available();
			if (arrived <= 0)
				return;
			int count = pipe.read(buffer, 0, Math.min(arrived, buffer.length));
			if (count == -1)
				return;
			if (printed.size() + count > OUTPUT_LIMIT)
				throw new IOException("The output exceeds " + OUTPUT_LIMIT + " bytes");
			printed.write(buffer, 0, count);
		}
	}

	/**
	 * Stops a process and every process that it started, and waits until all of them have ended or the stop limit
	 * has passed.
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
			// The check reports the program's failure to end either way
		}
	}
}
