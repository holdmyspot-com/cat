package com.holdmyspot.cat.common;

import java.nio.file.Path;

/**
 * The production implementation of {@link HookScope}.
 */
public final class MainHookScope extends AbstractHookScope
{
	/**
	 * Creates a hook scope with explicit process values and directories.
	 *
	 * @param parent the owned process scope
	 * @param pluginRoot the installation directory
	 * @param userHome the user home directory
	 * @param harness the session harness
	 * @param configurationDirectory the harness configuration directory
	 * @throws NullPointerException if any argument is null
	 */
	public MainHookScope(JvmScope parent, Path pluginRoot, Path userHome, SessionHarness harness,
		Path configurationDirectory)
	{
		this(parent, pluginRoot, userHome, harness, configurationDirectory, SessionIgnore.GIT_PROCESS);
	}

	/**
	 * Creates a hook scope whose session directories decide what to add to the project's {@code .gitignore} from the
	 * answers that a given Git gives.
	 *
	 * @param parent                 the owned process scope
	 * @param pluginRoot             the installation directory
	 * @param userHome               the user home directory
	 * @param harness                the session harness
	 * @param configurationDirectory the harness configuration directory
	 * @param git                    answers what Git ignores in the project
	 * @throws NullPointerException if any argument is null
	 */
	MainHookScope(JvmScope parent, Path pluginRoot, Path userHome, SessionHarness harness, Path configurationDirectory,
		SessionIgnore.GitQueries git)
	{
		super(parent, pluginRoot, userHome, harness, configurationDirectory, git);
	}
}
