package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock that moves only when the test moves it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ManualClock extends Clock
{
	private final AtomicReference<Instant> now;

	/**
	 * Creates a clock.
	 *
	 * @param start the initial time
	 * @throws NullPointerException if {@code start} is null
	 */
	public ManualClock(Instant start)
	{
		requireThat(start, "start").isNotNull();
		this.now = new AtomicReference<>(start);
	}

	/**
	 * Sets the time.
	 *
	 * @param time the new time
	 * @throws NullPointerException if {@code time} is null
	 */
	public void set(Instant time)
	{
		requireThat(time, "time").isNotNull();
		now.set(time);
	}

	/**
	 * Moves the time forward.
	 *
	 * @param duration how far to move
	 * @throws NullPointerException if {@code duration} is null
	 */
	public void advance(Duration duration)
	{
		requireThat(duration, "duration").isNotNull();
		now.updateAndGet(time -> time.plus(duration));
	}

	@Override
	public ZoneId getZone()
	{
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public Instant instant()
	{
		return now.get();
	}
}
