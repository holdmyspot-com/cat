package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one started harness process and its isolated execution directory for multiple requests.
 */
public final class HarnessRunSession implements AutoCloseable
{
	private final HarnessRun run;
	private final HarnessProcess process;
	private final HarnessRunnerScope scope;
	private final HarnessRunLock lock;
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * Attaches a live harness process to an owned run.
	 *
	 * @param run the owned run
	 * @param process the live harness process
	 * @param scope services owned for the process lifetime
	 * @throws NullPointerException if {@code run}, {@code process}, or {@code scope} are null
	 */
	public HarnessRunSession(HarnessRun run, HarnessProcess process, HarnessRunnerScope scope)
	{
		this(run, process, scope, HarnessRunLock.unlocked());
	}

	/**
	 * Attaches a live harness process and its exclusive run lock to an owned run.
	 *
	 * @param run the owned run
	 * @param process the live harness process
	 * @param scope services owned for the process lifetime
	 * @param lock the exclusive run lock
	 * @throws NullPointerException if {@code run}, {@code process}, {@code scope}, or {@code lock} are null
	 */
	public HarnessRunSession(HarnessRun run, HarnessProcess process, HarnessRunnerScope scope, HarnessRunLock lock)
	{
		requireThat(run, "run").isNotNull();
		requireThat(process, "process").isNotNull();
		requireThat(scope, "scope").isNotNull();
		requireThat(lock, "lock").isNotNull();
		this.run = run;
		this.process = process;
		this.scope = scope;
		this.lock = lock;
	}

	/**
	 * Sends one turn to the already-running harness process.
	 *
	 * @param request the framed turn request
	 * @return the terminal result for the turn
	 * @throws IOException if the harness process is no longer alive or the turn fails
	 */
	public HarnessProcessResult sendTurn(String request) throws IOException
	{
		return sendTurn(request, event ->
		{
		});
	}

	/**
	 * Sends one turn and forwards harness events while the process remains live.
	 *
	 * @param request the framed turn request
	 * @param listener receives raw harness event lines
	 * @return the terminal result for the turn
	 * @throws IOException if the harness process is no longer alive, the turn fails, or event forwarding fails
	 */
	public HarnessProcessResult sendTurn(String request, HarnessEventListener listener) throws IOException
	{
		requireThat(request, "request").isNotBlank();
		requireThat(listener, "listener").isNotNull();
		synchronized (process)
		{
			if (!process.isAlive())
				throw new IOException("Harness process is no longer alive for run " + run.id());
			return process.sendTurn(request, listener);
		}
	}

	/**
	 * Sends additional user direction to the currently active turn.
	 *
	 * @param request the non-blank direction
	 * @throws IOException if the process is unavailable or cannot accept the direction
	 */
	public void steer(String request) throws IOException
	{
		requireThat(request, "request").isNotBlank();
		if (!processIsAlive())
			throw new IOException("Harness process is no longer alive for run " + run.id());
		process.steer(request);
	}

	/**
	 * Interrupts the currently active turn without closing this session.
	 *
	 * @throws IOException if the process is unavailable or cannot interrupt the turn
	 */
	public void interrupt() throws IOException
	{
		if (!processIsAlive())
			throw new IOException("Harness process is no longer alive for run " + run.id());
		process.interrupt();
	}

	/**
	 * Returns the owned run.
	 *
	 * @return the owned run
	 */
	public HarnessRun run()
	{
		return run;
	}

	/**
	 * Returns services shared by this session's process and bridge.
	 *
	 * @return the live session scope
	 */
	public HarnessRunnerScope scope()
	{
		return scope;
	}

	/**
	 * Returns whether the owned harness process remains alive.
	 *
	 * @return true if the harness process remains alive
	 */
	public boolean processIsAlive()
	{
		return !closed.get() && process.isAlive();
	}

	/**
	 * Gracefully completes the live harness process.
	 *
	 * @throws IOException if process shutdown fails
	 */
	@Override
	public void close() throws IOException
	{
		if (!closed.compareAndSet(false, true))
			return;
		synchronized (process)
		{
			try (lock; scope)
			{
				process.close();
			}
		}
	}
}
