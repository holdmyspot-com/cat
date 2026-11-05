package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.installer.activation.CommandResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a data migration script in a new process: a POSIX shell script with {@code /bin/sh}, and a {@code .ps1} script
 * with Windows PowerShell. The script inherits the installer's environment and runs in its own directory, with its
 * standard input closed.
 */
public final class ProcessMigrationRunner implements MigrationRunner
{
	/**
	 * The longest that one migration script may run.
	 */
	private static final Duration TIME_LIMIT = Duration.ofMinutes(30);
	/**
	 * The most output that is kept; further output is read and discarded so that the script never blocks.
	 */
	private static final int MAXIMUM_OUTPUT_BYTES = 64 * 1024;

	/**
	 * Creates the runner.
	 */
	public ProcessMigrationRunner()
	{
	}

	@Override
	public CommandResult run(Path script, Map<String, String> variables) throws IOException
	{
		requireThat(script, "script").isAbsolute();
		requireThat(variables, "variables").isNotNull();
		ProcessBuilder builder = new ProcessBuilder(command(script)).directory(script.getParent().toFile()).
			redirectErrorStream(true);
		builder.environment().putAll(variables);
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
					throw new IOException("The data migration " + script + " did not finish within " +
						TIME_LIMIT.toMinutes() + " minutes");
				}
				reader.join();
			}
			catch (InterruptedException _)
			{
				process.destroyForcibly();
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted while the data migration " + script + " was running");
			}
			synchronized (output)
			{
				if (!failures.isEmpty())
				{
					throw new IOException("The output of the data migration " + script + " could not be read",
						failures.getFirst());
				}
				return new CommandResult(process.exitValue(), output.toString(UTF_8));
			}
		}
	}

	/**
	 * Returns the command line that runs a script.
	 *
	 * @param script the script
	 * @return the command line
	 */
	private static List<String> command(Path script)
	{
		if (script.getFileName().toString().endsWith(".ps1"))
		{
			String systemRoot = System.getenv("SystemRoot");
			if (systemRoot == null)
				systemRoot = "C:\\Windows";
			Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
			return List.of(powershell.toString(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
				"-File", script.toString());
		}
		return List.of("/bin/sh", script.toString());
	}

	/**
	 * Reads the script's output until it closes, keeping the first part of it.
	 *
	 * @param input    the script's combined output
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
