package com.holdmyspot.cat.codex.secrets;

import com.holdmyspot.cat.codex.optimizer.CodexSessions;
import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.runner.launcher.client.GitIdentity;

/**
 * Exposes the creation of Codex session ports around a given bridge or Git identity reader to the module's tests.
 */
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

	/**
	 * Creates a port that runs sessions through the real Codex runner, reading each project's Git identity with the
	 * given reader.
	 *
	 * @param settings how the runner is configured
	 * @param git      reads the project's Git identity
	 * @return the port
	 * @throws NullPointerException if any argument is null
	 */
	CodexSessions newCodexSessions(RunnerSessions.Settings settings, GitIdentity git);
}
