package com.holdmyspot.cat.common.runner.launcher.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads the Git author identity of a project, as its session sees it, so that the project's copy keeps it.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
public interface GitIdentity
{
	/**
	 * Returns the author name.
	 *
	 * @param project the project directory
	 * @return the name, or empty if none is configured
	 * @throws NullPointerException if {@code project} is null
	 * @throws IOException          if Git cannot be run
	 */
	Optional<String> name(Path project) throws IOException;

	/**
	 * Returns the author email.
	 *
	 * @param project the project directory
	 * @return the email, or empty if none is configured
	 * @throws NullPointerException if {@code project} is null
	 * @throws IOException          if Git cannot be run
	 */
	Optional<String> email(Path project) throws IOException;

	/**
	 * Returns the identity that {@code git config} reports in the project.
	 *
	 * @return the reader
	 */
	static GitIdentity fromGit()
	{
		return new GitConfigIdentity();
	}
}
