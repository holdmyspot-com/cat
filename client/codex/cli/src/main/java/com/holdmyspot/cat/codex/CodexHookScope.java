package com.holdmyspot.cat.codex;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.HookScope;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.Waiting;
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
	 * @param waiting how the hook waits between the attempts of a retried operation
	 * @return the scope
	 * @throws IOException if Codex did not supply a usable plugin root or home directory, or if a CAT variable
	 *   conflicts with the plugin root
	 */
	static HookScope open(Map<String, String> environment, Waiting waiting) throws IOException
	{
		return HookScope.fromEnvironment(environment, "Codex", "PLUGIN_ROOT", Platform.current().homeVariable(),
			CodexSessionHarness.HARNESS, waiting);
	}

	/**
	 * Opens a scope for one Codex hook invocation whose session directories decide what to add to the project's
	 * {@code .gitignore} from the answers that a given Git gives.
	 *
	 * @param environment the non-null harness hook environment
	 * @param waiting     how the hook waits between the attempts of a retried operation
	 * @param git         answers what Git ignores in the project
	 * @return the scope
	 * @throws IOException if Codex did not supply a usable plugin root or home directory, or if a CAT variable
	 *   conflicts with the plugin root
	 */
	static HookScope open(Map<String, String> environment, Waiting waiting, SessionIgnore.GitQueries git)
		throws IOException
	{
		return HookScope.fromEnvironment(environment, "Codex", "PLUGIN_ROOT", Platform.current().homeVariable(),
			CodexSessionHarness.HARNESS, waiting, git);
	}
}
