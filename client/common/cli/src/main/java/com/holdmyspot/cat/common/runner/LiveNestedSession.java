package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one started harness process and its isolated execution directory for multiple requests.
 */
public final class LiveNestedSession implements AutoCloseable
{
	private final NestedSession nestedSession;
	private final HarnessProcess process;
	private final AbstractHarnessRunnerScope scope;
	private final NestedSessionLock lock;
	/**
	 * The directory of the CAT plugin installed in the nested session's home, whose launchers the session's commands run,
	 * or {@code null} if the nested session has none.
	 */
	private final Path catPlugin;
	/**
	 * The policy that the nested session's sandbox applies to its commands, or {@code null} if the runner states none.
	 */
	private final ParentPolicy ownerPolicy;
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * Attaches a live harness process to an owned nested session.
	 *
	 * @param nestedSession the owned nested session
	 * @param process the live harness process
	 * @param scope services owned for the process lifetime
	 * @throws NullPointerException if {@code nestedSession}, {@code process}, or {@code scope} are null
	 */
	public LiveNestedSession(NestedSession nestedSession, HarnessProcess process, AbstractHarnessRunnerScope scope)
	{
		this(nestedSession, process, scope, NestedSessionLock.unlocked());
	}

	/**
	 * Attaches a live harness process and its exclusive nested-session lock to an owned nested session.
	 *
	 * @param nestedSession the owned nested session
	 * @param process the live harness process
	 * @param scope services owned for the process lifetime
	 * @param lock the exclusive nested-session lock
	 * @throws NullPointerException if {@code nestedSession}, {@code process}, {@code scope}, or {@code lock} are null
	 */
	public LiveNestedSession(NestedSession nestedSession, HarnessProcess process, AbstractHarnessRunnerScope scope,
		NestedSessionLock lock)
	{
		this(nestedSession, process, scope, lock, null);
	}

	/**
	 * Attaches a live harness process and its exclusive nested-session lock to an owned nested session whose home holds
	 * the CAT plugin that the session's commands run.
	 *
	 * @param nestedSession the owned nested session
	 * @param process       the live harness process
	 * @param scope         services owned for the process lifetime
	 * @param lock          the exclusive nested-session lock
	 * @param catPlugin     the directory of the CAT plugin installed in the nested session's home, whose launchers the
	 *                      session's commands run, or {@code null} if the nested session has none
	 * @throws NullPointerException if {@code nestedSession}, {@code process}, {@code scope}, or {@code lock} are null
	 */
	public LiveNestedSession(NestedSession nestedSession, HarnessProcess process, AbstractHarnessRunnerScope scope,
		NestedSessionLock lock, Path catPlugin)
	{
		this(nestedSession, process, scope, lock, catPlugin, null);
	}

	/**
	 * Attaches a live harness process and its exclusive nested-session lock to an owned nested session whose home holds
	 * the CAT plugin that the session's commands run, and whose runner states the policy that the session's sandbox
	 * applies to those commands.
	 *
	 * @param nestedSession the owned nested session
	 * @param process       the live harness process
	 * @param scope         services owned for the process lifetime
	 * @param lock          the exclusive nested-session lock
	 * @param catPlugin     the directory of the CAT plugin installed in the nested session's home, whose launchers the
	 *                      session's commands run, or {@code null} if the nested session has none
	 * @param ownerPolicy   the policy that the nested session's sandbox applies to its commands, which the session's
	 *                      commands cannot read from inside the sandbox, or {@code null} if the runner states none
	 * @throws NullPointerException if {@code nestedSession}, {@code process}, {@code scope}, or {@code lock} are null
	 */
	public LiveNestedSession(NestedSession nestedSession, HarnessProcess process, AbstractHarnessRunnerScope scope,
		NestedSessionLock lock, Path catPlugin, ParentPolicy ownerPolicy)
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		requireThat(process, "process").isNotNull();
		requireThat(scope, "scope").isNotNull();
		requireThat(lock, "lock").isNotNull();
		this.nestedSession = nestedSession;
		this.process = process;
		this.scope = scope;
		this.lock = lock;
		this.catPlugin = catPlugin;
		this.ownerPolicy = ownerPolicy;
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
				throw new IOException("Harness process is no longer alive for nested session " + nestedSession.id());
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
			throw new IOException("Harness process is no longer alive for nested session " + nestedSession.id());
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
			throw new IOException("Harness process is no longer alive for nested session " + nestedSession.id());
		process.interrupt();
	}

	/**
	 * Returns the owned nested session.
	 *
	 * @return the owned nested session
	 */
	public NestedSession nestedSession()
	{
		return nestedSession;
	}

	/**
	 * Returns the directory of the CAT plugin installed in the nested session's home, whose launchers the session's
	 * commands run.
	 *
	 * @return the directory, or empty if the nested session has none
	 */
	public Optional<Path> catPlugin()
	{
		return Optional.ofNullable(catPlugin);
	}

	/**
	 * Returns the policy that the nested session's sandbox applies to its commands.
	 *
	 * @return the policy, or empty if the runner states none
	 */
	public Optional<ParentPolicy> ownerPolicy()
	{
		return Optional.ofNullable(ownerPolicy);
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
