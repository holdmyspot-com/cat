package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.runner.HarnessProcess;
import io.github.cowwoc.cat.common.runner.HarnessEventListener;
import io.github.cowwoc.cat.common.runner.HarnessProcessResult;
import io.github.cowwoc.cat.common.runner.HarnessProcessTermination;
import io.github.cowwoc.cat.common.runner.HarnessProcessStarter;
import io.github.cowwoc.cat.common.runner.HarnessStandardError;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.time.Instant;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;

/**
 * Adapts Claude's stream-json CLI process to the multi-request {@link HarnessProcess} contract.
 */
public final class ClaudeHarnessProcess implements HarnessProcess
{
	private final HarnessRunnerScope scope;
	private final Process process;
	private final OutputStreamWriter input;
	private final BufferedReader output;
	private final HarnessStandardError standardError;
	private final Object turnLock = new Object();
	private boolean reserved;
	private boolean dispatched;
	private boolean stopRequested;
	private boolean signalDelivered;
	private boolean terminal;

	/**
	 * Wraps a started Claude process configured for stream-json input and output.
	 *
	 * @param scope services shared for the runner process lifetime
	 * @param process the started Claude process
	 * @throws NullPointerException if {@code scope} or {@code process} are null
	 */
	public ClaudeHarnessProcess(HarnessRunnerScope scope, Process process)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(process, "process").isNotNull();
		this.scope = scope;
		this.process = process;
		input = new OutputStreamWriter(process.getOutputStream(), UTF_8);
		output = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
		standardError = new HarnessStandardError(process.getErrorStream());
	}

	/**
	 * Sends one user turn and reads through Claude's terminal result event.
	 *
	 * @param request the non-blank user prompt
	 * @return the captured event stream for the turn
	 * @throws IOException if input or output fails
	 */
	@Override
	public HarnessProcessResult sendTurn(String request) throws IOException
	{
		return sendTurn(request, event ->
		{
		});
	}

	@Override
	public HarnessProcessResult sendTurn(String request, HarnessEventListener listener) throws IOException
	{
		requireThat(request, "request").isNotBlank();
		requireThat(listener, "listener").isNotNull();
		synchronized (turnLock)
		{
			if (!reserved)
				resetTurn();
		}
		synchronized (input)
		{
			input.write(ClaudeStreamingProtocol.userTurn(request));
			input.flush();
		}
		synchronized (turnLock)
		{
			dispatched = true;
			if (stopRequested)
				signalTurn();
		}
		StringBuilder events = new StringBuilder();
		String line = output.readLine();
		while (line != null)
		{
			events.append(line).append('\n');
			JsonNode result = terminalResult(line);
			if (result != null)
			{
				ClaudeResultDisposition disposition = ClaudeResultDisposition.read(result);
				boolean interrupted;
				synchronized (turnLock)
				{
					terminal = true;
					interrupted = signalDelivered && disposition == ClaudeResultDisposition.ABORTED_STREAMING;
				}
				listener.accept(line);
				String response = assistantResponse(events.toString());
				if (interrupted)
					return new HarnessProcessResult(130, events.toString(), "",
						HarnessProcessResult.Outcome.INTERRUPTED, response);
				if (disposition == ClaudeResultDisposition.COMPLETED)
					return new HarnessProcessResult(0, events.toString(), "",
						HarnessProcessResult.Outcome.COMPLETED, response);
				return new HarnessProcessResult(1, events.toString(),
					"Claude terminal result: " + result.path("terminal_reason").asString(),
					HarnessProcessResult.Outcome.FAILED, response);
			}
			listener.accept(line);
			line = output.readLine();
		}
		int exitCode;
		try
		{
			exitCode = process.waitFor();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for Claude process exit", e);
		}
		String error = standardError.await();
		String diagnostic = "Claude process ended before result";
		if (!error.isBlank())
			diagnostic += ": " + error;
		synchronized (turnLock)
		{
			terminal = true;
			if (signalDelivered && exitCode == 130)
				return new HarnessProcessResult(exitCode, events.toString(), "",
					HarnessProcessResult.Outcome.INTERRUPTED, assistantResponse(events.toString()));
		}
		return new HarnessProcessResult(exitCode, events.toString(), diagnostic,
			HarnessProcessResult.Outcome.FAILED, assistantResponse(events.toString()));
	}

	@Override
	public void reserveTurn()
	{
		synchronized (turnLock)
		{
			resetTurn();
		}
	}

	/**
	 * Clears the preceding turn's terminal and stop state while the turn lock is held.
	 */
	private void resetTurn()
	{
		reserved = true;
		dispatched = false;
		stopRequested = false;
		signalDelivered = false;
		terminal = false;
	}

	/**
	 * Reads a native result event from one protocol line.
	 *
	 * @param line one line from Claude's standard output
	 * @return the result event, or null when the line does not contain one
	 */
	private JsonNode terminalResult(String line)
	{
		try
		{
			JsonNode event = scope.json().readTree(line);
			if (event.path("type").asString().equals("result"))
				return event;
		}
		catch (JacksonException _)
		{
			// Retain unrelated diagnostic lines as native events.
		}
		return null;
	}

	/**
	 * Extracts Claude's final assistant text without exposing Claude stream-JSON details to the common runner bridge.
	 *
	 * @param events the complete native event stream for one terminal turn
	 * @return the last available assistant or terminal result text, or an empty string when none was emitted
	 */
	private String assistantResponse(String events)
	{
		assert events != null;
		String response = "";
		for (String line : events.lines().toList())
		{
			try
			{
				JsonNode event = scope.json().readTree(line);
				if (event.path("result").isString() && !event.path("result").asString().isBlank())
					response = event.path("result").asString();
				for (JsonNode content : event.path("message").path("content"))
					if (content.path("type").asString().equals("text") && content.path("text").isString() &&
						!content.path("text").asString().isBlank())
						response = content.path("text").asString();
			}
			catch (JacksonException _)
			{
				// The complete native stream remains retained even when an unrelated diagnostic line is not protocol JSON.
			}
		}
		return response;
	}

	/**
	 * Sends another user message through Claude's stream-JSON input while a turn is active.
	 *
	 * @param request the non-blank user direction
	 * @throws IOException if the CLI input stream cannot accept the direction
	 */
	@Override
	public void steer(String request) throws IOException
	{
		requireThat(request, "request").isNotBlank();
		synchronized (input)
		{
			input.write(ClaudeStreamingProtocol.userTurn(request));
			input.flush();
		}
	}

	/**
	 * Requests a POSIX interruption for an active Claude turn because stream-json has no documented interrupt message.
	 *
	 * @throws IOException if the process is no longer alive or the operating system rejects the signal
	 */
	@Override
	public void interrupt() throws IOException
	{
		synchronized (turnLock)
		{
			if (terminal || signalDelivered)
				return;
			if (!reserved)
				throw new IOException("Claude has no reserved turn to interrupt");
			stopRequested = true;
			if (dispatched)
				signalTurn();
		}
	}

	/**
	 * Signals the active native turn while the turn lock is held.
	 *
	 * @throws IOException if the operating system rejects the signal
	 */
	private void signalTurn() throws IOException
	{
		if (!process.isAlive())
			return;
		// Claude stream-json provides user messages but no corresponding interrupt request. Send SIGINT rather than
		// terminating the process so Claude can end the active turn without necessarily discarding the session.
		try (Process signal = new ProcessBuilder("kill", "-INT", Long.toString(process.pid())).start())
		{
			if (signal.waitFor() != 0)
				throw new IOException("Unable to interrupt the active Claude turn");
			signalDelivered = true;
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while requesting Claude turn interruption", e);
		}
	}

	/**
	 * Returns whether the Claude process remains alive.
	 *
	 * @return true if the process remains alive
	 */
	@Override
	public boolean isAlive()
	{
		return process.isAlive();
	}

	/**
	 * Returns the native process identifier.
	 *
	 * @return the positive native process identifier
	 */
	public long pid()
	{
		return process.pid();
	}

	/**
	 * Returns the native process start marker.
	 *
	 * @return the native process start marker
	 * @throws IOException if the operating system does not expose a start marker
	 */
	public Instant startedAt() throws IOException
	{
		return HarnessProcessStarter.startedAt(process);
	}

	/**
	 * Closes input and waits for Claude to exit.
	 *
	 * @throws IOException if closing input fails
	 */
	@Override
	public void close() throws IOException
	{
		synchronized (input)
		{
			input.close();
			// A child can keep standard error open after Claude exits, leaving the evaluator waiting for more output.
			// Stop the harness process tree first, then close the stream once no harness child can write to it.
			HarnessProcessTermination.terminate(process, "Claude");
			standardError.close();
			standardError.await();
		}
	}
}
