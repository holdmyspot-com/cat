package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.WaitUntil;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;

/**
 * Reads the time that passes during a {@link WaitUntil} and waits for the next run of a command to end.
 * <p>
 * A wait measures its deadline and the interval between a check's runs through its timer, so that the module's tests
 * decide when time passes instead of waiting for it.
 */
public interface WaitUntilTimer
{
	/**
	 * Returns the current reading of a monotonic time source.
	 *
	 * @return the reading in nanoseconds, which is comparable only with other readings of the same timer
	 */
	long nanoTime();

	/**
	 * Waits until a queue has an element or a timeout passes.
	 *
	 * @param <E>     the type of the queue's elements
	 * @param queue   the queue
	 * @param timeout the longest time to wait
	 * @return the queue's head, which this method removes, or {@code null} if the timeout passed first
	 * @throws InterruptedException if the thread is interrupted while it waits
	 */
	<E> E poll(BlockingQueue<E> queue, Duration timeout) throws InterruptedException;
}
