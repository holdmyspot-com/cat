package io.github.cowwoc.cat.claude;

import io.github.cowwoc.cat.common.HookScope;
import java.io.IOException;
import java.util.Map;

/**
 * Translates Claude's hook environment at the harness boundary.
 */
final class ClaudeHookScope
{
	/**
	 * Prevents instantiation.
	 */
	private ClaudeHookScope()
	{
	}

	/**
	 * Opens a scope for one Claude hook invocation.
	 *
	 * @param environment the non-null harness hook environment
	 * @return the scope
	 * @throws IOException if Claude did not supply a usable plugin root or home directory, or if a CAT variable
	 *   conflicts with the plugin root
	 */
	static HookScope open(Map<String, String> environment) throws IOException
	{
		return HookScope.fromEnvironment(environment, "Claude", "CLAUDE_PLUGIN_ROOT", "HOME");
	}
}
