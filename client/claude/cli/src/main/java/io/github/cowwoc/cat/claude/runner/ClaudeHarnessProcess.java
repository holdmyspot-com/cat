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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;

/**
 * Adapts Claude's stream-json CLI process to the multi-request {@link HarnessProcess} contract.
 */
public final class ClaudeHarnessProcess implements HarnessProcess
{
	/**
	 * How long a turn waits for Claude's next output line once the root session has replied and every background task
	 * has finished, before the turn ends without a follow-up reply.
	 */
	private static final Duration FOLLOW_UP_START_TIMEOUT = Duration.ofSeconds(30);
	/**
	 * How long to wait for the operating system to report Claude's exit once its output has ended.
	 */
	private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(10);
	private final HarnessRunnerScope scope;
	private final Process process;
	private final OutputStreamWriter input;
	/**
	 * Claude's standard-output lines in arrival order, followed by one {@link OutputLine} that marks the end of the
	 * stream.
	 */
	private final BlockingQueue<OutputLine> output = new LinkedBlockingQueue<>();
	private final HarnessStandardError standardError;
	private final Duration followUpStartTimeout;
	/**
	 * Guards {@link #turnActive} so an interrupt is signalled only while a turn is in progress.
	 */
	private final Object turnLock = new Object();
	/**
	 * Whether a turn has been sent and has not yet ended.
	 */
	private boolean turnActive;

	/**
	 * Wraps a started Claude process configured for stream-json input and output.
	 *
	 * @param scope services shared for the runner process lifetime
	 * @param process the started Claude process
	 * @throws NullPointerException if {@code scope} or {@code process} are null
	 */
	public ClaudeHarnessProcess(HarnessRunnerScope scope, Process process)
	{
		this(scope, process, FOLLOW_UP_START_TIMEOUT);
	}

	/**
	 * Wraps a started Claude process with an explicit wait for a follow-up reply.
	 *
	 * @param scope services shared for the runner process lifetime
	 * @param process the started Claude process
	 * @param followUpStartTimeout how long a turn waits for Claude's next output line once the root session has
	 *   replied and every background task has finished; the turn ends without a follow-up reply when none arrives
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code followUpStartTimeout} is not positive
	 */
	@SuppressWarnings("PMD.CloseResource")
	ClaudeHarnessProcess(HarnessRunnerScope scope, Process process, Duration followUpStartTimeout)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(process, "process").isNotNull();
		requireThat(followUpStartTimeout, "followUpStartTimeout").isGreaterThan(Duration.ZERO);
		this.scope = scope;
		this.process = process;
		this.followUpStartTimeout = followUpStartTimeout;
		input = new OutputStreamWriter(process.getOutputStream(), UTF_8);
		standardError = new HarnessStandardError(process.getErrorStream());
		// The output reader thread owns Claude's standard output and closes it when the stream ends.
		BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
		Thread.ofVirtual().start(() -> readOutput(lines));
	}

	/**
	 * Moves each line of Claude's standard output into {@link #output} so a turn can wait for the next line with a
	 * deadline. Ends with one end-of-stream entry when Claude closes its output or reading fails.
	 *
	 * @param lines Claude's standard output
	 */
	private void readOutput(BufferedReader lines)
	{
		assert lines != null;
		try (lines)
		{
			String line = lines.readLine();
			while (line != null)
			{
				output.add(new OutputLine(line, null));
				line = lines.readLine();
			}
			output.add(new OutputLine(null, null));
		}
		catch (IOException e)
		{
			output.add(new OutputLine(null, e));
		}
	}

	/**
	 * Sends one user turn and reads Claude's output until the turn ends.
	 * <p>
	 * A turn ends when the root session has replied and none of its background tasks is still running. When a
	 * background task is still running at the root reply, Claude reports its completion and then starts a follow-up
	 * reply on its own; the turn includes both and ends at the follow-up reply's result. If Claude prints nothing for
	 * 30 seconds after the last background task finishes, no follow-up reply is coming and the turn ends there.
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
			turnActive = true;
		}
		try
		{
			synchronized (input)
			{
				input.write(ClaudeStreamingProtocol.userTurn(request));
				input.flush();
			}
			StringBuilder events = new StringBuilder();
			TurnBoundary boundary = new TurnBoundary();
			OutputLine next = nextLine(boundary.awaitingFollowUp());
			while (next != null && next.line() != null)
			{
				String line = next.line();
				events.append(line).append('\n');
				listener.accept(line);
				if (boundary.ends(line))
					return completedTurn(events.toString());
				next = nextLine(boundary.awaitingFollowUp());
			}
			if (next == null)
			{
				// The root session replied and every background task finished, but Claude printed nothing more within
				// the wait, so no follow-up reply is coming and the turn ends with the replies it has.
				return completedTurn(events.toString());
			}
			// Keep the end-of-stream entry so a later turn also observes that Claude's output has ended.
			output.add(next);
			if (next.failure() != null)
				throw new IOException("Unable to read Claude output", next.failure());
			String error = standardError.await();
			String diagnostic = "Claude process ended before result";
			if (!error.isBlank())
				diagnostic += ": " + error;
			return new HarnessProcessResult(exitStatus(), events.toString(), diagnostic);
		}
		finally
		{
			synchronized (turnLock)
			{
				turnActive = false;
			}
		}
	}

	/**
	 * Returns the next line of Claude's standard output.
	 *
	 * @param deadline whether to give up after {@link #followUpStartTimeout} instead of waiting indefinitely
	 * @return the next output entry, or {@code null} if {@code deadline} is set and no line arrived in time
	 * @throws IOException if the thread is interrupted while waiting
	 */
	private OutputLine nextLine(boolean deadline) throws IOException
	{
		try
		{
			if (deadline)
				return output.poll(followUpStartTimeout.toNanos(), TimeUnit.NANOSECONDS);
			return output.take();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for Claude output", e);
		}
	}

	/**
	 * Creates the result of a turn that Claude finished.
	 *
	 * @param events the complete native event stream for the turn
	 * @return a completed result carrying the turn's final assistant response
	 */
	private HarnessProcessResult completedTurn(String events)
	{
		assert events != null;
		return new HarnessProcessResult(0, events, "", true, assistantResponse(events));
	}

	/**
	 * Extracts Claude's final assistant text without exposing Claude stream-JSON details to the common runner bridge.
	 *
	 * @param events the complete native event stream for one terminal turn
	 * @return the root session's last assistant or terminal result text, or an empty string when none was emitted
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
				// Messages printed by a child agent name the tool use that started it; they are not the root's reply.
				if (event.path("parent_tool_use_id").isString())
					continue;
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
	 * Returns Claude's exit status once its output has ended. Claude closes its output before the operating system
	 * reports that it exited, so the status is not available at once.
	 *
	 * @return the exit status
	 * @throws IOException if Claude does not exit in time or the wait is interrupted
	 */
	private int exitStatus() throws IOException
	{
		try
		{
			if (!process.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
				throw new IOException("Claude closed its output but did not exit within " + EXIT_TIMEOUT.toSeconds() +
					" seconds");
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for Claude to exit", e);
		}
		return process.exitValue();
	}

	/**
	 * Requests a POSIX interruption for an active Claude turn because stream-json has no documented interrupt message.
	 * Does nothing when no turn is in progress, since the turn the caller meant to stop has already ended.
	 *
	 * @throws IOException if a turn is in progress but the process is no longer alive or the operating system rejects
	 *   the signal
	 */
	@Override
	public void interrupt() throws IOException
	{
		synchronized (turnLock)
		{
			if (!turnActive)
				return;
			if (!process.isAlive())
				throw new IOException("Claude process is no longer alive");
			// Claude stream-json provides user messages but no corresponding interrupt request. Send SIGINT rather than
			// terminating the process so Claude can end the active turn without necessarily discarding the session.
			try (Process signal = new ProcessBuilder("kill", "-INT", Long.toString(process.pid())).start())
			{
				if (signal.waitFor() != 0)
					throw new IOException("Unable to interrupt the active Claude turn");
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while requesting Claude turn interruption", e);
			}
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

	/**
	 * Tracks one turn's progress through Claude's output to decide where the turn ends.
	 * <p>
	 * When a root reply finishes while a background task is still running, Claude prints that reply's result line
	 * first. When the last background task finishes, Claude prints a {@code background_tasks_changed} line that lists
	 * no task, the task's completion notice, and then starts a follow-up reply that begins with a
	 * {@code system/init} line and ends with its own result line.
	 */
	private final class TurnBoundary
	{
		/**
		 * Whether the root session has printed a result line since its latest reply started.
		 */
		private boolean replied;
		/**
		 * Whether the latest {@code background_tasks_changed} line listed a task.
		 */
		private boolean backgroundTasksRunning;

		/**
		 * Indicates whether the root session has replied and every background task has finished, but no follow-up
		 * reply has started yet.
		 *
		 * @return {@code true} if the turn is waiting for Claude to start a follow-up reply
		 */
		boolean awaitingFollowUp()
		{
			return replied && !backgroundTasksRunning;
		}

		/**
		 * Records one output line.
		 *
		 * @param line a line of Claude's output
		 * @return {@code true} if the line ends the turn
		 */
		boolean ends(String line)
		{
			assert line != null;
			JsonNode event;
			try
			{
				event = scope.json().readTree(line);
			}
			catch (JacksonException _)
			{
				// A diagnostic line that is not protocol JSON does not change the turn's progress.
				return false;
			}
			switch (event.path("type").asString(""))
			{
				case "result" ->
				{
					if (!backgroundTasksRunning)
						return true;
					replied = true;
				}
				case "system" ->
				{
					switch (event.path("subtype").asString(""))
					{
						case "background_tasks_changed" -> backgroundTasksRunning = !event.path("tasks").isEmpty();
						case "init" -> replied = false;
						default ->
						{
						}
					}
				}
				default ->
				{
				}
			}
			return false;
		}
	}

	/**
	 * One entry read from Claude's standard output.
	 *
	 * @param line the output line, or {@code null} when the stream has ended
	 * @param failure the failure that ended the stream, or {@code null} when the stream ended normally or
	 *   {@code line} is present
	 */
	private record OutputLine(String line, IOException failure)
	{
	}
}
