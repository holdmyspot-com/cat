package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one started harness process and its isolated execution directory for multiple requests.
 */
public final class HarnessRunSession implements AutoCloseable
{
	private final HarnessRun run;
	private final HarnessProcess process;
	private final AbstractHarnessRunnerScope scope;
	private final HarnessRunLock lock;
	/**
	 * The directory of the CAT plugin installed in the run's home, whose launchers the session's commands run, or
	 * {@code null} if the run has none.
	 */
	private final Path catPlugin;
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * Attaches a live harness process to an owned run.
	 *
	 * @param run the owned run
	 * @param process the live harness process
	 * @param scope services owned for the process lifetime
	 * @throws NullPointerException if {@code run}, {@code process}, or {@code scope} are null
	 */
	public HarnessRunSession(HarnessRun run, HarnessProcess process, AbstractHarnessRunnerScope scope)
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
	public HarnessRunSession(HarnessRun run, HarnessProcess process, AbstractHarnessRunnerScope scope,
		HarnessRunLock lock)
	{
		this(run, process, scope, lock, null);
	}

	/**
	 * Attaches a live harness process and its exclusive run lock to an owned run whose home holds the CAT plugin that
	 * the session's commands run.
	 *
	 * @param run       the owned run
	 * @param process   the live harness process
	 * @param scope     services owned for the process lifetime
	 * @param lock      the exclusive run lock
	 * @param catPlugin the directory of the CAT plugin installed in the run's home, whose launchers the session's
	 *                  commands run, or {@code null} if the run has none
	 * @throws NullPointerException if {@code run}, {@code process}, {@code scope}, or {@code lock} are null
	 */
	public HarnessRunSession(HarnessRun run, HarnessProcess process, AbstractHarnessRunnerScope scope,
		HarnessRunLock lock, Path catPlugin)
	{
		requireThat(run, "run").isNotNull();
		requireThat(process, "process").isNotNull();
		requireThat(scope, "scope").isNotNull();
		requireThat(lock, "lock").isNotNull();
		this.run = run;
		this.process = process;
		this.scope = scope;
		this.lock = lock;
		this.catPlugin = catPlugin;
	}

	/**
	 * Tells the harness process that the caller has started a turn that another thread is about to send, so that an
	 * {@link #interrupt()} requested before the turn reaches the process stops that turn.
	 *
	 * @see HarnessProcess#prepareTurn()
	 */
	public void prepareTurn()
	{
		process.prepareTurn();
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
		return sendTurn(request, listener, TurnMode.STANDARD);
	}

	/**
	 * Sends one turn for the given purpose and forwards harness events while the process remains live.
	 *
	 * @param request the framed turn request
	 * @param listener receives raw harness event lines
	 * @param mode what the turn is for
	 * @return the terminal result for the turn
	 * @throws IOException if the harness process is no longer alive, the turn fails, or event forwarding fails
	 */
	public HarnessProcessResult sendTurn(String request, HarnessEventListener listener, TurnMode mode)
		throws IOException
	{
		requireThat(request, "request").isNotBlank();
		requireThat(listener, "listener").isNotNull();
		requireThat(mode, "mode").isNotNull();
		synchronized (process)
		{
			if (!process.isAlive())
				throw new IOException("Harness process is no longer alive for run " + run.id());
			return process.sendTurn(request, listener, mode);
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
	 * Returns the directory of the CAT plugin installed in the run's home, whose launchers the session's commands run.
	 *
	 * @return the directory, or empty if the run has none
	 */
	public Optional<Path> catPlugin()
	{
		return Optional.ofNullable(catPlugin);
	}

	/**
	 * Returns services shared by this session's process and bridge.
	 *
	 * @return the live session scope
	 */
	public AbstractHarnessRunnerScope scope()
	{
		return scope;
	}

	/**
	 * Returns the path of the native transcript that the harness writes for its session, once the session's identifier
	 * is known.
	 *
	 * @return the path of the session's native transcript
	 */
	public Optional<Path> sessionTranscript()
	{
		return process.sessionTranscript();
	}

	/**
	 * Returns the identifier that the harness gave its session, once the harness has reported it.
	 *
	 * @return the identifier of the harness's session
	 */
	public Optional<String> harnessSessionId()
	{
		return process.harnessSessionId();
	}

	/**
	 * Indicates whether the owned harness process remains alive.
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
