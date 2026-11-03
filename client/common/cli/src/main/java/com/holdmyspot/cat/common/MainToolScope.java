package com.holdmyspot.cat.common;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The production implementation of {@link ToolScope}.
 */
public final class MainToolScope extends AbstractToolScope
{
	/**
	 * Creates a tool scope with explicit process and session values.
	 *
	 * @param parent the owned process scope
	 * @param workingDirectory the tool working directory
	 * @param environment the session environment
	 * @param harnesses the supported harnesses in consultation order
	 * @throws NullPointerException if any argument is null
	 */
	public MainToolScope(JvmScope parent, Path workingDirectory, Map<String, String> environment,
		List<SessionHarness> harnesses)
	{
		this(parent, workingDirectory, environment, harnesses, SessionIgnore.GIT_PROCESS);
	}

	/**
	 * Creates a tool scope whose session directory decides what to add to the project's {@code .gitignore} from the
	 * answers that a given Git gives.
	 *
	 * @param parent           the owned process scope
	 * @param workingDirectory the tool working directory
	 * @param environment      the session environment
	 * @param harnesses        the supported harnesses in consultation order
	 * @param git              answers what Git ignores in the project
	 * @throws NullPointerException if any argument is null
	 */
	MainToolScope(JvmScope parent, Path workingDirectory, Map<String, String> environment,
		List<SessionHarness> harnesses, SessionIgnore.GitQueries git)
	{
		super(parent, workingDirectory, environment, harnesses, git);
	}
}
