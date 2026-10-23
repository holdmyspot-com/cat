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
		super(parent, pluginRoot, userHome, harness, configurationDirectory);
	}
}
