package io.github.cowwoc.cat.codex.optimizer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.List;

/**
 * Runs the runner's JSONL bridge once.
 * <p>
 * The production bridge is {@code CodexRunnerCli.serve}. A test substitutes a bridge that plays a harness.
 */
@FunctionalInterface
interface Bridge
{
	/**
	 * Serves one session until the input ends or the session completes.
	 *
	 * @param arguments the runner's command-line options
	 * @param input the requests, one JSON object per line
	 * @param output where the responses are written, one JSON object per line
	 * @throws IOException if the runner or its harness fails
	 */
	void serve(List<String> arguments, Reader input, Writer output) throws IOException;
}
