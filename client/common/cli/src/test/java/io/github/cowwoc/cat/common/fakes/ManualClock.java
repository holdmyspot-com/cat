package io.github.cowwoc.cat.common.fakes;

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
	private final ZoneId zone;

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
		zone = ZoneOffset.UTC;
	}

	/**
	 * Creates a zone view sharing another clock's manually controlled time.
	 *
	 * @param now the shared time
	 * @param zone the view's time zone
	 */
	private ManualClock(AtomicReference<Instant> now, ZoneId zone)
	{
		this.now = now;
		this.zone = zone;
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
	 * Moves the time by a duration, forward for positive values and backward for negative ones.
	 *
	 * @param duration the amount to add to the current instant
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
		return zone;
	}

	@Override
	public Clock withZone(ZoneId zone)
	{
		requireThat(zone, "zone").isNotNull();
		if (this.zone.equals(zone))
			return this;
		return new ManualClock(now, zone);
	}

	@Override
	public Instant instant()
	{
		return now.get();
	}
}
