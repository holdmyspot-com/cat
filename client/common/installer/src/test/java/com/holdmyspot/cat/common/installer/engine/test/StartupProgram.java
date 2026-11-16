package com.holdmyspot.cat.common.installer.engine.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;

/**
 * The session-start program of the fixture plugin whose startup cache real Java records and writes: it reads its
 * input and prints a line, or publishes its process ID and waits when asked to hold.
 */
public final class StartupProgram
{
	/**
	 * Prevents instantiation.
	 */
	private StartupProgram()
	{
	}

	/**
	 * Runs the program or holds a fixture process after publishing its process ID.
	 *
	 * @param arguments empty to read and print, or {@code --hold} followed by a path at which to publish the process ID
	 * @throws IOException if input cannot be read or the process ID cannot be published
	 * @throws InterruptedException if the held process is interrupted
	 */
	public static void main(String[] arguments) throws IOException, InterruptedException
	{
		if (arguments.length == 2 && arguments[0].equals("--hold"))
		{
			publishProcessId(Path.of(arguments[1]));
			new CountDownLatch(1).await();
			return;
		}
		if (arguments.length != 0)
			throw new IllegalArgumentException("Expected no arguments or --hold <ready-path>");

		System.in.readAllBytes();
		System.out.println("started");
	}

	/**
	 * Publishes this process's ID at a path that readers observe only after the complete value is written.
	 *
	 * @param readyPath the path at which to publish the process ID
	 * @throws IOException if the process ID cannot be published
	 */
	private static void publishProcessId(Path readyPath) throws IOException
	{
		Path temporaryPath = null;
		try
		{
			temporaryPath = Files.createTempFile(readyPath.getParent(), "startup-program-", ".tmp");
			Files.writeString(temporaryPath, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
			Files.move(temporaryPath, readyPath, StandardCopyOption.ATOMIC_MOVE);
		}
		finally
		{
			if (temporaryPath != null)
				Files.deleteIfExists(temporaryPath);
		}
	}
}
