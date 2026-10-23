package com.holdmyspot.cat.common.fakes.test;

import com.holdmyspot.cat.common.AbstractHookScope;
import com.holdmyspot.cat.common.HookScope;
import com.holdmyspot.cat.common.SessionHarness;
import java.nio.file.Path;

/**
 * Supplies {@link HookScope} services with explicit harness directories and the build's temporary directory.
 */
public final class TestHookScope extends AbstractHookScope
{
	/**
	 * Creates an open test hook scope.
	 *
	 * @param pluginRoot             the plugin installation directory
	 * @param userHome               the test user's home directory
	 * @param harness                the session harness
	 * @param configurationDirectory the harness configuration directory
	 * @throws NullPointerException if any argument is null
	 */
	public TestHookScope(Path pluginRoot, Path userHome, SessionHarness harness, Path configurationDirectory)
	{
		super(new TestJvmScope(), pluginRoot, userHome, harness, configurationDirectory);
	}
}
