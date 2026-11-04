package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Tells whether this process runs with administrative rights.
 */
public final class ProcessElevation
{
	/**
	 * The Windows integrity levels of an elevated access token: high, and system.
	 */
	private static final String[] ELEVATED_LEVELS = {"S-1-16-12288", "S-1-16-16384"};
	/**
	 * The longest time that the Windows check may take.
	 */
	private static final long CHECK_SECONDS = 30;

	/**
	 * Prevents instantiation.
	 */
	private ProcessElevation()
	{
	}

	/**
	 * Returns whether this process runs with administrative rights. On Windows, that is when its access token is
	 * elevated, which {@code whoami /groups} reports as the high or system integrity level. Elsewhere, it is when its
	 * effective user ID is 0, which is the owner of a file that it creates.
	 *
	 * @param directory a directory that this process may write short-lived files to, which is created when absent
	 * @return {@code true} if the process runs with administrative rights, or the check fails
	 * @throws NullPointerException if {@code directory} is null
	 */
	public static boolean isElevated(Path directory)
	{
		requireThat(directory, "directory").isNotNull();
		try
		{
			if (Platform.current().isWindows())
				return hasElevatedToken(directory);
			return createsFilesAsRoot(directory);
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
	 * Returns whether this process's files are owned by user ID 0.
	 *
	 * @param directory the directory to create the file in
	 * @return {@code true} if a file that this process creates is owned by user ID 0
	 * @throws IOException if the file cannot be created, inspected or deleted
	 */
	private static boolean createsFilesAsRoot(Path directory) throws IOException
	{
		Files.createDirectories(directory);
		Path probe = Files.createTempFile(directory, "owner-", ".probe");
		try
		{
			return ((Integer) Files.getAttribute(probe, "unix:uid")) == 0;
		}
		finally
		{
			Files.delete(probe);
		}
	}

	/**
	 * Returns whether this process's Windows access token is elevated.
	 *
	 * @param directory the directory to write {@code whoami}'s output to
	 * @return {@code true} if {@code whoami /groups} lists a high or system integrity level
	 * @throws IOException          if {@code whoami} cannot be run, fails or does not end in time
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	private static boolean hasElevatedToken(Path directory) throws IOException, InterruptedException
	{
		String systemRoot = System.getenv("SystemRoot");
		if (systemRoot == null || systemRoot.isBlank())
			throw new IOException("SystemRoot is not set");
		Files.createDirectories(directory);
		Path output = Files.createTempFile(directory, "groups-", ".csv");
		try
		{
			ProcessBuilder builder = new ProcessBuilder(Path.of(systemRoot, "System32", "whoami.exe").toString(),
				"/groups", "/fo", "csv", "/nh").
				redirectErrorStream(true).
				redirectOutput(output.toFile());
			try (Process process = builder.start())
			{
				process.getOutputStream().close();
				if (!process.waitFor(CHECK_SECONDS, TimeUnit.SECONDS))
				{
					process.destroyForcibly();
					throw new IOException("whoami did not end");
				}
				// whoami writes in the console's code page; the integrity levels that are looked for are ASCII
				String groups = Files.readString(output, ISO_8859_1);
				if (process.exitValue() != 0)
					throw new IOException("whoami failed: " + groups);
				for (String level : ELEVATED_LEVELS)
				{
					if (groups.contains(level))
						return true;
				}
				return false;
			}
		}
		finally
		{
			Files.delete(output);
		}
	}
}
