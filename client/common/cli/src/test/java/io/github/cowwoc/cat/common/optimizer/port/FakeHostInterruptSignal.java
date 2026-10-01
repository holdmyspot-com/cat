package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link HostInterruptSignal} whose interrupt record the test writes.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeHostInterruptSignal implements HostInterruptSignal
{
	private final AtomicReference<Instant> interruptedAt = new AtomicReference<>();

	/**
	 * Creates a signal with no interrupt record.
	 */
	public FakeHostInterruptSignal()
	{
	}

	/**
	 * Records a user interrupt.
	 *
	 * @param at when the host recorded the interrupt
	 * @throws NullPointerException if {@code at} is null
	 */
	public void interrupt(Instant at)
	{
		requireThat(at, "at").isNotNull();
		interruptedAt.set(at);
	}

	@Override
	public boolean isInterruptedAfter(Instant start)
	{
		requireThat(start, "start").isNotNull();
		Instant at = interruptedAt.get();
		return at != null && at.isAfter(start);
	}
}
