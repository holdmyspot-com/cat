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
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;

/**
 * Adapts Claude's stream-json CLI process to the multi-request {@link HarnessProcess} contract.
 */
public final class ClaudeHarnessProcess implements HarnessProcess
{
	/**
	 * How long to wait for the operating system to report Claude's exit once its output has ended.
	 */
	private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(10);
	/**
	 * Reports that a turn's output began with a follow-up reply that Claude finished for an earlier turn.
	 */
	static final String LATE_FOLLOW_UP = "Claude finished a follow-up reply to a background task's " +
		"completion after the previous turn had ended. Its lines are included in this turn's events, but its text is " +
		"not this turn's reply.";
	private final HarnessRunnerScope scope;
	private final Process process;
	private final OutputStreamWriter input;
	/**
	 * Claude's standard-output lines in arrival order, followed by one {@link OutputLine} that marks the end of the
	 * stream.
	 */
	private final BlockingQueue<OutputLine> output = new LinkedBlockingQueue<>();
	private final HarnessStandardError standardError;
	/**
	 * Guards {@link #turnActive} so an interrupt is signalled only while a turn is in progress.
	 */
	private final Object turnLock = new Object();
	/**
	 * Whether a turn has been sent and has not yet ended.
	 */
	private boolean turnActive;
	/**
	 * Whether Claude has printed a {@code session_state_changed} line since the process started. Turns run one at a
	 * time, but not always on the same thread.
	 */
	private volatile boolean sessionStatePrinted;

	/**
	 * Wraps a started Claude process configured for stream-json input and output.
	 *
	 * @param scope services shared for the runner process lifetime
	 * @param process the started Claude process
	 * @throws NullPointerException if {@code scope} or {@code process} are null
	 */
	@SuppressWarnings("PMD.CloseResource")
	public ClaudeHarnessProcess(HarnessRunnerScope scope, Process process)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(process, "process").isNotNull();
		this.scope = scope;
		this.process = process;
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
	 * A turn ends when Claude reports that its session is idle after the turn's reply and no background task is listed.
	 * A child that is still running at the root's result, or that finishes during the root's last model call, makes
	 * Claude report its completion and then start a follow-up reply on its own; the turn includes both and ends once
	 * Claude is idle after the follow-up reply. The runner sets no time limit of its own on a turn: otherwise a turn
	 * ends only when it is interrupted or Claude exits.
	 * <p>
	 * The turn fails at once, without a completed result, if Claude starts a reply before it has ever reported its
	 * session state: such a Claude does not honour {@code CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS}, so the runner cannot
	 * tell where its turns end.
	 * <p>
	 * When the turn's output begins with a follow-up reply that Claude finished for an earlier turn, the turn relays
	 * that reply's lines but does not end there or take its text as the turn's reply, and its result reports the late
	 * follow-up in {@link HarnessProcessResult#standardError()}.
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
			// Where the turn's own reply begins in events: after any follow-up reply that belongs to an earlier turn
			int replyStart = 0;
			StringJoiner diagnostics = new StringJoiner("\n");
			TurnBoundary boundary = new TurnBoundary(scope.json(), sessionStatePrinted);
			OutputLine next = nextLine();
			while (next.line() != null)
			{
				String line = next.line();
				events.append(line).append('\n');
				listener.accept(line);
				LineEffect effect = boundary.accept(line);
				sessionStatePrinted = boundary.sessionStatePrinted();
				switch (effect)
				{
					case TURN_ENDS ->
					{
						return completedTurn(events.toString(), events.substring(replyStart), diagnostics.toString());
					}
					case EARLIER_FOLLOW_UP_ENDS ->
					{
						replyStart = events.length();
						diagnostics.add(LATE_FOLLOW_UP);
					}
					case SESSION_STATE_MISSING ->
					{
						// Claude keeps working on the turn; the caller ends the session once it sees the failure.
						return new HarnessProcessResult(1, events.toString(), sessionStateMissing(boundary.claudeVersion()));
					}
					case NONE ->
					{
					}
				}
				next = nextLine();
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
	 * Returns the next entry of Claude's standard output, waiting for it as long as it takes.
	 *
	 * @return the next output entry
	 * @throws IOException if the thread is interrupted while waiting
	 */
	private OutputLine nextLine() throws IOException
	{
		try
		{
			return output.take();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for Claude output", e);
		}
	}

	/**
	 * Explains why a turn failed because Claude did not report its session state.
	 *
	 * @param claudeVersion the version that Claude reported, or an empty string if it reported none
	 * @return the diagnostic
	 */
	static String sessionStateMissing(String claudeVersion)
	{
		assert claudeVersion != null;
		String claude;
		if (claudeVersion.isBlank())
			claude = "Claude Code (version not reported)";
		else
			claude = "Claude Code " + claudeVersion;
		return claude + " started a reply without reporting its session state, so the runner cannot tell where " +
			"its turns end. Nested Claude sessions need a Claude Code CLI that honours " +
			"CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS; retrying with this CLI fails the same way.";
	}

	/**
	 * Creates the result of a turn that Claude finished.
	 *
	 * @param events the complete native event stream for the turn
	 * @param replyEvents the part of {@code events} that belongs to the turn's own reply
	 * @param diagnostics the turn's diagnostics, one per line, or an empty string if there are none
	 * @return a completed result carrying the turn's final assistant response
	 */
	private HarnessProcessResult completedTurn(String events, String replyEvents, String diagnostics)
	{
		assert events != null;
		assert replyEvents != null;
		assert diagnostics != null;
		return new HarnessProcessResult(0, events, diagnostics, true, assistantResponse(replyEvents));
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
	 * Claude prints a {@code session_state_changed} line, because the runner sets
	 * {@code CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1}, whenever its session starts or stops working: {@code running}
	 * before the first {@code system/init} line of a turn, and {@code idle} just after the root's final {@code result}
	 * once Claude has no queued work left for the root. A background task that finishes while the root is still
	 * replying queues a follow-up reply, so Claude is not idle until that follow-up's {@code result}; while a child
	 * agent that the root sees is running, Claude is not idle either.
	 * <p>
	 * {@code idle} does not mean that all background work has finished: a child agent's own background command can
	 * still be listed, and when it finishes the child restarts and the root may start a follow-up reply. So the turn
	 * ends at an {@code idle} state only if the turn has replied to a user message and the latest
	 * {@code background_tasks_changed} line listed no task; otherwise the turn waits for the next {@code idle} state. A
	 * result or a change to the task list alone never ends a turn, even if the list empties after the last {@code idle}
	 * state: the turn then waits for the next {@code idle} state.
	 * <p>
	 * A {@code result} whose {@code origin.kind} is {@code task-notification} and that arrives before the turn's first
	 * reply to a user message ends a follow-up reply that belongs to an earlier turn; it does not end this turn, and
	 * neither does the {@code idle} state that follows it.
	 * <p>
	 * A {@code system/init} line in a process that has never printed a session state means that Claude does not
	 * honour the setting, and the turn fails.
	 */
	static final class TurnBoundary
	{
		/**
		 * Parses Claude's output lines.
		 */
		private final JsonMapper json;
		/**
		 * Whether Claude has printed a {@code session_state_changed} line since the process started.
		 */
		private boolean sessionStatePrinted;
		/**
		 * Whether the latest {@code background_tasks_changed} line listed a task.
		 */
		private boolean backgroundTasksRunning;
		/**
		 * Whether the root session has printed a result line for a reply to a user message in this turn.
		 */
		private boolean answered;
		/**
		 * The version in the latest {@code system/init} line, or an empty string if there was none.
		 */
		private String claudeVersion = "";

		/**
		 * Creates the state of a turn that has just been sent.
		 *
		 * @param json                parses Claude's output lines
		 * @param sessionStatePrinted whether Claude printed a {@code session_state_changed} line in an earlier turn
		 */
		TurnBoundary(JsonMapper json, boolean sessionStatePrinted)
		{
			assert json != null;
			this.json = json;
			this.sessionStatePrinted = sessionStatePrinted;
		}

		/**
		 * Indicates whether Claude has printed a {@code session_state_changed} line since the process started.
		 *
		 * @return {@code true} if Claude reports its session state
		 */
		boolean sessionStatePrinted()
		{
			return sessionStatePrinted;
		}

		/**
		 * Returns the Claude version from the latest {@code system/init} line.
		 *
		 * @return the version, or an empty string if no {@code system/init} line reported one
		 */
		String claudeVersion()
		{
			return claudeVersion;
		}

		/**
		 * Records one output line.
		 *
		 * @param line a line of Claude's output
		 * @return what the line means for the turn
		 */
		LineEffect accept(String line)
		{
			assert line != null;
			JsonNode event;
			try
			{
				event = json.readTree(line);
			}
			catch (JacksonException _)
			{
				// A diagnostic line that is not protocol JSON does not change the turn's progress.
				return LineEffect.NONE;
			}
			return switch (event.path("type").asString(""))
			{
				case "result" -> acceptResult(event);
				case "system" -> acceptSystem(event);
				default -> LineEffect.NONE;
			};
		}

		/**
		 * Records a root result line.
		 *
		 * @param event the parsed line
		 * @return what the line means for the turn
		 */
		private LineEffect acceptResult(JsonNode event)
		{
			assert event != null;
			if (!answered && event.path("origin").path("kind").asString("").equals("task-notification"))
				return LineEffect.EARLIER_FOLLOW_UP_ENDS;
			answered = true;
			return LineEffect.NONE;
		}

		/**
		 * Records a system line.
		 *
		 * @param event the parsed line
		 * @return what the line means for the turn
		 */
		private LineEffect acceptSystem(JsonNode event)
		{
			assert event != null;
			switch (event.path("subtype").asString(""))
			{
				case "background_tasks_changed" -> backgroundTasksRunning = !event.path("tasks").isEmpty();
				case "init" ->
				{
					claudeVersion = event.path("claude_code_version").asString("");
					if (!sessionStatePrinted)
						return LineEffect.SESSION_STATE_MISSING;
				}
				case "session_state_changed" ->
				{
					sessionStatePrinted = true;
					if (event.path("state").asString("").equals("idle") && answered && !backgroundTasksRunning)
						return LineEffect.TURN_ENDS;
				}
				default ->
				{
				}
			}
			return LineEffect.NONE;
		}
	}

	/**
	 * What one output line means for the turn in progress.
	 */
	enum LineEffect
	{
		/**
		 * The turn continues.
		 */
		NONE,
		/**
		 * The line ends the turn.
		 */
		TURN_ENDS,
		/**
		 * The line ends a follow-up reply that Claude started for an earlier turn; the turn continues.
		 */
		EARLIER_FOLLOW_UP_ENDS,
		/**
		 * Claude started a reply without having reported its session state, so the turn fails.
		 */
		SESSION_STATE_MISSING
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
