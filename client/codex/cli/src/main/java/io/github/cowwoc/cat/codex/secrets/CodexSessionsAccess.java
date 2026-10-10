package io.github.cowwoc.cat.codex.secrets;

import io.github.cowwoc.cat.codex.optimizer.CodexSessions;
import io.github.cowwoc.cat.common.optimizer.adapter.Bridge;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;

/**
 * Exposes the creation of Codex session ports around a given bridge to the module's tests.
 */
@FunctionalInterface
public interface CodexSessionsAccess
{
	/**
	 * Creates a port.
	 *
	 * @param settings how the runner is configured
	 * @param bridge runs the runner's bridge
	 * @return the port
	 * @throws NullPointerException if any argument is null
	 */
	CodexSessions newCodexSessions(RunnerSessions.Settings settings, Bridge bridge);
}
