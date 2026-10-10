package io.github.cowwoc.cat.claude.secrets;

import io.github.cowwoc.cat.claude.optimizer.ClaudeSessions;
import io.github.cowwoc.cat.common.optimizer.adapter.Bridge;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;

import java.nio.file.Path;
import java.util.List;

/**
 * Exposes the creation of Claude session ports around a given bridge to the module's tests.
 */
public interface ClaudeSessionsAccess
{
	/**
	 * Creates a port.
	 *
	 * @param settings how the runner is configured
	 * @param bridge runs the runner's bridge
	 * @return the port
	 * @throws NullPointerException if any argument is null
	 */
	ClaudeSessions newClaudeSessions(RunnerSessions.Settings settings, Bridge bridge);

	/**
	 * Creates a port that reads the given managed settings files.
	 *
	 * @param settings how the runner is configured
	 * @param bridge runs the runner's bridge
	 * @param managedSettings the managed settings files that the host's administrator may install
	 * @return the port
	 * @throws NullPointerException if any argument is null
	 */
	ClaudeSessions newClaudeSessions(RunnerSessions.Settings settings, Bridge bridge,
		List<Path> managedSettings);
}
