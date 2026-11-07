package com.holdmyspot.cat.common.runner.launcher.client;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

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
final class GitConfigIdentity implements GitIdentity
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
		try (Process process = builder.start())
		{
			// Git reads no input here. Closing the pipe gives it an empty input on every platform, which a null
			// device file would not: Windows names it NUL, not /dev/null.
			process.getOutputStream().close();
			String value;
			try (InputStream output = process.getInputStream())
			{
				value = new String(output.readAllBytes(), UTF_8).strip();
			}
			try
			{
				if (process.waitFor() != 0 || value.isEmpty())
					return Optional.empty();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while reading the Git identity of " + project, e);
			}
			return Optional.of(value);
		}
	}
}
