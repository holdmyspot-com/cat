package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * The queue through which session and check-program completions reach the {@link Driver}'s thread.
 * <p>
 * Completion callbacks and check workers only post here. The driver consumes the events
 * on its own thread, so the campaign state has a single writer.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class SessionEvents
{
	private final BlockingDeque<Event> events = new LinkedBlockingDeque<>();

	/**
	 * Creates an empty queue.
	 */
	SessionEvents()
	{
	}

	/**
	 * Posts the end of a session or check evaluation.
	 *
	 * @param event the event
	 * @throws NullPointerException if {@code event} is null
	 */
	void post(Event event)
	{
		requireThat(event, "event").isNotNull();
		events.addLast(event);
	}

	/**
	 * Removes every posted event without waiting.
	 *
	 * @return the events, in posting order
	 */
	List<Event> drain()
	{
		List<Event> drained = new ArrayList<>();
		events.drainTo(drained);
		return drained;
	}

	/**
	 * Indicates whether an event is available before the wait ends, without consuming it.
	 *
	 * @param timeout the longest time to wait
	 * @return {@code true} if an event is waiting
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	boolean await(Duration timeout) throws InterruptedException
	{
		Event event = events.pollFirst(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
		if (event == null)
			return false;
		events.addFirst(event);
		return true;
	}

	/** An in-flight operation's completion. */
	sealed interface Event
	{
		/**
		 * Returns the operation's driver-local identifier.
		 *
		 * @return the operation token
		 */
		long token();
	}

	/**
	 * The end of one session.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param token the driver's identifier of the session
	 * @param result the session's result, or {@code null} if the session failed with an exception
	 * @param failure the exception, or {@code null} if the session returned a result
	 */
	record SessionEnded(long token, SessionResult result, Throwable failure) implements Event
	{
		/**
		 * Creates an event.
		 *
		 * @param token the driver's identifier of the session
		 * @param result the session's result, or {@code null} if the session failed with an exception
		 * @param failure the exception, or {@code null} if the session returned a result
		 * @throws IllegalArgumentException if {@code result} and {@code failure} are both null or both set
		 */
		SessionEnded
		{
			requireThat(result == null, "resultIsNull").isEqualTo(failure != null, "failureIsSet");
		}
	}

	/**
	 * The end of one completed trial's check evaluation.
	 *
	 * @param token the driver's identifier of the evaluation
	 * @param results the trial's built-in results, or null if the evaluation failed
	 * @param failure the exception if the evaluation failed, or null if it returned results
	 */
	record ChecksEnded(long token, List<BuiltInToolResult> results, Throwable failure) implements Event
	{
		/**
		 * Creates the event and freezes any results.
		 *
		 * @param token the driver's identifier of the evaluation
		 * @param results the built-in results, or null if the evaluation failed
		 * @param failure the failure, or null if the evaluation returned results
		 * @throws IllegalArgumentException if results and failure are both null or both set
		 * @throws NullPointerException if a result entry is null
		 */
		ChecksEnded
		{
			requireThat(results == null, "resultsAreNull").isEqualTo(failure != null, "failureIsSet");
			if (results != null)
				results = List.copyOf(results);
		}
	}
}
