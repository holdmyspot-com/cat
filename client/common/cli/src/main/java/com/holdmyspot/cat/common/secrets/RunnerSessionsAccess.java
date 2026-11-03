package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;

import java.time.Duration;

/**
 * Exposes how often the driver of a {@link RunnerSessions} session checks its clock to the module's tests.
 */
@FunctionalInterface
public interface RunnerSessionsAccess
{
	/**
	 * Starts a session whose driver wakes at an interval to check the clock and the interrupt request.
	 *
	 * @param sessions the port that starts the session
	 * @param request  what the session does
	 * @param poll     the longest time that the driver waits for the runner before it checks the clock again
	 * @return the session
	 * @throws NullPointerException if any argument is null
	 */
	SessionHandle start(RunnerSessions sessions, SessionRequest request, Duration poll);
}
