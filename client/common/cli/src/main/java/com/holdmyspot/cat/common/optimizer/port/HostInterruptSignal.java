package com.holdmyspot.cat.common.optimizer.port;

import java.io.IOException;
import java.time.Instant;

/**
 * Reports whether the user interrupted the host turn that runs the optimizer.
 */
@FunctionalInterface
public interface HostInterruptSignal
{
	/**
	 * Indicates whether the host recorded a user interrupt later than an instant.
	 *
	 * @param start the instant that the caller started at
	 * @return {@code true} if an interrupt record later than {@code start} exists
	 * @throws NullPointerException if {@code start} is null
	 * @throws IOException if the host's record cannot be read
	 */
	boolean isInterruptedAfter(Instant start) throws IOException;
}
