package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.pouch10.core.LazyReference;
import io.github.cowwoc.pouch10.core.Reference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;


/**
 * Owns the mapper and services shared while one runner process serves requests for a harness execution.
 */
public final class HarnessRunnerScope implements AutoCloseable
{
	private boolean closed;
	private final Reference<JsonMapper> json = LazyReference.create(() -> JsonMapper.builder().
		enable(SerializationFeature.INDENT_OUTPUT).build());

	/**
	 * Creates an open harness-run scope.
	 */
	public HarnessRunnerScope()
	{
	}

	/**
	 * Returns the JSON mapper shared by this run's protocol and bridge.
	 *
	 * @return the shared JSON mapper
	 *
	 * @throws IllegalStateException if this scope is closed
	 */
	public JsonMapper json()
	{
		ensureOpen();
		return json.getValue();
	}

	/**
	 * Returns whether this scope is closed.
	 *
	 * @return true if this scope is closed
	 */
	public boolean isClosed()
	{
		return closed;
	}

	/**
	 * Prevents further service access.
	 */
	@Override
	public void close()
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
	private void ensureOpen()
	{
		if (closed)
			throw new IllegalStateException("Scope is closed");
	}
}
