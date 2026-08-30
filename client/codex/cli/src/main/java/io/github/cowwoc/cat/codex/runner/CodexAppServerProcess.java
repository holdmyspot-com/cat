package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.runner.HarnessProcess;
import io.github.cowwoc.cat.common.runner.HarnessEventListener;
import io.github.cowwoc.cat.common.runner.HarnessProcessResult;
import io.github.cowwoc.cat.common.runner.HarnessProcessTermination;
import io.github.cowwoc.cat.common.runner.HarnessProcessStarter;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.HarnessStandardError;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Adapts Codex's app-server CLI process to the multi-request {@link HarnessProcess} contract.
 */
public final class CodexAppServerProcess implements HarnessProcess
{
	private final Process process;
	private final HarnessRunnerScope scope;
	private final OutputStreamWriter input;
	private final BufferedReader output;
	private final HarnessStandardError standardError;
	private final boolean verifyCatHookDelivery;
	private final Object turnState = new Object();
	private final StringBuilder startupEvents = new StringBuilder();
	private long nextRequestId = 4;
	private String threadId;
	private String activeTurnId;
	private boolean awaitingTurnStart;
	private String effort = "";

	/**
	 * Wraps a started Codex app-server process.
	 *
	 * @param process the started Codex app-server process
	 * @param scope services owned for this app-server lifetime
	 * @param verifyCatHookDelivery whether CAT hook configuration and delegated-hook delivery must be verified
	 * @throws NullPointerException if {@code process} or {@code scope} are null
	 */
	public CodexAppServerProcess(Process process, HarnessRunnerScope scope, boolean verifyCatHookDelivery)
	{
		requireThat(process, "process").isNotNull();
		requireThat(scope, "scope").isNotNull();
		this.process = process;
		this.scope = scope;
		this.verifyCatHookDelivery = verifyCatHookDelivery;
		input = new OutputStreamWriter(process.getOutputStream(), UTF_8);
		output = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
		standardError = new HarnessStandardError(process.getErrorStream());
	}

	/**
	 * Initializes the app-server and creates its one workspace-write thread.
	 *
	 * @param workingDirectory the copied-project directory
	 * @param model the optional model name
	 * @throws IOException if startup fails or no thread ID is returned
	 */
	public void initialize(Path workingDirectory, String model) throws IOException
	{
		initialize(workingDirectory, model, "", "");
	}

	/**
	 * Initializes the app-server and creates its one workspace-write thread with an optional direct agent contract.
	 *
	 * @param workingDirectory the copied-project directory
	 * @param model the optional model name
	 * @param developerInstructions the optional direct developer contract
	 * @param effort the optional reasoning effort
	 * @throws IOException if startup fails or no thread ID is returned
	 */
	public void initialize(Path workingDirectory, String model, String developerInstructions, String effort)
		throws IOException
	{
		initialize(workingDirectory, model, developerInstructions, effort, "");
	}

	/**
	 * Initializes the app server with optional profile-based filesystem authority.
	 *
	 * @param workingDirectory the copied project directory
	 * @param model the optional model name
	 * @param developerInstructions the optional direct agent contract
	 * @param effort the optional reasoning effort
	 * @param permissionProfile the optional named Codex permission profile
	 * @throws IOException if startup fails or no thread ID is returned
	 */
	void initialize(Path workingDirectory, String model, String developerInstructions, String effort,
		String permissionProfile) throws IOException
	{
		initialize(workingDirectory, model, developerInstructions, effort, permissionProfile,
			List.of(workingDirectory));
	}

	/**
	 * Initializes the app server with a permission profile and every workspace root that profile needs to read.
	 *
	 * @param workingDirectory the copied project directory that receives edits
	 * @param model the optional model name
	 * @param developerInstructions the optional direct agent contract
	 * @param effort the optional reasoning effort
	 * @param permissionProfile the optional named Codex permission profile
	 * @param runtimeWorkspaceRoots the copied project followed by read-only evidence roots available to the profile
	 * @throws IOException if startup fails or no thread ID is returned
	 */
	void initialize(Path workingDirectory, String model, String developerInstructions, String effort,
		String permissionProfile, List<Path> runtimeWorkspaceRoots) throws IOException
	{
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(model, "model").isNotNull();
		requireThat(developerInstructions, "developerInstructions").isNotNull();
		requireThat(effort, "effort").isNotNull();
		requireThat(runtimeWorkspaceRoots, "runtimeWorkspaceRoots").isNotEmpty();
		this.effort = effort;
		synchronized (input)
		{
			readResponse(1, CodexAppServerProtocol.initialize());
			input.write(CodexAppServerProtocol.initialized());
			input.flush();
			// CAT owns this short-lived local session through its bridge. Disable Remote Control in the negotiated
			// app-server session because it needs separate ChatGPT authentication and otherwise can wait indefinitely
			// for an unavailable preference before CAT can submit work. Ephemeral avoids changing the user's preference.
			readResponse(2, CodexAppServerProtocol.remoteControlDisable(2));
			threadId = threadId(readResponse(3, CodexAppServerProtocol.threadStart(3, workingDirectory, model,
				developerInstructions, permissionProfile, runtimeWorkspaceRoots)));
			// Plugin installation and copied trust establish the hook prerequisite before startup. Do not issue the native
			// hooks/list diagnostic here: some app-server versions stall after thread/start, preventing a usable session.
			// The completed turn remains the authoritative delivery boundary for subagent hooks.
		}
	}

	/**
	 * Sends one text turn and reads through its completion notification.
	 *
	 * @param request the non-blank user prompt
	 * @return the captured app-server event stream for the turn
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
		if (threadId == null)
			throw new IOException("Codex app-server was not initialized");
		synchronized (input)
		{
			synchronized (turnState)
			{
				activeTurnId = null;
				awaitingTurnStart = true;
			}
			input.write(CodexAppServerProtocol.turnStart(nextRequestId, threadId, request, effort));
			nextRequestId += 1;
			input.flush();
		}
		StringBuilder events = new StringBuilder(startupEvents);
		startupEvents.setLength(0);
		String line = output.readLine();
		while (line != null)
		{
			events.append(line).append('\n');
			listener.accept(line);
			captureActiveTurn(line);
			if (turnCompletedFor(line, threadId))
			{
				clearActiveTurn();
				return completedTurn(events.toString(), line);
			}
			line = output.readLine();
		}
		String diagnostic = "Codex app-server ended before turn completion";
		String error = standardError.await();
		if (!error.isBlank())
			diagnostic += ": " + error;
		clearActiveTurn();
		return new HarnessProcessResult(process.exitValue(), events.toString(), diagnostic);
	}

	@Override
	public void steer(String request) throws IOException
	{
		requireThat(request, "request").isNotBlank();
		String turnId = requiredActiveTurn();
		synchronized (input)
		{
			input.write(CodexAppServerProtocol.turnSteer(nextRequestId, threadId, turnId, request));
			nextRequestId += 1;
			input.flush();
		}
	}

	@Override
	public void interrupt() throws IOException
	{
		String turnId = requiredActiveTurn();
		synchronized (input)
		{
			input.write(CodexAppServerProtocol.turnInterrupt(nextRequestId, threadId, turnId));
			nextRequestId += 1;
			input.flush();
		}
	}

	/**
	 * Returns whether one native event completes the thread that received the current request.
	 *
	 * @param event the raw native event
	 * @param targetThreadId the requested thread identifier
	 * @return {@code true} if the event completes that thread
	 * @throws IOException if a completion event is malformed
	 */
	private boolean turnCompletedFor(String event, String targetThreadId) throws IOException
	{
		assert event != null;
		assert targetThreadId != null;
		if (!event.contains("\"method\":\"turn/completed\""))
			return false;
		try
		{
			JsonNode node = scope.json().readTree(event);
			return targetThreadId.equals(node.path("params").path("threadId").stringValue());
		}
		catch (JacksonException e)
		{
			throw new IOException("Invalid Codex turn completion event", e);
		}
	}

	/**
	 * Records the identifier needed by a later steering or interruption request.
	 *
	 * @param event one native app-server event
	 * @throws IOException if a turn-start event is malformed
	 */
	private void captureActiveTurn(String event) throws IOException
	{
		assert event != null;
		if (!event.contains("\"method\":\"turn/started\""))
			return;
		try
		{
			JsonNode parameters = scope.json().readTree(event).path("params");
			if (threadId.equals(parameters.path("threadId").stringValue()))
				synchronized (turnState)
				{
					activeTurnId = parameters.path("turn").path("id").stringValue();
					awaitingTurnStart = false;
					turnState.notifyAll();
				}
		}
		catch (JacksonException e)
		{
			throw new IOException("Invalid Codex turn-start event", e);
		}
	}

	/**
	 * Returns the active turn identifier required by app-server controls.
	 *
	 * @return the non-blank active turn identifier
	 * @throws IOException if the active turn cannot be identified
	 */
	private String requiredActiveTurn() throws IOException
	{
		synchronized (turnState)
		{
			while (awaitingTurnStart && process.isAlive())
				try
				{
					turnState.wait();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for Codex to start the active turn", e);
				}
			if (activeTurnId == null || activeTurnId.isBlank())
				throw new IOException("Codex has no active steerable turn");
			return activeTurnId;
		}
	}

	/**
	 * Clears the active-turn state and wakes a pending control request.
	 */
	private void clearActiveTurn()
	{
		synchronized (turnState)
		{
			activeTurnId = null;
			awaitingTurnStart = false;
			turnState.notifyAll();
		}
	}

	/**
	 * Returns whether the Codex app-server remains alive.
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
	 * Closes app-server input and requests graceful termination.
	 *
	 * @throws IOException if closing input fails
	 */
	@Override
	public void close() throws IOException
	{
		synchronized (input)
		{
			input.close();

			// A child can keep standard error open after the app server exits, leaving the evaluator waiting for more output.
			// Stop the harness process tree first, then close the stream once no harness child can write to it.
			HarnessProcessTermination.terminate(process, "Codex");
			standardError.close();
			standardError.await();
		}
	}

	/**
	 * Converts a terminal turn notification into a process result.
	 *
	 * @param events the captured app-server event stream
	 * @param notification the terminal turn notification
	 * @return a successful result only when Codex completed the turn successfully
	 * @throws IOException if the notification is malformed
	 */
	private HarnessProcessResult completedTurn(String events, String notification) throws IOException
	{
		assert events != null;
		assert notification != null;
		try
		{
			JsonNode turn = scope.json().readTree(notification).path("params").path("turn");
			String status = turn.path("status").stringValue();
			if ("completed".equals(status))
			{
				if (verifyCatHookDelivery)
					CodexHookDelivery.verifySubagentDelivery(events, scope.json());
				return new HarnessProcessResult(0, events, "", true, assistantResponse(events));
			}
			String diagnostic = "Codex turn finished with status " + status;
			JsonNode error = turn.path("error").path("message");
			if (error.isString() && !error.stringValue().isBlank())
				diagnostic += ": " + error.stringValue();
			return new HarnessProcessResult(1, events, diagnostic);
		}
		catch (JacksonException e)
		{
			throw new IOException("Codex app-server returned malformed turn/completed notification", e);
		}
	}

	/**
	 * Extracts the final completed assistant message without exposing Codex's event schema to the harness-neutral bridge.
	 *
	 * @param events the complete native event stream for one terminal turn
	 * @return the last completed assistant message, or an empty string when none was emitted
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
				JsonNode item = event.path("params").path("item");
				if (event.path("method").asString().equals("item/completed") &&
					item.path("type").asString().equals("agentMessage") && item.path("text").isString() &&
					!item.path("text").asString().isBlank())
				{
					response = item.path("text").asString();
				}
			}
			catch (JacksonException _)
			{
				// The complete native stream remains retained even when an unrelated diagnostic line is not protocol JSON.
			}
		}
		return response;
	}

	/**
	 * Writes a request and reads through its matching JSON-RPC response.
	 *
	 * @param requestId the request identifier
	 * @param request the JSON-RPC request line
	 * @return the response line
	 * @throws IOException if the app-server exits or omits the response
	 */
	private String readResponse(long requestId, String request) throws IOException
	{
		assert requestId > 0;
		assert request != null;
		input.write(request);
		input.flush();
		String expected = "\"id\":" + requestId;
		String line = output.readLine();
		while (line != null)
		{
			if (line.contains(expected))
				return line;
			startupEvents.append(line).append('\n');
			line = output.readLine();
		}
		throw new IOException("Codex app-server ended before responding to request " + requestId);
	}

	/**
	 * Extracts the thread ID from one thread/start response.
	 *
	 * @param response the response JSON
	 * @return the non-blank thread ID
	 * @throws IOException if the response is malformed or omits the ID
	 */
	private String threadId(String response) throws IOException
	{
		assert response != null;
		try
		{
			JsonNode id = scope.json().readTree(response).path("result").path("thread").path("id");
			if (!id.isString() || id.stringValue().isBlank())
				throw new IOException("Codex app-server returned no thread identifier");
			return id.stringValue();
		}
		catch (JacksonException e)
		{
			throw new IOException("Codex app-server returned malformed thread/start response", e);
		}
	}
}
