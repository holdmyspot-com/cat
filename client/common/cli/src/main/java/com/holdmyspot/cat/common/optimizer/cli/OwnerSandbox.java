package com.holdmyspot.cat.common.optimizer.cli;

import java.io.IOException;
import java.util.Optional;

/**
 * Says whether the session that runs a prompt optimizer command runs its commands in a sandbox. Trials run in the same
 * type of sandbox as the session that starts them, so a command that can start a trial needs one.
 */
@FunctionalInterface
public interface OwnerSandbox
{
	/**
	 * Returns why the session that runs the command cannot start trials.
	 *
	 * @return the reason, which names the cause and the fix, or empty if the session runs its commands in a sandbox
	 * @throws IOException if the session's sandbox cannot be determined
	 */
	Optional<String> refusal() throws IOException;
}
