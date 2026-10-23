package com.holdmyspot.cat.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Supplies services for one CLI tool operation.
 */
public interface ToolScope extends JvmScope
{
	/**
	 * Creates a tool scope from the session environment.
	 *
	 * @param workingDirectory the tool working directory
	 * @param environment the tool environment
	 * @param harnesses the supported harnesses in consultation order
	 * @return the open scope
	 * @throws NullPointerException if any argument is null
	 */
	static ToolScope forSession(Path workingDirectory, Map<String, String> environment,
		List<SessionHarness> harnesses)
	{
		return AbstractToolScope.forSession(workingDirectory, environment, harnesses);
	}

	/**
	 * Indicates whether a CAT project encloses the tool's working directory.
	 *
	 * @return {@code true} if the working directory or one of its parents holds a {@code .cat} directory
	 * @throws IllegalStateException if this scope is closed
	 */
	boolean isInProject();

	/**
	 * Returns the CAT session directory of the session that the first set session variable names, creating it and its
	 * marker on first use. Without a CAT project, the directory is in the tool's working directory.
	 *
	 * @return the CAT session directory, or empty when no session variable is set
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if the session variable is not a UUID in canonical form, if the harness's configuration
	 *   directory cannot be found, or if the directory or its marker cannot be created
	 */
	Optional<SessionDirectory> sessionDirectory() throws IOException;

	/**
	 * Returns the CAT session directory of the session that the first set session variable names, as
	 * {@link #sessionDirectory()} does, for a tool that cannot run without one.
	 *
	 * @return the CAT session directory
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if no session variable is set, with a message that names every session variable, or for any
	 *   reason that {@link #sessionDirectory()} throws
	 */
	SessionDirectory requireSessionDirectory() throws IOException;

	/**
	 * Returns the sentence that reports a tool shell without a session variable.
	 *
	 * @return the sentence, which names every session variable that this scope consults
	 * @throws IllegalStateException if this scope is closed
	 */
	String missingSession();
}
