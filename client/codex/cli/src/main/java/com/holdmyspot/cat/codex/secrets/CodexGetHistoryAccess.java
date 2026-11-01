package com.holdmyspot.cat.codex.secrets;

import com.holdmyspot.cat.common.JvmScope;
import java.io.IOException;
import java.io.InputStream;
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
	 * @param scope the process scope
	 * @param environment supplies {@code CODEX_HOME}, the directory that contains Codex rollouts, or
	 *   {@code HOME}, whose {@code .codex} subdirectory is used when {@code CODEX_HOME} is unset or blank
	 * @param args a UUID thread identifier followed by a session-history operation and its arguments
	 * @param input the message text that the {@code request-status} operation reads
	 * @return the selected transcript and operation result
	 * @throws IllegalArgumentException if {@code args} does not start with a UUID thread identifier and an
	 *   operation, or {@code request-status} has arguments or reads blank text
	 * @throws IOException if the requested transcript cannot be resolved or read
	 */
	String execute(JvmScope scope, Map<String, String> environment, String[] args, InputStream input)
		throws IOException;
}
