package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.CodeLoadingVariables;
import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Reads a harness's version from {@code <executable> --version}. The process is behind {@link Launcher}, so that a
 * test supplies a fake.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class HarnessVersion
{
	private static final String UNKNOWN = "unknown";
	private static final long TIMEOUT_SECONDS = 30;

	/**
	 * The output of a command.
	 *
	 * @param stdout the text that the command wrote to stdout
	 * @param stderr the text that the command wrote to stderr
	 */
	public record Output(String stdout, String stderr)
	{
		/**
		 * Creates the output of a command.
		 *
		 * @param stdout the text that the command wrote to stdout
		 * @param stderr the text that the command wrote to stderr
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Output
		{
			requireThat(stdout, "stdout").isNotNull();
			requireThat(stderr, "stderr").isNotNull();
		}
	}

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
		 * @return the command's stdout and stderr, each read separately
		 * @throws IOException if the process cannot start, fails to run or does not end in time
		 * @throws InterruptedException if the thread is interrupted while it waits
		 */
		Output run(List<String> command) throws IOException, InterruptedException;
	}

	/**
	 * Prevents instantiation.
	 */
	private HarnessVersion()
	{
	}

	/**
	 * Returns the launcher that starts a real process and waits 30 seconds for it to end. The launcher finds the
	 * executable as {@link Platform#findProgram} does, and reads stdout and stderr apart, so that a diagnostic on
	 * stderr never enters the text that a caller parses from stdout.
	 *
	 * @return the launcher
	 */
	public static Launcher process()
	{
		return command ->
		{
			ProcessBuilder builder = new ProcessBuilder(command);
			List<String> resolved = new ArrayList<>(command);
			resolved.set(0, Platform.current().findProgram(command.getFirst(), builder.environment()));
			Process process = CodeLoadingVariables.start(builder.command(resolved));
			try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor())
			{
				Future<String> stdout = readers.submit(() -> readAll(process.getInputStream()));
				Future<String> stderr = readers.submit(() -> readAll(process.getErrorStream()));
				if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
				{
					process.destroyForcibly();
					throw new IOException("The command " + command + " did not end in " + TIMEOUT_SECONDS + " seconds.");
				}
				return new Output(stdout.get(), stderr.get());
			}
			catch (ExecutionException e)
			{
				throw new IOException("The output of the command " + command + " could not be read.", e.getCause());
			}
		};
	}

	/**
	 * Reads a harness's version.
	 *
	 * @param executable the harness's executable
	 * @param launcher runs {@code <executable> --version}
	 * @return the first non-blank line of stdout, or of stderr if stdout has none, or {@code unknown} if neither has
	 *   one or the command fails
	 * @throws NullPointerException if any argument is null
	 */
	public static String read(String executable, Launcher launcher)
	{
		requireThat(executable, "executable").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
		try
		{
			Output output = launcher.run(List.of(executable, "--version"));
			return firstLine(output.stdout()).or(() -> firstLine(output.stderr())).orElse(UNKNOWN);
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

	/**
	 * Reads a process stream to its end.
	 *
	 * @param stream the stream
	 * @return the stream's text
	 * @throws IOException if the stream cannot be read
	 */
	private static String readAll(InputStream stream) throws IOException
	{
		try (stream)
		{
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Returns the first non-blank line of a text.
	 *
	 * @param text the text
	 * @return the stripped line, or empty if the text has none
	 */
	private static Optional<String> firstLine(String text)
	{
		return text.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst();
	}
}
