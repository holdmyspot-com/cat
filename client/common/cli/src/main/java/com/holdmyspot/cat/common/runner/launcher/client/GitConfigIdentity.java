package com.holdmyspot.cat.common.runner.launcher.client;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.CodeLoadingVariables;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads a project's Git author identity with {@code git config --get}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class GitConfigIdentity implements GitIdentity
{
	/**
	 * Creates the reader.
	 */
	GitConfigIdentity()
	{
	}

	@Override
	public Optional<String> name(Path project) throws IOException
	{
		return read(project, "user.name");
	}

	@Override
	public Optional<String> email(Path project) throws IOException
	{
		return read(project, "user.email");
	}

	/**
	 * Interprets the result of {@code git config --get} for one key.
	 *
	 * @param status the command's exit status
	 * @param output the command's standard output
	 * @return the value without surrounding white space, or empty if the command failed, as it does when the key is not
	 *   set or the directory is not in a repository, or printed nothing
	 * @throws NullPointerException if {@code output} is null
	 */
	public static Optional<String> interpret(int status, String output)
	{
		requireThat(output, "output").isNotNull();
		String value = output.strip();
		if (status != 0 || value.isEmpty())
			return Optional.empty();
		return Optional.of(value);
	}

	/**
	 * Reads one configuration value.
	 *
	 * @param project the project directory
	 * @param key     the configuration key
	 * @return the value, or empty if it is not set or the directory is not in a repository
	 * @throws NullPointerException if {@code project} is null
	 * @throws IOException          if Git cannot be run
	 */
	private static Optional<String> read(Path project, String key) throws IOException
	{
		requireThat(project, "project").isNotNull();
		ProcessBuilder builder = new ProcessBuilder("git", "-C", project.toString(), "config", "--get", key).
			redirectError(Redirect.DISCARD);
		try (Process process = CodeLoadingVariables.start(builder))
		{
			// Git reads no input here. Closing the pipe gives it an empty input on every platform, which a null
			// device file would not: Windows names it NUL, not /dev/null.
			process.getOutputStream().close();
			String output;
			try (InputStream stream = process.getInputStream())
			{
				output = new String(stream.readAllBytes(), UTF_8);
			}
			try
			{
				return interpret(process.waitFor(), output);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while reading the Git identity of " + project, e);
			}
		}
	}
}
