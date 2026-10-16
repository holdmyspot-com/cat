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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Adapts Codex's app-server CLI process to the multi-request {@link HarnessProcess} contract.
 */
public final class CodexProcess implements HarnessProcess
{
	/**
	 * How long to wait for the operating system to report Codex's exit once its output has ended.
	 */
	private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(10);
	private final Process process;
	private final HarnessRunnerScope scope;
	private final OutputStreamWriter input;
	private final BufferedReader output;
	private final HarnessStandardError standardError;
	private final boolean verifyCatHookDelivery;
	/**
	 * The Codex home whose {@code sessions} directory holds the rollouts of the app-server's threads.
	 */
	private final Path codexHome;
	/**
	 * The directory that keeps the rollouts once the app-server exits.
	 */
	private final Path rollouts;
	private final StringBuilder startupEvents = new StringBuilder();
	/**
	 * The next JSON-RPC request identifier; guarded by {@link #input}.
	 */
	private long nextRequestId = 3;
	private String threadId;
	private String effort = "";
	/**
	 * Indicates whether a turn has been requested and has not yet ended; guarded by {@link #input}.
	 */
	private boolean turnActive;
	/**
	 * The active turn's identifier, or {@code null} until Codex answers the {@code turn/start} request; guarded by
	 * {@link #input}.
	 */
	private String turnId;
	/**
	 * Indicates whether the caller asked to interrupt the active turn; guarded by {@link #input}.
	 */
	private boolean interruptRequested;

	/**
	 * Wraps a started Codex app-server process.
	 *
	 * @param process the started Codex app-server process
	 * @param scope services owned for this app-server lifetime
	 * @param verifyCatHookDelivery whether CAT hook configuration and delegated-hook delivery must be verified
	 * @param codexHome the Codex home of the app-server, whose {@code sessions} directory holds its threads' rollouts
	 * @param rollouts the directory that keeps the rollouts once the app-server exits
	 * @throws NullPointerException if {@code process}, {@code scope}, {@code codexHome} or {@code rollouts} are null
	 */
	public CodexProcess(Process process, HarnessRunnerScope scope, boolean verifyCatHookDelivery, Path codexHome,
		Path rollouts)
	{
		requireThat(process, "process").isNotNull();
		requireThat(scope, "scope").isNotNull();
		requireThat(codexHome, "codexHome").isNotNull();
		requireThat(rollouts, "rollouts").isNotNull();
		this.process = process;
		this.scope = scope;
		this.verifyCatHookDelivery = verifyCatHookDelivery;
		this.codexHome = codexHome;
		this.rollouts = rollouts;
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

	/**
	 * Sends one text turn and reads through the root thread's turn completion.
	 * <p>
	 * The result's assistant messages contain every non-blank {@code agentMessage} item that the root thread completed
	 * during the turn, in completion order. Its assistant response is the last of those messages, or empty when none
	 * were collected.
	 * Messages from child threads are excluded. A turn that ends because the caller interrupted it through
	 * {@link #interrupt()} is a completed turn.
	 * <p>
	 * On the first turn, the listener first receives the lines Codex printed during start-up, such as the
	 * {@code thread/started} notification, in arrival order; later turns do not receive them again. The listener then
	 * receives each line of this turn. The result's event stream holds the same lines.
	 * <p>
	 * When Codex answers the {@code turn/start} request with an error, such as for a prompt longer than its input
	 * limit, the turn fails with Codex's error message.
	 *
	 * @param request the non-blank user prompt
	 * @param listener receives each harness event line
	 * @return the captured app-server event stream for the turn
	 * @throws IOException if input or output fails, or the listener fails
	 */
	@Override
	public HarnessProcessResult sendTurn(String request, HarnessEventListener listener) throws IOException
	{
		requireThat(request, "request").isNotBlank();
		requireThat(listener, "listener").isNotNull();
		long turnRequestId;
		String startup;
		StringBuilder events;
		synchronized (input)
		{
			if (threadId == null)
				throw new IOException("Codex app-server was not initialized");
			turnRequestId = nextRequestId;
			nextRequestId += 1;
			input.write(CodexAppServerProtocol.turnStart(turnRequestId, threadId, request, effort));
			input.flush();
			turnActive = true;
			turnId = null;
			interruptRequested = false;
			startup = startupEvents.toString();
			events = new StringBuilder(startup);
			startupEvents.setLength(0);
		}
		// The turn reads output without holding the input lock, so interrupt() can write a request while it waits.
		try
		{
			// Lines Codex printed before this turn, such as thread/started, reach the listener ahead of the turn's own lines
			for (String startupLine : startup.lines().toList())
				listener.accept(startupLine);
			List<String> assistantMessages = new ArrayList<>();
			String line = output.readLine();
			while (line != null)
			{
				events.append(line).append('\n');
				listener.accept(line);
				JsonNode event = parse(line);
				// Codex sends no turn/completed for a turn that it refused to start
				String refusal = turnStartError(event, turnRequestId);
				if (refusal != null)
				{
					return new HarnessProcessResult(1, events.toString(), "Codex app-server did not start the turn: " +
						refusal);
				}
				identifyTurn(event, turnRequestId);
				String rootMessage = rootAgentMessage(event);
				if (rootMessage != null && !rootMessage.isBlank())
					assistantMessages.add(rootMessage);
				if (turnCompletedFor(line, threadId))
				{
					boolean interrupted;
					synchronized (input)
					{
						turnActive = false;
						interrupted = interruptRequested;
					}
					return completedTurn(events.toString(), line, assistantMessages, interrupted);
				}
				line = output.readLine();
			}
			String diagnostic = "Codex app-server ended before turn completion";
			String error = standardError.await();
			if (!error.isBlank())
				diagnostic += ": " + error;
			return new HarnessProcessResult(exitStatus(), events.toString(), diagnostic);
		}
		finally
		{
			synchronized (input)
			{
				turnActive = false;
				turnId = null;
			}
		}
	}

	/**
	 * Returns Codex's exit status once its output has ended. Codex closes its output before the operating system
	 * reports that it exited, so the status is not available at once.
	 *
	 * @return the exit status
	 * @throws IOException if Codex does not exit in time or the wait is interrupted
	 */
	private int exitStatus() throws IOException
	{
		try
		{
			if (!process.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
				throw new IOException("Codex closed its output but did not exit within " + EXIT_TIMEOUT.toSeconds() +
					" seconds");
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for Codex to exit", e);
		}
		return process.exitValue();
	}

	/**
	 * Stops the active turn with a {@code turn/interrupt} request. Codex then ends the turn with status
	 * {@code interrupted}, which completes the turn that {@link #sendTurn(String, HarnessEventListener)} is reading.
	 * When Codex has not yet identified the requested turn, the request is sent as soon as it does. Does nothing when no
	 * turn is in progress, since the turn the caller meant to stop has already ended.
	 *
	 * @throws IOException if the request cannot be written to Codex
	 */
	@Override
	public void interrupt() throws IOException
	{
		synchronized (input)
		{
			if (!turnActive || interruptRequested)
				return;
			interruptRequested = true;
			if (turnId != null)
				writeInterrupt();
		}
	}

	/**
	 * Writes the {@code turn/interrupt} request for the identified active turn. The caller must hold the
	 * {@link #input} lock.
	 *
	 * @throws IOException if the request cannot be written to Codex
	 */
	private void writeInterrupt() throws IOException
	{
		assert Thread.holdsLock(input);
		assert turnId != null;
		input.write(CodexAppServerProtocol.turnInterrupt(nextRequestId, threadId, turnId));
		nextRequestId += 1;
		input.flush();
	}

	/**
	 * Records the active turn's identifier from Codex's answer to the {@code turn/start} request, and sends an
	 * interrupt that the caller requested before Codex identified the turn.
	 *
	 * @param event a harness event, or {@code null} if the line was not JSON
	 * @param turnRequestId the {@code turn/start} request's identifier
	 * @throws IOException if a pending interrupt cannot be written to Codex
	 */
	private void identifyTurn(JsonNode event, long turnRequestId) throws IOException
	{
		if (event == null || event.path("id").asLong(-1) != turnRequestId)
			return;
		JsonNode id = event.path("result").path("turn").path("id");
		if (!id.isString() || id.stringValue().isBlank())
			return;
		synchronized (input)
		{
			turnId = id.stringValue();
			if (interruptRequested)
				writeInterrupt();
		}
	}

	/**
	 * Returns the message of Codex's error answer to the {@code turn/start} request.
	 *
	 * @param event a harness event, or {@code null} if the line was not JSON
	 * @param turnRequestId the {@code turn/start} request's identifier
	 * @return the error's message, or {@code null} if the event is not an error answer to that request
	 */
	private static String turnStartError(JsonNode event, long turnRequestId)
	{
		if (event == null || event.path("id").asLong(-1) != turnRequestId)
			return null;
		JsonNode error = event.path("error");
		if (!error.isObject())
			return null;
		return error.path("message").asString("");
	}

	/**
	 * Returns the text of an {@code agentMessage} item that the root thread completed.
	 *
	 * @param event a harness event, or {@code null} if the line was not JSON
	 * @return the message text, or {@code null} if the event is not a completed root-thread {@code agentMessage}
	 */
	private String rootAgentMessage(JsonNode event)
	{
		if (event == null || !event.path("method").asString("").equals("item/completed"))
			return null;
		JsonNode params = event.path("params");
		if (!threadId.equals(params.path("threadId").asString("")))
			return null;
		JsonNode item = params.path("item");
		if (!item.path("type").asString("").equals("agentMessage") || !item.path("text").isString())
			return null;
		return item.path("text").stringValue();
	}

	/**
	 * Parses one harness event line.
	 *
	 * @param line the raw line
	 * @return the parsed event, or {@code null} if the line is not JSON
	 */
	private JsonNode parse(String line)
	{
		assert line != null;
		try
		{
			return scope.json().readTree(line);
		}
		catch (JacksonException _)
		{
			// The complete harness stream remains retained even when an unrelated diagnostic line is not protocol JSON.
			return null;
		}
	}

	/**
	 * Indicates whether one harness event completes the thread that received the current request.
	 *
	 * @param event the raw harness event
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
	 * Indicates whether the Codex app-server remains alive.
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
	 * Closes app-server input, waits for the app-server to exit, and then copies the rollouts of its threads into the
	 * rollouts directory, so that what each thread's model received outlives the Codex home.
	 *
	 * @throws IOException if closing input fails or the rollouts cannot be copied
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
			keepRollouts();
		}
	}

	/**
	 * Copies every rollout file below the Codex home's {@code sessions} directory into the rollouts directory, which
	 * is created even when the app-server started no thread. Links are not followed.
	 *
	 * @throws IOException if the rollouts cannot be listed or copied
	 */
	private void keepRollouts() throws IOException
	{
		Files.createDirectories(rollouts);
		Path sessions = codexHome.resolve("sessions");
		if (!Files.isDirectory(sessions, LinkOption.NOFOLLOW_LINKS))
			return;
		List<Path> files;
		try (Stream<Path> walk = Files.walk(sessions))
		{
			files = walk.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).
				filter(file -> file.getFileName().toString().startsWith("rollout-")).
				filter(file -> file.getFileName().toString().endsWith(".jsonl")).
				toList();
		}
		for (Path file : files)
			Files.copy(file, rollouts.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Converts a terminal turn notification into a process result.
	 *
	 * @param events the captured app-server event stream
	 * @param notification the terminal turn notification
	 * @param assistantMessages non-blank completed root-thread messages in completion order; read without mutation
	 * @param interruptRequested whether the caller interrupted the turn
	 * @return a successful result only when Codex completed the turn successfully, or ended it as {@code interrupted}
	 *   after the caller interrupted it
	 * @throws IOException if the notification is malformed
	 */
	private HarnessProcessResult completedTurn(String events, String notification, List<String> assistantMessages,
		boolean interruptRequested) throws IOException
	{
		assert events != null;
		assert notification != null;
		assert assistantMessages != null;

		String assistantResponse = "";
		if (!assistantMessages.isEmpty())
			assistantResponse = assistantMessages.getLast();

		try
		{
			JsonNode turn = scope.json().readTree(notification).path("params").path("turn");
			String status = turn.path("status").stringValue();
			if ("completed".equals(status))
			{
				if (verifyCatHookDelivery)
					CodexHookDelivery.verifySubagentDelivery(events, scope.json());
				return new HarnessProcessResult(0, events, "", true, assistantResponse, assistantMessages);
			}
			if (interruptRequested && "interrupted".equals(status))
				return new HarnessProcessResult(0, events, "", true, assistantResponse, assistantMessages);
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
		String diagnostic = "Codex app-server ended before responding to request " + requestId;
		String error = standardError.await().strip();
		if (!error.isEmpty())
			diagnostic += ": " + error;
		throw new IOException(diagnostic);
	}

	/**
	 * Extracts the thread ID from one thread/start response.
	 *
	 * @param response the response JSON
	 * @return the non-blank thread ID
	 * @throws IOException if Codex did not start the thread, or the response is malformed or omits the ID
	 */
	private String threadId(String response) throws IOException
	{
		assert response != null;
		try
		{
			JsonNode tree = scope.json().readTree(response);
			JsonNode error = tree.path("error").path("message");
			if (error.isString())
				throw new IOException("Codex app-server did not start the thread: " + error.stringValue());
			JsonNode id = tree.path("result").path("thread").path("id");
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
