package com.holdmyspot.cat.codex.updater;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Starts the Codex command-line program that setup found, to confirm that it runs.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexProgram
{
	/**
	 * Bounds how long {@code codex --version} may run before discovery fails.
	 */
	private static final long VERSION_TIMEOUT_SECONDS = 60;
	/**
	 * Bounds the version output kept for a diagnostic to 64 KiB.
	 */
	private static final int MAX_OUTPUT_BYTES = 64 * 1024;

	/**
	 * Prevents instantiation.
	 */
	private CodexProgram()
	{
	}

	/**
	 * Runs {@code <command> --version} with exactly {@code environment}, without a shell and with standard input
	 * closed, and requires it to exit with status 0.
	 *
	 * @param command     the command prefix whose first element is the absolute program path
	 * @param environment the complete environment of the program
	 * @param directory   the working directory, used when it is an existing directory
	 * @throws IOException if the program cannot start, does not exit within 60 seconds, or exits with another status
	 */
	public static void requireVersion(List<String> command, Map<String, String> environment, Path directory)
		throws IOException
	{
		List<String> arguments = new ArrayList<>(command);
		arguments.add("--version");
		ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
		builder.environment().clear();
		builder.environment().putAll(environment);
		if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
			builder.directory(directory.toFile());
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			FutureTask<String> output = new FutureTask<>(() -> read(process.getInputStream()));
			Thread.ofVirtual().start(output);
			try
			{
				if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
				{
					process.destroyForcibly();
					throw new IOException("The Codex command-line program did not exit within " +
						VERSION_TIMEOUT_SECONDS + " seconds: " + String.join(" ", arguments));
				}
				if (process.exitValue() != 0)
				{
					throw new IOException("The Codex command-line program failed with status " + process.exitValue() +
						"; reinstall Codex and rerun this command. Command: " + String.join(" ", arguments) +
						"\nOutput: " + output.get(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
				}
			}
			catch (InterruptedException e)
			{
				process.destroyForcibly();
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while running " + String.join(" ", arguments), e);
			}
			catch (ExecutionException | TimeoutException e)
			{
				throw new IOException("Could not read the output of " + String.join(" ", arguments), e);
			}
		}
	}

	/**
	 * Reads a program's output, keeping its first 64 KiB.
	 *
	 * @param stdout the program's merged standard output and standard error
	 * @return the kept output as UTF-8 text without surrounding whitespace
	 * @throws IOException if the output cannot be read
	 */
	private static String read(InputStream stdout) throws IOException
	{
		try (stdout)
		{
			String text = new String(stdout.readNBytes(MAX_OUTPUT_BYTES), StandardCharsets.UTF_8).strip();
			stdout.transferTo(OutputStream.nullOutputStream());
			return text;
		}
	}
}
