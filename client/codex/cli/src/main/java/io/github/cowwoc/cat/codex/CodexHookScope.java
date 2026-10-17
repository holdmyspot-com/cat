package io.github.cowwoc.cat.codex;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.HookScope;
import java.io.IOException;
import java.util.Map;

/**
 * Translates Codex's hook environment at the harness boundary.
 */
final class CodexHookScope
{
	/**
	 * Prevents instantiation.
	 */
	private CodexHookScope()
	{
	}

	/**
	 * Opens a scope for one Codex hook invocation.
	 *
	 * @param environment the non-null harness hook environment
	 * @return the scope
	 * @throws IOException if Codex did not supply a usable plugin root or home directory, or if a CAT variable
	 *   conflicts with the plugin root
	 */
	static HookScope open(Map<String, String> environment) throws IOException
	{
		return HookScope.fromEnvironment(environment, "Codex", "PLUGIN_ROOT", Platform.current().homeVariable(),
			CodexSessionHarness.HARNESS);
	}
}
