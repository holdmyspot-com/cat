package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Represents a started harness CLI process that accepts multiple requests before it is closed.
 */
public interface HarnessProcess extends AutoCloseable
{
	/**
	 * Tells the process that the caller has started a turn that it is about to send with
	 * {@link #sendTurn(String, HarnessEventListener)}, typically from another thread. An {@link #interrupt()} requested
	 * from then until the turn is sent stops that turn once it is sent, instead of being ignored as an interrupt
	 * between turns. Sending the turn ends this state.
	 */
	void prepareTurn();

	/**
	 * Sends one turn without restarting the harness.
	 *
	 * @param request the framed turn request
	 *
	 * @return the terminal result for the turn
	 *
	 * @throws IOException if the process cannot accept or complete the turn, or the calling thread is interrupted
	 *   while the turn is in progress; the thread's interrupt status is then set
	 */
	HarnessProcessResult sendTurn(String request) throws IOException;

	/**
	 * Sends one turn and forwards each harness event before returning its terminal result.
	 *
	 * @param request the framed turn request
	 *
	 * @param listener receives raw harness event lines
	 *
	 * @return the terminal result for the turn
	 *
	 * @throws IOException if the process cannot accept or complete the turn, event forwarding fails, or the calling
	 *   thread is interrupted while the turn is in progress, which lets a caller stop a turn that does not end; the
	 *   thread's interrupt status is then set
	 */
	default HarnessProcessResult sendTurn(String request, HarnessEventListener listener) throws IOException
	{
		requireThat(request, "request").isNotBlank();
		requireThat(listener, "listener").isNotNull();
		HarnessProcessResult result = sendTurn(request);
		for (String event : result.standardOutput().lines().toList())
			listener.accept(event);
		return result;
	}

	/**
	 * Sends additional user direction to the active turn without starting another turn.
	 *
	 * @param request the non-blank additional user direction
	 *
	 * @throws IOException if the harness cannot accept the direction
	 */
	default void steer(String request) throws IOException
	{
		requireThat(request, "request").isNotBlank();
		throw new IOException("This harness does not support mid-turn steering");
	}

	/**
	 * Stops the active turn while retaining the harness session when the harness supports it. An interrupt requested
	 * after {@link #prepareTurn()} and before the prepared turn is sent stops that turn once it is sent. Does nothing
	 * between turns.
	 *
	 * @throws IOException if the harness cannot interrupt the active turn
	 */
	default void interrupt() throws IOException
	{
		throw new IOException("This harness does not support mid-turn interruption");
	}

	/**
	 * Returns the path of the native transcript that the harness writes for its session, once the session's identifier
	 * is known. A harness that writes no transcript, or one whose session is not yet known, returns no path.
	 * <p>
	 * A process that was asked to keep session transcripts copies every transcript of its session, subagents' included,
	 * into the run's {@link HarnessRun#sessionTranscripts() session transcripts directory} when it closes.
	 *
	 * @return the path of the session's native transcript
	 */
	default Optional<Path> sessionTranscript()
	{
		return Optional.empty();
	}

	/**
	 * Indicates whether the harness remains alive.
	 *
	 * @return true if the process is alive
	 */
	boolean isAlive();

	/**
	 * Closes harness input and ends the harness process tree before reporting shutdown complete. Implementations must not
	 * leave a descendant able to write to a stream inherited from the harness after this method returns.
	 *
	 * @throws IOException if shutdown fails
	 */
	@Override
	void close() throws IOException;
}
