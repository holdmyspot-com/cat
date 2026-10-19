package com.holdmyspot.cat.common.runner;

import java.io.IOException;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Represents a started harness CLI process that accepts multiple requests before it is closed.
 */
public interface HarnessProcess extends AutoCloseable
{
	/**
	 * Sends one turn without restarting the harness.
	 *
	 * @param request the framed turn request
	 *
	 * @return the terminal result for the turn
	 *
	 * @throws IOException if the process cannot accept or complete the turn
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
	 * @throws IOException if the process cannot accept or complete the turn, or event forwarding fails
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
	 * Stops the active turn while retaining the harness session when the harness supports it.
	 *
	 * @throws IOException if the harness cannot interrupt the active turn
	 */
	default void interrupt() throws IOException
	{
		throw new IOException("This harness does not support mid-turn interruption");
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
