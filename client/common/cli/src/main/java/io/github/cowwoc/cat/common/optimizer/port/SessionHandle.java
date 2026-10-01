package io.github.cowwoc.cat.common.optimizer.port;

import java.io.IOException;
import java.util.concurrent.CompletionStage;

/**
 * A nested session that has started.
 */
public interface SessionHandle
{
	/**
	 * Returns the session's result once it ends.
	 *
	 * @return a stage that completes with the result, or exceptionally with an {@link IOException} if the result
	 *   cannot be read
	 */
	CompletionStage<SessionResult> completion();

	/**
	 * Asks the session to stop. The completion then reports {@link SessionResult.Interrupted} unless the session
	 * had already ended. Interrupting a session that has ended does nothing.
	 *
	 * @throws IOException if the request cannot be delivered
	 */
	void interrupt() throws IOException;
}
