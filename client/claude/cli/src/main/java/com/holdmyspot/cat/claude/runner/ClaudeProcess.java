package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessEventListener;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessProcessTermination;
import com.holdmyspot.cat.common.runner.HarnessProcessStarter;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.HarnessStandardError;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;

/**
 * Adapts Claude's stream-json CLI process to the multi-request {@link HarnessProcess} contract.
 */
public final class ClaudeProcess implements HarnessProcess
{
	/**
	 * How long to wait for the operating system to report Claude's exit once its output has ended.
	 */
	private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(10);
	/**
	 * Reports that a turn's output began with a follow-up reply that Claude finished for an earlier turn.
	 */
	public static final String LATE_FOLLOW_UP = "Claude finished a follow-up reply to a background task's " +
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
	 * Guards {@link #turnActive} and {@link #interruptsSent} so an interrupt is requested only while a turn is in
	 * progress.
	 */
	private final Object turnLock = new Object();
	/**
	 * Indicates whether a turn has been sent and has not yet ended.
	 */
	private boolean turnActive;
	/**
	 * How many interrupt requests have been written, which makes each request's identifier unique.
	 */
	private long interruptsSent;
	/**
	 * Indicates whether Claude has printed a {@code session_state_changed} line since the process started. Turns run
	 * one at a time, but not always on the same thread.
	 */
	private volatile boolean sessionStatePrinted;
	/**
	 * Where Claude writes the root session's transcript, or {@code null} if the process keeps no transcripts.
	 */
	private final Path projectDirectory;
	/**
	 * Copies the session's transcripts when the process closes, or {@code null} if the process keeps no transcripts.
	 */
	private final HarnessSessionTranscripts transcripts;
	/**
	 * The root session's identifier, or an empty string until Claude reports it.
	 */
	private volatile String sessionId = "";

	/**
	 * Wraps a started Claude process configured for stream-json input and output.
	 *
	 * @param scope services shared for the runner process lifetime
	 * @param process the started Claude process
	 * @throws NullPointerException if {@code scope} or {@code process} are null
	 */
	public ClaudeProcess(HarnessRunnerScope scope, Process process)
	{
		this(scope, process, null, null);
	}

	/**
	 * Wraps a started Claude process that was asked to keep its session transcripts.
	 *
	 * @param scope services shared for the runner process lifetime
	 * @param process the started Claude process
	 * @param projectDirectory the directory in which Claude writes the root session's transcript, which is named after
	 *   the session's identifier, or {@code null} if the process keeps no transcripts
	 * @param transcripts copies the transcripts of the root session and its subagents when the process closes, or
	 *   {@code null} if the process keeps no transcripts
	 * @throws NullPointerException if {@code scope} or {@code process} are null
	 */
	public ClaudeProcess(HarnessRunnerScope scope, Process process, Path projectDirectory,
		HarnessSessionTranscripts transcripts)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(process, "process").isNotNull();
		this.scope = scope;
		this.process = process;
		this.projectDirectory = projectDirectory;
		this.transcripts = transcripts;
		input = new OutputStreamWriter(process.getOutputStream(), UTF_8);
		standardError = new HarnessStandardError(process.getErrorStream());
		// The output reader thread owns Claude's standard output and closes it when the stream ends.
		Thread.ofVirtual().start(() -> readOutput(process.getInputStream()));
	}

	/**
	 * Moves each line of Claude's standard output into {@link #output} so a turn can wait for the next line with a
	 * deadline. Ends with one end-of-stream entry when Claude closes its output or reading fails.
	 *
	 * @param stream Claude's standard output
	 */
	private void readOutput(InputStream stream)
	{
		assert stream != null;
		try (BufferedReader lines = new BufferedReader(new InputStreamReader(stream, UTF_8)))
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
				if (!boundary.sessionId().isEmpty())
					sessionId = boundary.sessionId();
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
	 * Extracts the root session's ordered non-blank assistant text blocks and distinct final response.
	 *
	 * @param events the complete harness event stream for the turn
	 * @param replyEvents the part of {@code events} that belongs to the turn's own reply
	 * @param diagnostics the turn's diagnostics, one per line, or an empty string if there are none
	 * @return a completed result carrying every non-blank assistant text block and the final assistant response
	 */
	private HarnessProcessResult completedTurn(String events, String replyEvents, String diagnostics)
	{
		assert events != null;
		assert replyEvents != null;
		assert diagnostics != null;
		List<String> assistantMessages = new ArrayList<>();
		String response = "";
		for (String line : replyEvents.lines().toList())
		{
			try
			{
				JsonNode event = scope.json().readTree(line);
				// A child agent's text belongs to the child session rather than the root's reply.
				if (event.path("parent_tool_use_id").isString())
					continue;
				if (event.path("result").isString() && !event.path("result").asString().isBlank())
				{
					response = event.path("result").asString();
					// A result may supply final text without an assistant event; avoid repeating an emitted final block.
					if (assistantMessages.isEmpty() || !assistantMessages.getLast().equals(response))
						assistantMessages.add(response);
				}
				for (JsonNode content : event.path("message").path("content"))
				{
					if (!content.path("type").asString().equals("text") || !content.path("text").isString())
						continue;
					String text = content.path("text").asString();
					if (text.isBlank())
						continue;
					response = text;
					if (event.path("type").asString().equals("assistant"))
						assistantMessages.add(text);
				}
			}
			catch (JacksonException _)
			{
				// Unrelated diagnostics remain in the retained harness stream.
			}
		}
		return new HarnessProcessResult(0, events, diagnostics, true, response, assistantMessages);
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
	 * Asks Claude to interrupt the turn in progress by writing a stream-json interrupt request to its standard input.
	 * Unlike terminating the process, the request lets Claude stop the turn's work, including background tasks, and
	 * report that it is idle while keeping the session for later turns. Does nothing when no turn is in progress, since
	 * the turn the caller meant to stop has already ended.
	 *
	 * @throws IOException if a turn is in progress but the process is no longer alive or the request cannot be written
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
			++interruptsSent;
			synchronized (input)
			{
				input.write(ClaudeStreamingProtocol.interrupt("cat-interrupt-" + interruptsSent));
				input.flush();
			}
		}
	}

	/**
	 * Indicates whether the Claude process remains alive.
	 *
	 * @return true if the process remains alive
	 */
	@Override
	public boolean isAlive()
	{
		return process.isAlive();
	}

	/**
	 * Returns the harness process identifier.
	 *
	 * @return the positive harness process identifier
	 */
	public long pid()
	{
		return process.pid();
	}

	/**
	 * Returns the harness process start marker.
	 *
	 * @return the harness process start marker
	 * @throws IOException if the operating system does not expose a start marker
	 */
	public Instant startedAt() throws IOException
	{
		return HarnessProcessStarter.startedAt(process);
	}

	/**
	 * Returns the root session's native transcript once Claude has reported the session's identifier.
	 *
	 * @return the transcript, or none if the process keeps no transcripts or Claude has not reported the identifier
	 */
	@Override
	public Optional<Path> sessionTranscript()
	{
		String id = sessionId;
		if (projectDirectory == null || id.isEmpty())
			return Optional.empty();
		return Optional.of(projectDirectory.resolve(id + ".jsonl"));
	}

	/**
	 * Closes input and waits for Claude to exit, and then copies the session's transcripts if the process keeps them.
	 *
	 * @throws IOException if closing input fails or the transcripts cannot be copied
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
			if (transcripts != null)
				transcripts.copy();
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
	public static final class TurnBoundary
	{
		/**
		 * Parses Claude's output lines.
		 */
		private final JsonMapper json;
		/**
		 * Indicates whether Claude has printed a {@code session_state_changed} line since the process started.
		 */
		private boolean sessionStatePrinted;
		/**
		 * Indicates whether the latest {@code background_tasks_changed} line listed a task.
		 */
		private boolean backgroundTasksRunning;
		/**
		 * Indicates whether the root session has printed a result line for a reply to a user message in this turn.
		 */
		private boolean answered;
		/**
		 * The version in the latest {@code system/init} line, or an empty string if there was none.
		 */
		private String claudeVersion = "";
		/**
		 * The session identifier in the latest {@code system/init} line, or an empty string if there was none.
		 */
		private String sessionId = "";

		/**
		 * Creates the state of a turn that has just been sent.
		 *
		 * @param json                parses Claude's output lines
		 * @param sessionStatePrinted whether Claude printed a {@code session_state_changed} line in an earlier turn
		 */
		public TurnBoundary(JsonMapper json, boolean sessionStatePrinted)
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
		 * Returns the session identifier from the latest {@code system/init} line.
		 *
		 * @return the identifier, or an empty string if no {@code system/init} line reported one
		 */
		String sessionId()
		{
			return sessionId;
		}

		/**
		 * Records one output line.
		 *
		 * @param line a line of Claude's output
		 * @return what the line means for the turn
		 */
		public LineEffect accept(String line)
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
					sessionId = event.path("session_id").asString("");
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
	public enum LineEffect
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
