package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.WaitUntil;
import com.holdmyspot.cat.common.secrets.WaitUntilTimer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Checks that a {@link WaitUntilTimer} measures the time that a {@link WaitUntil} waits and hands it each run that
 * ends: a run that already ended at once, a run that ends during the wait when it ends, and no run once the wait's
 * timeout passed on the timer's readings.
 */
public final class WaitUntilTimerContract
{
	/**
	 * The time that a run takes to end, or that a wait lasts before its timeout passes.
	 */
	private static final Duration SHORT = Duration.ofMillis(20);
	/**
	 * A timeout that a wait for a run that ends never reaches.
	 */
	private static final Duration LONG = Duration.ofSeconds(5);
	/**
	 * The run that ends.
	 */
	private static final String RUN = "ended";

	/**
	 * The timer under contract.
	 */
	private final Subject subject;

	/**
	 * Creates checks of one timer.
	 *
	 * @param subject the timer under contract
	 */
	public WaitUntilTimerContract(Subject subject)
	{
		this.subject = subject;
	}

	/**
	 * Verifies that a wait for a run that already ended returns that run at once and removes it from the queue.
	 *
	 * @throws InterruptedException if the thread is interrupted while it waits
	 */
	public void returnsEndedRun() throws InterruptedException
	{
		WaitUntilTimer timer = subject.timer();
		BlockingQueue<String> ended = new LinkedBlockingQueue<>(List.of(RUN));
		long start = timer.nanoTime();

		String run = timer.poll(ended, LONG);

		requireThat(run, "run").isEqualTo(RUN);
		requireThat(ended, "ended").isEmpty();
		requireThat(Duration.ofNanos(timer.nanoTime() - start), "elapsed").isLessThan(LONG);
	}

	/**
	 * Verifies that a wait for a run that ends during the wait returns that run once it ends.
	 *
	 * @throws InterruptedException if the thread is interrupted while it waits
	 */
	public void returnsRunThatEndsDuringWait() throws InterruptedException
	{
		WaitUntilTimer timer = subject.timer();
		BlockingQueue<String> ended = new LinkedBlockingQueue<>();
		long start = timer.nanoTime();
		subject.endAfter(ended, RUN, SHORT);

		String run = timer.poll(ended, LONG);

		Duration elapsed = Duration.ofNanos(timer.nanoTime() - start);
		requireThat(run, "run").isEqualTo(RUN);
		requireThat(elapsed, "elapsed").isGreaterThanOrEqualTo(SHORT).isLessThan(LONG);
	}

	/**
	 * Verifies that a wait during which no run ends returns nothing once its timeout passed on the timer's readings.
	 *
	 * @throws InterruptedException if the thread is interrupted while it waits
	 */
	public void returnsNothingAfterTimeout() throws InterruptedException
	{
		WaitUntilTimer timer = subject.timer();
		BlockingQueue<String> ended = new LinkedBlockingQueue<>();
		long start = timer.nanoTime();

		String run = timer.poll(ended, SHORT);

		requireThat(run, "run").isNull();
		requireThat(Duration.ofNanos(timer.nanoTime() - start), "elapsed").isGreaterThanOrEqualTo(SHORT);
	}

	/**
	 * The timer under contract and the runs that end while it waits.
	 */
	public interface Subject
	{
		/**
		 * Returns the timer under contract.
		 *
		 * @return the timer
		 */
		WaitUntilTimer timer();

		/**
		 * Makes a run end after a delay on the timer, as a command's process that exits after that delay does.
		 *
		 * @param ended the queue that the run joins when it ends
		 * @param run   the run
		 * @param delay the time until the run ends
		 */
		void endAfter(BlockingQueue<String> ended, String run, Duration delay);
	}
}
