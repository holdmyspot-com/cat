package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.runner.HarnessProcess;
import io.github.cowwoc.cat.common.runner.HarnessEventListener;
import io.github.cowwoc.cat.common.runner.HarnessProcessResult;
import io.github.cowwoc.cat.common.runner.HarnessProcessStarter;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.HarnessStandardError;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.time.Instant;
import java.nio.file.Path;

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
	private final StringBuilder startupEvents = new StringBuilder();
	private long nextRequestId = 3;
	private String threadId;
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
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(model, "model").isNotNull();
		requireThat(developerInstructions, "developerInstructions").isNotNull();
		requireThat(effort, "effort").isNotNull();
		this.effort = effort;
		synchronized (input)
		{
			readResponse(1, CodexAppServerProtocol.initialize());
			input.write(CodexAppServerProtocol.initialized());
			input.flush();
			threadId = threadId(readResponse(2, CodexAppServerProtocol.threadStart(workingDirectory, model,
				developerInstructions, permissionProfile)));
			if (verifyCatHookDelivery)
			{
				long requestId = nextRequestId;
				nextRequestId += 1;
				CodexHookDelivery.verifyConfiguredHooks(scope.json().readTree(readResponse(requestId,
					CodexAppServerProtocol.hooksList(requestId, workingDirectory))));
			}
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
			input.write(CodexAppServerProtocol.turnStart(nextRequestId, threadId, request, effort));
			nextRequestId += 1;
			input.flush();
			StringBuilder events = new StringBuilder(startupEvents);
			startupEvents.setLength(0);
			String line = output.readLine();
			while (line != null)
			{
				events.append(line).append('\n');
				listener.accept(line);
				if (turnCompletedFor(line, threadId))
					return completedTurn(events.toString(), line);
				line = output.readLine();
			}
			String diagnostic = "Codex app-server ended before turn completion";
			String error = standardError.await();
			if (!error.isBlank())
				diagnostic += ": " + error;
			return new HarnessProcessResult(process.exitValue(), events.toString(), diagnostic);
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
			try
			{
				process.waitFor();
				standardError.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for Codex to exit", e);
			}
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
				return new HarnessProcessResult(0, events, "");
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
