package com.holdmyspot.cat.common.installer.engine.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.engine.internal.LockPause;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests how long an installer operation pauses between attempts to take the harness lock.
 */
public final class LockPauseTest
{
	private static final Duration WAIT = Duration.ofMinutes(5);
	private static final Instant START = Instant.EPOCH;
	private static final Instant DEADLINE = START.plus(WAIT);

	/**
	 * Creates the test.
	 */
	public LockPauseTest()
	{
	}

	/**
	 * Supplies the clock reading, the elapsed time and the pause they make.
	 *
	 * @return the current instant, the elapsed time and the pause
	 */
	@DataProvider
	public Object[][] pauses()
	{
		return new Object[][]
			{
				{START, Duration.ZERO, Optional.of(Duration.ofMillis(50))},
				{START.plusSeconds(10), Duration.ofSeconds(10), Optional.of(Duration.ofMillis(50))},
				{DEADLINE.minusMillis(20), WAIT.minusMillis(20), Optional.of(Duration.ofMillis(20))},
				{DEADLINE.minusMillis(50), WAIT.minusMillis(50), Optional.of(Duration.ofMillis(50))},
				{DEADLINE, WAIT, Optional.empty()},
				{DEADLINE.plusSeconds(1), WAIT.plusSeconds(1), Optional.empty()},
				// A clock that jumped forward ends the wait early
				{DEADLINE.minusMillis(20), Duration.ofSeconds(1), Optional.of(Duration.ofMillis(20))},
				{DEADLINE.plusSeconds(1), Duration.ofSeconds(1), Optional.empty()},
				// A clock that stalled or jumped back does not extend the wait
				{START, WAIT.minusMillis(20), Optional.of(Duration.ofMillis(20))},
				{START, WAIT, Optional.empty()}
			};
	}

	/**
	 * The pause is at most the poll interval and never passes the earlier end of the wait.
	 *
	 * @param now     the current instant on the clock
	 * @param elapsed the elapsed time
	 * @param pause   the pause
	 */
	@Test(dataProvider = "pauses")
	public void pauseNeverPassesEndOfWait(Instant now, Duration elapsed, Optional<Duration> pause)
	{
		requireThat(LockPause.next(now, DEADLINE, WAIT, elapsed), "pause").isEqualTo(pause);
	}

	/**
	 * A zero wait makes exactly one attempt.
	 */
	@Test
	public void zeroWaitNeverPauses()
	{
		requireThat(LockPause.next(START, START, Duration.ZERO, Duration.ZERO), "pause").isEqualTo(Optional.empty());
	}
}
