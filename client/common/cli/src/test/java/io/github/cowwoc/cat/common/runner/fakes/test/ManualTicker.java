package io.github.cowwoc.cat.common.runner.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.HeartbeatTicker;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link HeartbeatTicker} that runs its action only when a test runs it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ManualTicker implements HeartbeatTicker
{
	private final AtomicReference<Runnable> tick = new AtomicReference<>();

	/**
	 * Creates a ticker that has no action.
	 */
	public ManualTicker()
	{
	}

	@Override
	public Closeable start(Runnable tick)
	{
		this.tick.set(tick);
		return () -> this.tick.set(null);
	}

	/**
	 * Runs the action once.
	 *
	 * @throws IllegalArgumentException if no action is running
	 */
	public void tick()
	{
		Runnable current = tick.get();
		requireThat(current, "tick").isNotNull();
		current.run();
	}
}
