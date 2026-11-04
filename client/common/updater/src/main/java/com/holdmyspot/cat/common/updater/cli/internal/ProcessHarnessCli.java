package com.holdmyspot.cat.common.updater.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.updater.activation.CommandResult;
import com.holdmyspot.cat.common.updater.activation.HarnessCli;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the recorded harness program directly, without a shell, with exactly its recorded environment and the harness
 * home as its working directory.
 */
public final class ProcessHarnessCli implements HarnessCli
{
	/**
	 * The longest that one harness command may run.
	 */
	private static final Duration TIME_LIMIT = Duration.ofMinutes(5);
	/**
	 * The most output that is kept; further output is read and discarded so that the program never blocks.
	 */
	private static final int MAXIMUM_OUTPUT_BYTES = 1024 * 1024;
	private final HarnessCommand command;
	private final Path home;

	/**
	 * Creates the runner.
	 *
	 * @param command the recorded harness program and its environment
	 * @param home    the harness home
	 * @throws NullPointerException if any of the arguments are null
	 */
	public ProcessHarnessCli(HarnessCommand command, Path home)
	{
		requireThat(command, "command").isNotNull();
		requireThat(home, "home").isNotNull();
		this.command = command;
		this.home = home;
	}

	@Override
	public CommandResult run(List<String> arguments) throws IOException
	{
		requireThat(arguments, "arguments").isNotNull();
		List<String> line = new ArrayList<>(command.command());
		line.addAll(arguments);
		ProcessBuilder builder = new ProcessBuilder(line).directory(home.toFile()).redirectErrorStream(true);
		builder.environment().clear();
		builder.environment().putAll(command.environment());
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			List<IOException> failures = new ArrayList<>(1);
			Thread reader = Thread.ofPlatform().daemon().start(() -> read(process.getInputStream(), output, failures));
			try
			{
				if (!process.waitFor(TIME_LIMIT.toMillis(), TimeUnit.MILLISECONDS))
				{
					process.destroyForcibly();
					throw new IOException(line.getFirst() + " did not finish within " + TIME_LIMIT.toMinutes() +
						" minutes");
				}
				reader.join();
			}
			catch (InterruptedException _)
			{
				process.destroyForcibly();
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted while " + line.getFirst() + " was running");
			}
			String text;
			synchronized (output)
			{
				if (!failures.isEmpty())
					throw new IOException("The output of " + line.getFirst() + " could not be read", failures.getFirst());
				text = output.toString(UTF_8);
			}
			return new CommandResult(process.exitValue(), text);
		}
	}

	/**
	 * Reads the program's output until it closes, keeping the first part of it.
	 *
	 * @param input    the program's combined output
	 * @param output   receives the kept part
	 * @param failures receives the failure if the output cannot be read
	 */
	private static void read(InputStream input, ByteArrayOutputStream output, List<IOException> failures)
	{
		byte[] buffer = new byte[8192];
		try (input)
		{
			for (int count = input.read(buffer); count >= 0; count = input.read(buffer))
			{
				synchronized (output)
				{
					int kept = Math.min(count, MAXIMUM_OUTPUT_BYTES - output.size());
					if (kept > 0)
						output.write(buffer, 0, kept);
				}
			}
		}
		catch (IOException e)
		{
			synchronized (output)
			{
				failures.add(e);
			}
		}
	}
}
