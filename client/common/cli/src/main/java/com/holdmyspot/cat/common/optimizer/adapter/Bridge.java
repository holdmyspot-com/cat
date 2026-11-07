package com.holdmyspot.cat.common.optimizer.adapter;

import com.holdmyspot.cat.common.runner.launcher.client.LaunchRefusedException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.List;

/**
 * Runs a harness runner's JSONL bridge once.
 * <p>
 * The production bridge sends the session along the launch route that the environment of the optimizer's command
 * decides: to a session launcher, or to the harness runner's {@code serve} operation in this process; on a route that
 * refuses sessions, it refuses each one. A test substitutes a bridge that plays a harness.
 */
@FunctionalInterface
public interface Bridge
{
	/**
	 * Serves one session until the input ends or the session completes.
	 *
	 * @param arguments the runner's command-line options
	 * @param input the requests, one JSON object per line
	 * @param output where the responses are written, one JSON object per line
	 * @throws LaunchRefusedException if no session started because it was refused: by the session launcher, by the
	 *   launch route, which refuses sessions inside a run whose runner lets its session request none and when only
	 *   the other harness runs the command, or because the sandbox of a session served in this process would not
	 *   confine it as its bound requires; the same launch is refused again until the user acts on the message
	 * @throws IOException if the runner or its harness fails
	 */
	void serve(List<String> arguments, Reader input, Writer output) throws IOException;
}
