package io.github.cowwoc.cat.codex.secrets;

import java.io.IOException;
import java.util.Map;

/**
 * Exposes the operations of the Codex session-history command to the module's tests.
 */
@FunctionalInterface
public interface CodexGetHistoryAccess
{
	/**
	 * Runs the requested operation for an explicit Codex thread.
	 *
	 * @param environment supplies {@code CODEX_HOME}, the directory that contains Codex rollouts, or
	 *   {@code HOME}, whose {@code .codex} subdirectory is used when {@code CODEX_HOME} is unset or blank
	 * @param args a UUID thread identifier followed by a session-history operation and its arguments
	 * @return the selected transcript and operation result
	 * @throws IllegalArgumentException if {@code args} does not start with a UUID thread identifier and an
	 *   operation
	 * @throws IOException if the requested transcript cannot be resolved or read
	 */
	String execute(Map<String, String> environment, String[] args) throws IOException;
}
