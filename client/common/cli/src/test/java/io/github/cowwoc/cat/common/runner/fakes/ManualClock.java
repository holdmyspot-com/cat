package io.github.cowwoc.cat.common.runner.fakes;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock that advances only when a test advances it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ManualClock extends Clock
{
	private final AtomicReference<Instant> now;

	/**
	 * Creates a clock.
	 *
	 * @param start the instant that the clock reports until a test advances it
	 * @throws NullPointerException if {@code start} is null
	 */
	public ManualClock(Instant start)
	{
		requireThat(start, "start").isNotNull();
		this.now = new AtomicReference<>(start);
	}

	/**
	 * Moves the clock forward.
	 *
	 * @param amount how far to move the clock
	 * @throws NullPointerException if {@code amount} is null
	 * @throws IllegalArgumentException if {@code amount} is negative
	 */
	public void advance(Duration amount)
	{
		requireThat(amount, "amount").isNotNull();
		requireThat(amount.isNegative(), "amount.isNegative").isFalse();
		now.updateAndGet(current -> current.plus(amount));
	}

	@Override
	public ZoneId getZone()
	{
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone)
	{
		requireThat(zone, "zone").isNotNull();
		return this;
	}

	@Override
	public Instant instant()
	{
		return now.get();
	}
}
