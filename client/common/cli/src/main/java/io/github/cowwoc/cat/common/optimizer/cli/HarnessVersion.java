package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Platform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads a harness's version from {@code <executable> --version}. The process is behind {@link Launcher}, so that a
 * test supplies a fake.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class HarnessVersion
{
	private static final String UNKNOWN = "unknown";
	private static final long TIMEOUT_SECONDS = 30;

	/**
	 * Runs a command and returns its output.
	 */
	@FunctionalInterface
	public interface Launcher
	{
		/**
		 * Runs a command.
		 *
		 * @param command the executable and its arguments
		 * @return the combined standard output and standard error
		 * @throws IOException if the process cannot start, fails to run or does not end in time
		 * @throws InterruptedException if the thread is interrupted while it waits
		 */
		String run(List<String> command) throws IOException, InterruptedException;
	}

	/**
	 * Prevents instantiation.
	 */
	private HarnessVersion()
	{
	}

	/**
	 * Returns the launcher that starts a real process and waits 30 seconds for it to end. The launcher finds the
	 * executable as {@link Platform#findProgram} does.
	 *
	 * @return the launcher
	 */
	public static Launcher process()
	{
		return command ->
		{
			ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
			List<String> resolved = new ArrayList<>(command);
			resolved.set(0, Platform.current().findProgram(command.getFirst(), builder.environment()));
			Process process = builder.command(resolved).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
			{
				process.destroyForcibly();
				throw new IOException("The command " + command + " did not end in " + TIMEOUT_SECONDS + " seconds.");
			}
			return output;
		};
	}

	/**
	 * Reads a harness's version.
	 *
	 * @param executable the harness's executable
	 * @param launcher runs {@code <executable> --version}
	 * @return the first non-blank line of the output, or {@code unknown} if there is none or the command fails
	 * @throws NullPointerException if any argument is null
	 */
	public static String read(String executable, Launcher launcher)
	{
		requireThat(executable, "executable").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
		try
		{
			return launcher.run(List.of(executable, "--version")).lines().map(String::strip).
				filter(line -> !line.isEmpty()).findFirst().orElse(UNKNOWN);
		}
		catch (IOException _)
		{
			return UNKNOWN;
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			return UNKNOWN;
		}
	}
}
