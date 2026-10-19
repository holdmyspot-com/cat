package com.holdmyspot.cat.common.optimizer.port.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;
import com.holdmyspot.cat.common.optimizer.port.contract.test.HostInterruptSignalContract;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link HostInterruptSignal} whose interrupt record the test writes.
 * <p>
 * {@link HostInterruptSignalContract} checks its timestamp behavior against the real host signals.
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
	 * Records a user interrupt, retaining the latest timestamp when records arrive out of order.
	 *
	 * @param at when the host recorded the interrupt
	 * @throws NullPointerException if {@code at} is null
	 */
	public void interrupt(Instant at)
	{
		requireThat(at, "at").isNotNull();
		interruptedAt.updateAndGet(latest ->
		{
			if (latest == null || at.isAfter(latest))
				return at;
			return latest;
		});
	}

	@Override
	public boolean isInterruptedAfter(Instant start)
	{
		requireThat(start, "start").isNotNull();
		Instant at = interruptedAt.get();
		return at != null && at.isAfter(start);
	}
}
