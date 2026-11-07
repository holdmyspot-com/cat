package com.holdmyspot.cat.common.runner.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.HeartbeatTicker;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link HeartbeatTicker} that runs its actions only when a test runs them.
 * <p>
 * Like the scope's real ticker, it runs each started action apart from the others, until the action's handle closes.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ManualTicker implements HeartbeatTicker
{
	private final List<Runnable> running = new CopyOnWriteArrayList<>();

	/**
	 * Creates a ticker that runs no action.
	 */
	public ManualTicker()
	{
	}

	@Override
	public Closeable start(Runnable tick)
	{
		requireThat(tick, "tick").isNotNull();
		// A start of its own, so that closing it leaves another start of the same action running
		Runnable started = tick::run;
		running.add(started);
		return () -> running.remove(started);
	}

	/**
	 * Runs each running action once, as one period of the real ticker does.
	 *
	 * @throws IllegalArgumentException if no action is running
	 */
	public void tick()
	{
		requireThat(running, "running").isNotEmpty();
		for (Runnable action : running)
			action.run();
	}
}
