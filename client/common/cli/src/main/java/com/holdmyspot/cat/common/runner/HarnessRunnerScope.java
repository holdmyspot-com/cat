package com.holdmyspot.cat.common.runner;

import io.github.cowwoc.pouch10.core.LazyReference;
import io.github.cowwoc.pouch10.core.Reference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Owns the mapper and services shared while one runner process serves requests for a harness execution.
 * <p>
 * {@link MainHarnessRunnerScope} supplies the production clock and heartbeat; tests supply their own scope.
 */
public abstract class HarnessRunnerScope implements AutoCloseable
{
	private boolean closed;
	private final Reference<JsonMapper> json = LazyReference.create(() -> JsonMapper.builder().
		enable(SerializationFeature.INDENT_OUTPUT).build());

	/**
	 * Creates an open harness-run scope.
	 */
	protected HarnessRunnerScope()
	{
	}

	/**
	 * Returns the JSON mapper shared by this run's protocol and bridge.
	 *
	 * @return the shared JSON mapper
	 *
	 * @throws IllegalStateException if this scope is closed
	 */
	public final JsonMapper json()
	{
		ensureOpen();
		return json.getValue();
	}

	/**
	 * Returns the clock that measures how long a bridge turn has been quiet.
	 *
	 * @return the clock
	 *
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract Clock clock();

	/**
	 * Returns the heartbeat that runs the bridge's check for a quiet turn.
	 *
	 * @return the heartbeat
	 *
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract HeartbeatTicker heartbeatTicker();

	/**
	 * Prevents further service access.
	 */
	@Override
	public final void close()
	{
		if (closed)
			return;
		closed = true;
	}

	/**
	 * Ensures this scope remains open.
	 *
	 * @throws IllegalStateException if this scope is closed
	 */
	protected final void ensureOpen()
	{
		if (closed)
			throw new IllegalStateException("Scope is closed");
	}
}
