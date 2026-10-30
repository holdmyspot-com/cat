package com.holdmyspot.cat.codex.secrets;

import com.holdmyspot.cat.codex.optimizer.CodexOptimizerCommandScope;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Exposes the creation of Codex command scopes and their interrupt signal to the module's tests.
 */
public interface CodexOptimizerCommandScopeAccess
{
	/**
	 * Creates a scope.
	 *
	 * @param environment the process environment
	 * @param home the user's home directory
	 * @param directory the working directory
	 * @param artifact the complete Codex artifact that nested sessions install
	 * @param waiting how the process waits between the attempts of a retried operation
	 * @return the scope
	 * @throws NullPointerException if any argument is null
	 */
	CodexOptimizerCommandScope newCodexOptimizerCommandScope(Map<String, String> environment, Path home,
		Path directory, Path artifact, Waiting waiting);

	/**
	 * Returns the signal that reports the host user's interrupts. The host session's rollout is located when the
	 * signal is first polled, so commands that never poll it, such as {@code status}, do not need it.
	 *
	 * @param environment the process environment
	 * @param configurationDirectory the host's Codex configuration directory
	 * @param clock the clock
	 * @return a signal that never reports an interrupt if the host session is not named in the environment.
	 *   Otherwise its first poll throws {@code IOException} if the session has no rollout.
	 */
	HostInterruptSignal interrupts(Map<String, String> environment, Path configurationDirectory, Clock clock);
}
