package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.updater.secrets.ProcessElevationSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
	 * The Windows integrity levels of an elevated access token: high, and system.
	 */
	private static final List<String> ELEVATED_LEVELS = List.of("S-1-16-12288", "S-1-16-16384");
	/**
	 * The longest time that the program of a check may take.
	 */
	private static final Duration CHECK_LIMIT = Duration.ofSeconds(10);
	/**
	 * The form of a user ID.
	 */
	private static final Pattern USER_ID = Pattern.compile("[0-9]+");

	static
	{
		ProcessElevationSecrets.setProcessElevationAccess(ProcessElevation::runsAsRoot);
	}

	/**
	 * Prevents instantiation.
	 */
	private ProcessElevation()
	{
	}

	/**
	 * Returns whether this process runs with administrative rights. On Windows, that is when its access token is
	 * elevated, which {@code whoami /groups} reports as the high or system integrity level. Elsewhere, it is when its
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
		try
		{
			String systemRoot = System.getenv("SystemRoot");
			if (systemRoot == null || systemRoot.isBlank())
				return true;
			String groups = output(List.of(Path.of(systemRoot, "System32", "whoami.exe").toString(), "/groups",
				"/fo", "csv", "/nh"), CHECK_LIMIT, directory);
			for (String level : ELEVATED_LEVELS)
			{
				if (groups.contains(level))
					return true;
			}
			return false;
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
	 * @param limit     the longest time that the program may take before it is stopped
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
					process.descendants().forEach(ProcessHandle::destroyForcibly);
					process.destroyForcibly();
					process.waitFor();
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
			Files.delete(output);
		}
	}
}
