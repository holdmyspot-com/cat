package io.github.cowwoc.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.HostInterruptSignal;

import java.io.IOException;
import java.time.Instant;

/**
 * A host interrupt signal that locates the host session's log when it is first polled, so that a command that never
 * polls it, such as {@code status}, does not need the log.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe if the located signal is.
 */
public final class DeferredInterruptSignal implements HostInterruptSignal
{
	private final Locator locator;
	private final Object lock = new Object();
	private HostInterruptSignal located;

	/**
	 * Creates a signal.
	 *
	 * @param locator locates the signal that reads the host session's log
	 * @throws NullPointerException if {@code locator} is null
	 */
	public DeferredInterruptSignal(Locator locator)
	{
		requireThat(locator, "locator").isNotNull();
		this.locator = locator;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws IOException if the log cannot be located or read. A poll that fails to locate the log locates it
	 *   again on the next poll.
	 */
	@Override
	public boolean isInterruptedAfter(Instant start) throws IOException
	{
		HostInterruptSignal signal;
		synchronized (lock)
		{
			if (located == null)
				located = locator.locate();
			signal = located;
		}
		return signal.isInterruptedAfter(start);
	}

	/**
	 * Locates the signal that reads the host session's log.
	 */
	@FunctionalInterface
	public interface Locator
	{
		/**
		 * Locates the signal.
		 *
		 * @return the signal
		 * @throws IOException if the host session's log cannot be found
		 */
		HostInterruptSignal locate() throws IOException;
	}
}
