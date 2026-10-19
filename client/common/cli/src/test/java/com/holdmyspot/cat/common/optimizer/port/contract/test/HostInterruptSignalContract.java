package com.holdmyspot.cat.common.optimizer.port.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;

import java.io.IOException;
import java.time.Instant;

/**
 * Checks the timestamp behavior shared by every {@link HostInterruptSignal} implementation.
 * <p>
 * Each test supplies an isolated signal and records interrupts using its implementation's input boundary. Real
 * adapters read test-owned host logs. Checks are plain methods because this package is not opened to TestNG.
 */
public final class HostInterruptSignalContract
{
	private static final Instant START = Instant.parse("2026-10-01T07:55:00Z");
	private final Subject subject;

	/**
	 * Creates the contract.
	 *
	 * @param subject the implementation under test
	 * @throws NullPointerException if {@code subject} is null
	 */
	public HostInterruptSignalContract(Subject subject)
	{
		requireThat(subject, "subject").isNotNull();
		this.subject = subject;
	}

	/**
	 * A signal and the input boundary that records its host's interrupts.
	 */
	public interface Subject
	{
		/**
		 * Returns the signal under test.
		 *
		 * @return the signal
		 */
		HostInterruptSignal port();

		/**
		 * Records an interrupt with the supplied timestamp.
		 *
		 * @param at when the host recorded the interrupt
		 * @throws IOException if the record cannot be written
		 */
		void interrupt(Instant at) throws IOException;
	}

	/**
	 * Checks that a signal without an interrupt record reports none, even for a start in the distant past.
	 *
	 * @throws IOException if the host record cannot be read
	 */
	public void reportsNoInterruptWithoutRecord() throws IOException
	{
		requireThat(subject.port().isInterruptedAfter(Instant.MIN), "withoutRecord").isFalse();
	}

	/**
	 * Checks that an interrupt counts only for an earlier start and remains visible on repeated polls.
	 *
	 * @throws IOException if the host record cannot be written or read
	 */
	public void comparesTimestampStrictly() throws IOException
	{
		subject.interrupt(START);
		HostInterruptSignal signal = subject.port();
		requireThat(signal.isInterruptedAfter(START), "equalTimestamp").isFalse();
		requireThat(signal.isInterruptedAfter(START.plusNanos(1)), "laterStart").isFalse();
		requireThat(signal.isInterruptedAfter(START.minusNanos(1)), "earlierStart").isTrue();
		requireThat(signal.isInterruptedAfter(START.minusNanos(1)), "repeatedPoll").isTrue();
	}

	/**
	 * Checks that recording an older interrupt cannot erase a later interrupt, before or after the first poll.
	 *
	 * @throws IOException if the host record cannot be written or read
	 */
	public void retainsLatestTimestamp() throws IOException
	{
		subject.interrupt(START.plusSeconds(2));
		subject.interrupt(START.minusSeconds(1));
		HostInterruptSignal signal = subject.port();
		requireThat(signal.isInterruptedAfter(START.plusSeconds(1)), "newestUnreadRecord").isTrue();
		subject.interrupt(START);
		requireThat(signal.isInterruptedAfter(START.plusSeconds(1)), "newestPreviouslyReadRecord").isTrue();
		requireThat(signal.isInterruptedAfter(START.plusSeconds(2)), "equalLatestTimestamp").isFalse();
	}

	/**
	 * Calls the signal with a null start, which every implementation must reject.
	 *
	 * @throws NullPointerException if the implementation rejects the null start as required
	 * @throws IOException if the host record cannot be read
	 */
	public void rejectsNullStart() throws IOException
	{
		subject.port().isInterruptedAfter(null);
	}
}
