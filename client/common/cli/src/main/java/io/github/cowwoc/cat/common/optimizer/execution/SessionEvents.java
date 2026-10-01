package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.SessionResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * The queue through which session completions reach the {@link Driver}'s thread.
 * <p>
 * Completion callbacks run on whichever thread ends a session; they only post here. The driver consumes the events
 * on its own thread, so the campaign state has a single writer.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class SessionEvents
{
	private final BlockingDeque<SessionEvent> events = new LinkedBlockingDeque<>();

	/**
	 * Creates an empty queue.
	 */
	SessionEvents()
	{
	}

	/**
	 * Posts the end of a session.
	 *
	 * @param event the event
	 * @throws NullPointerException if {@code event} is null
	 */
	void post(SessionEvent event)
	{
		requireThat(event, "event").isNotNull();
		events.addLast(event);
	}

	/**
	 * Removes every posted event without waiting.
	 *
	 * @return the events, in posting order
	 */
	List<SessionEvent> drain()
	{
		List<SessionEvent> drained = new ArrayList<>();
		events.drainTo(drained);
		return drained;
	}

	/**
	 * Waits until an event is posted, without consuming it.
	 *
	 * @param timeout the longest time to wait
	 * @return {@code true} if an event is waiting
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	boolean await(Duration timeout) throws InterruptedException
	{
		SessionEvent event = events.pollFirst(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
		if (event == null)
			return false;
		events.addFirst(event);
		return true;
	}

	/**
	 * The end of one session.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param token the driver's identifier of the session
	 * @param result the session's result, or {@code null} if the session failed with an exception
	 * @param failure the exception, or {@code null} if the session returned a result
	 */
	record SessionEvent(long token, SessionResult result, Throwable failure)
	{
		/**
		 * Creates an event.
		 *
		 * @param token the driver's identifier of the session
		 * @param result the session's result, or {@code null} if the session failed with an exception
		 * @param failure the exception, or {@code null} if the session returned a result
		 * @throws IllegalArgumentException if {@code result} and {@code failure} are both null or both set
		 */
		SessionEvent
		{
			requireThat(result == null, "resultIsNull").isEqualTo(failure != null, "failureIsSet");
		}
	}
}
