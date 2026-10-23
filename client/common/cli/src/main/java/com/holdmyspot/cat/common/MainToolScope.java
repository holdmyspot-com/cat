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
	 * Creates a tool scope from the current process environment without a session.
	 */
	public MainToolScope()
	{
		this(new MainJvmScope(System.getenv()), Path.of(""), Map.of(), List.of());
	}

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
		super(parent, workingDirectory, environment, harnesses);
	}
}
