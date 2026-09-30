package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
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
	private final Path routeRuleRoot;
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
		this(run, process, scope, HarnessRunLock.unlocked(), null);
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
		this(run, process, scope, lock, null);
	}

	/**
	 * Attaches a live harness process to an owned run and the installed root that may satisfy declared CAT routes.
	 *
	 * @param run the owned run
	 * @param process the live harness process
	 * @param scope services owned for the process lifetime
	 * @param lock the exclusive run lock
	 * @param routeRuleRoot the caller-owned installed rules root, or {@code null} without direct CAT rule delivery
	 * @throws NullPointerException if {@code run}, {@code process}, {@code scope}, or {@code lock} are null
	 */
	public HarnessRunSession(HarnessRun run, HarnessProcess process, HarnessRunnerScope scope, HarnessRunLock lock,
		Path routeRuleRoot)
	{
		requireThat(run, "run").isNotNull();
		requireThat(process, "process").isNotNull();
		requireThat(scope, "scope").isNotNull();
		requireThat(lock, "lock").isNotNull();
		this.run = run;
		this.process = process;
		this.scope = scope;
		this.lock = lock;
		this.routeRuleRoot = routeRuleRoot;
	}

	/**
	 * Constructs the complete bridge input before its turns reach the native transport.
	 *
	 * @param input the caller's bridge input
	 * @return prepared input and any direct-rule receipt
	 * @throws IOException if input or a declared route cannot be prepared
	 */
	public RoutePreparationInput.PreparedInput prepareInput(Reader input) throws IOException
	{
		return RoutePreparationInput.prepare(input, routeRuleRoot, scope);
	}

	/**
	 * Prepares the first bridge request while leaving the caller's remaining input open for live controls.
	 *
	 * @param firstLine the first request, or null at end of input
	 * @return prepared requests and the remaining-input decision
	 * @throws IOException if the first request or its declared route is invalid
	 */
	RoutePreparationInput.PreparedPrefix prepareFirstLine(String firstLine) throws IOException
	{
		return RoutePreparationInput.prepareFirstLine(firstLine, routeRuleRoot, scope);
	}

	/**
	 * Reserves the next native turn before the bridge accepts a stop request for it.
	 *
	 * @throws IOException if the process cannot reserve the turn
	 */
	public void reserveTurn() throws IOException
	{
		process.reserveTurn();
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
	 * Sends one turn and forwards native events while the process remains live.
	 *
	 * @param request the framed turn request
	 * @param listener receives raw native event lines
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
	 * Returns the installed CAT rule root available to the route-selection bridge, if any.
	 *
	 * @return the installed rule root, or {@code null} when CAT route delivery is unavailable
	 */
	public Path routeRuleRoot()
	{
		return routeRuleRoot;
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
