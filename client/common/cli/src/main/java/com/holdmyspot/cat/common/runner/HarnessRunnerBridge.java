package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.FileReplacer;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Forwards line-delimited JSON requests to one persistent harness process and writes each response.
 */
public final class HarnessRunnerBridge
{
	/**
	 * How often the bridge checks a conversation for the asking session's next message.
	 */
	private static final Duration CONVERSATION_POLL = Duration.ofMillis(100);

	/**
	 * Starts one session and serves turn and completion messages until completion. Harness events are written once as
	 * {@code harness_event} responses; the terminal response records the status, error, final assistant response
	 * and every assistant message in order. A turn that fails ends with a {@code turn_failed} response that records
	 * why, and the bridge then fails at its next request, unless the client interrupted the turn and the harness keeps
	 * running, as Claude Code does when it fails the turn that it was asked to interrupt: an interrupt ends only the
	 * turn in progress, so the bridge then keeps serving.
	 *
	 * @param starter starts the isolated persistent session
	 *
	 * @param input the JSONL client input
	 *
	 * @param output the JSONL client output
	 *
	 * @return the run that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, or stream fails
	 */
	public static HarnessRun serve(HarnessRunStarter starter, Reader input, Writer output) throws IOException
	{
		return serve(starter, null, true, input, output);
	}

	/**
	 * Starts one session and retains the complete JSONL bridge transcript at the requested path.
	 *
	 * @param starter starts the isolated persistent session
	 *
	 * @param input the JSONL client input
	 *
	 * @param output the durable JSONL client-output destination
	 *
	 * @return the run that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, or transcript I/O fails
	 */
	public static HarnessRun serve(HarnessRunStarter starter, Reader input, Path output) throws IOException
	{
		return serve(starter, null, true, input, output);
	}

	/**
	 * Starts one session and serves JSONL messages while applying owned-run lifecycle transitions.
	 * <p>
	 * While a turn writes no response for 30 seconds, the bridge writes a {@code waiting} response with the run's
	 * {@code run_id} and the turn's {@code quiet_seconds}, and repeats it every 30 seconds while the turn stays quiet.
	 * The session's {@link HarnessRunnerScope} supplies the clock that times the quiet turn and the heartbeat that checks
	 * it.
	 *
	 * @param starter starts the isolated persistent session
	 *
	 * @param manager the run manager, or {@code null} when the caller owns lifecycle transitions
	 *
	 * @param keepRun whether a completed run should be retained
	 *
	 * @param input the JSONL client input
	 *
	 * @param output the JSONL client output
	 *
	 * @return the run that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, lifecycle, or stream fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	public static HarnessRun serve(HarnessRunStarter starter, HarnessRunManager manager, boolean keepRun, Reader input,
		Writer output) throws IOException
	{
		return serve(starter, manager, keepRun, input, output, null, null);
	}

	/**
	 * Serves a bridge with optional requested-session ownership.
	 *
	 * @param starter starts the parent session
	 * @param manager the lifecycle manager
	 * @param keepRun indicates whether the completed run is retained
	 * @param input the bridge input
	 * @param output the bridge output
	 * @param options the runner options, null when the caller does not serve the session as a runner
	 * @param requestedStarter starts child runners, null only when no request broker starts
	 * @return the completed parent run
	 * @throws IOException if the bridge or a request broker fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	private static HarnessRun serve(HarnessRunStarter starter, HarnessRunManager manager, boolean keepRun, Reader input,
		Writer output, HarnessRunnerOptions options, RequestedSessionStarter requestedStarter) throws IOException
	{
		requireThat(starter, "starter").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		if (options == null || options.conversation() == null)
			return serve(starter, manager, keepRun, input, output, options, requestedStarter, null);
		// The folder is checked before the session starts
		try (Conversation conversation = Conversation.open(options.conversation()))
		{
			// However the session ends, the asking session learns that no further turn_end comes
			String status = "failed";
			try
			{
				HarnessRun run = serve(starter, manager, keepRun, input, conversation.childWriter(output), options,
					requestedStarter, conversation);
				status = "completed";
				return run;
			}
			finally
			{
				conversation.end(status);
			}
		}
	}

	/**
	 * Serves a bridge with optional requested-session ownership, reading its records from {@code input} or from a
	 * conversation.
	 *
	 * @param starter starts the parent session
	 * @param manager the lifecycle manager
	 * @param keepRun indicates whether the completed run is retained
	 * @param input the bridge input when {@code conversation} is null
	 * @param output the bridge output
	 * @param options the runner options, null when the caller does not serve the session as a runner
	 * @param requestedStarter starts child runners, null only when no request broker starts
	 * @param conversation the conversation whose messages are the bridge's records, or null to read {@code input}
	 * @return the completed parent run
	 * @throws IOException if the bridge or a request broker fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	private static HarnessRun serve(HarnessRunStarter starter, HarnessRunManager manager, boolean keepRun, Reader input,
		Writer output, HarnessRunnerOptions options, RequestedSessionStarter requestedStarter,
		Conversation conversation) throws IOException
	{
		HarnessRun run = null;
		boolean completed = false;
		int turnNumber = 0;
		FutureTask<Void> activeTurn = null;
		CountDownLatch activeTurnStarted = null;
		// Set once the client interrupts the active turn
		AtomicBoolean activeTurnInterrupted = new AtomicBoolean();
		AtomicBoolean transcriptRecorded = new AtomicBoolean();
		boolean copiesChecked = false;
		try
		{
			try (HarnessRunSession session = starter.start();
				ClientOutput client = ClientOutput.open(session, output);
				SessionRequestBroker broker = requestBroker(session, options, requestedStarter))
			{
				try
				{
					run = session.run();
					if (manager != null)
						manager.markIdle(run);
					client.write(event(session.scope(), "started").put("run_id", session.run().id()));
					try (BufferedReader lines = lines(session, input, conversation))
					{
						String line = lines.readLine();
						while (line != null)
						{
							JsonNode request = read(session.scope(), line);
							String type = requiredText(request, "type");
							if (type.equals("turn"))
							{
								awaitActiveTurn(activeTurn);
								String prompt = requiredText(request, "prompt");
								int currentTurn = turnNumber + 1;
								CountDownLatch turnStarted = new CountDownLatch(1);
								AtomicBoolean turnInterrupted = new AtomicBoolean();
								// An interrupt that this thread reads next must stop this turn even if the turn's thread has not
								// yet sent it to the harness
								session.prepareTurn();
								client.startTurn();
								activeTurn = new FutureTask<>(() ->
								{
									try
									{
										turnStarted.countDown();
										HarnessProcessResult result;
										try
										{
											if (manager != null)
												manager.markWorking(session.run());
											result = session.sendTurn(prompt, harnessEvent ->
											{
												if (manager != null)
													manager.appendTurnEvent(session.run(), currentTurn, harnessEvent);
												forwardHarnessEvent(session, client, harnessEvent);
												recordSessionTranscript(session, client, transcriptRecorded);
											});
											recordSessionTranscript(session, client, transcriptRecorded);
											if (manager != null)
												manager.writeTurnOutput(session.run(), currentTurn, result);
											if (!result.completed())
												throw harnessTurnFailure(result);
										}
										catch (IOException | RuntimeException e)
										{
											if (broker != null)
												broker.stopChildren();
											reportTurnFailure(session, client, e);
											// The interrupt ended only this turn, and the harness serves the next one
											if (turnInterrupted.get() && session.processIsAlive())
											{
												if (manager != null)
													manager.markIdle(session.run());
												return null;
											}
											throw e;
										}
										if (manager != null)
											manager.markIdle(session.run());
										ObjectNode response = event(session.scope(), "turn_completed");
										response.put("run_id", session.run().id());
										response.put("exit_code", result.exitCode());
										response.put("error", result.standardError());
										response.put("assistant_response", result.assistantResponse());
										ArrayNode reply = response.putArray("assistant_messages");
										for (String message : result.assistantMessages())
											reply.add(message);
										client.endTurn(response);
										return null;
									}
									finally
									{
										client.endTurn();
									}
								});
								Thread.ofPlatform().start(activeTurn);
								activeTurnStarted = turnStarted;
								activeTurnInterrupted = turnInterrupted;
								turnNumber = currentTurn;
							}
							else if (type.equals("steer"))
							{
								if (!client.turnActive())
									throw new IOException("Bridge has no active turn to steer");
								awaitRequestedTurnStart(request, activeTurnStarted);
								session.steer(requiredText(request, "prompt"));
								client.write(event(session.scope(), "steered").put("run_id", session.run().id()));
							}
							else if (type.equals("interrupt"))
							{
								// A client cannot know whether its interrupt reaches the bridge before or after the turn ends. Once the
								// turn has ended there is nothing to stop, so the request is ignored and the session keeps serving.
								if (client.turnActive())
								{
									awaitRequestedTurnStart(request, activeTurnStarted);
									activeTurnInterrupted.set(true);
									// The record precedes the end of the turn that it stops, so a client can tell which turn the
									// interrupt ended
									if (client.writeInTurn(event(session.scope(), "interrupted").
										put("run_id", session.run().id())))
									{
										if (broker != null)
											broker.stopChildren();
										session.interrupt();
									}
								}
							}
							else if (type.equals("complete"))
							{
								awaitActiveTurn(activeTurn);
								client.write(event(session.scope(), "completed").put("run_id", session.run().id()));
								completed = true;
								break;
							}
							else
								throw new IOException("Unsupported bridge message type: " + type);
							line = lines.readLine();
						}
					}
					// Input that ends without complete means that the requester has gone away, so no one reads the rest of
					// the turn: the failure below stops it instead of waiting for it
					if (!completed)
						throw new IOException("Bridge input ended without a complete message");
				}
				catch (IOException | RuntimeException e)
				{
					// A running turn holds the session's monitor, so closing the session would wait for it. Interrupt the turn
					// first so that a failure of the bridge itself does not wait for a turn that may never end.
					stopTurn(activeTurn);
					throw e;
				}
			}
			if (manager != null)
			{
				// The session has ended, so the copies are as it left them; it may not end the run with a result that
				// rests on changed copies
				copiesChecked = true;
				Optional<String> changes = manager.changedLinkCopies(run);
				if (changes.isPresent())
					throw new IOException(changes.get());
				manager.markCompleted(run);
				if (!keepRun)
					manager.deleteRun(run);
			}
			return run;
		}
		catch (IOException e)
		{
			if (manager != null && run != null)
			{
				IOException failure = e;
				if (!copiesChecked)
					failure = withChangedLinkCopies(manager, run, e);
				String diagnostic = failure.getMessage();
				if (diagnostic == null)
					diagnostic = failure.getClass().getSimpleName();
				manager.fail(run, diagnostic);
				throw failure;
			}
			throw e;
		}
		finally
		{
			stopTurn(activeTurn);
		}
	}

	/**
	 * Adds to the failure of a run whose session has ended any change that the session made to the read-only copies of
	 * the targets of the project's links outside it, so that the run's diagnostic names each changed path whatever else
	 * failed.
	 *
	 * @param manager the lifecycle manager
	 * @param run     the failed run
	 * @param failure why the run failed
	 * @return {@code failure}, or a failure that names the changed copies and is caused by {@code failure}
	 */
	private static IOException withChangedLinkCopies(HarnessRunManager manager, HarnessRun run, IOException failure)
	{
		Optional<String> changes;
		try
		{
			changes = manager.changedLinkCopies(run);
		}
		catch (IOException e)
		{
			failure.addSuppressed(e);
			return failure;
		}
		if (changes.isEmpty())
			return failure;
		String reason = failure.getMessage();
		if (reason == null)
			reason = failure.getClass().getSimpleName();
		return new IOException(changes.get() + " The run also failed: " + reason, failure);
	}

	/**
	 * Returns the bridge's records as lines.
	 *
	 * @param session      the live harness session, whose scope paces the checks for a conversation's next message
	 * @param input        the bridge input when {@code conversation} is null
	 * @param conversation the conversation whose messages are the bridge's records, or null to read {@code input}
	 * @return the lines
	 */
	private static BufferedReader lines(HarnessRunSession session, Reader input, Conversation conversation)
	{
		if (conversation == null)
			return new BufferedReader(input);
		return new BufferedReader(conversation.records(session.scope().waiting().every(CONVERSATION_POLL)));
	}

	/**
	 * Interrupts a turn that is still running.
	 *
	 * @param turn the last turn that started, or {@code null} if none started
	 */
	private static void stopTurn(FutureTask<Void> turn)
	{
		if (turn != null)
			turn.cancel(true);
	}

	/**
	 * Tells the client that the active turn failed, so that it stops waiting for the turn's {@code turn_completed}
	 * response. Unless the client interrupted the turn and the harness keeps running, the bridge reports the same
	 * failure again when it receives its next request.
	 *
	 * @param session the live harness session
	 * @param client  the bridge's output
	 * @param failure why the turn failed
	 */
	private static void reportTurnFailure(HarnessRunSession session, ClientOutput client, Exception failure)
	{
		assert failure != null;
		String error = failure.getMessage();
		if (error == null || error.isBlank())
			error = failure.getClass().getSimpleName();
		try
		{
			client.endTurn(event(session.scope(), "turn_failed").put("run_id", session.run().id()).
				put("error", error));
		}
		catch (IOException e)
		{
			failure.addSuppressed(e);
		}
	}

	/**
	 * Waits for the active turn so a later bridge request observes its terminal outcome.
	 *
	 * @param activeTurn the active turn, or {@code null} when none was started
	 *
	 * @throws IOException if the turn failed or this thread was interrupted
	 */
	private static void awaitActiveTurn(Future<?> activeTurn) throws IOException
	{
		if (activeTurn == null)
			return;
		try
		{
			activeTurn.get();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the active harness turn", e);
		}
		catch (ExecutionException e)
		{
			Throwable cause = e.getCause();
			if (cause instanceof IOException failure)
				throw failure;
			throw new IOException("Active harness turn failed", cause);
		}
	}

	/**
	 * Waits for the currently submitted turn only when a test explicitly requests an active-turn control point.
	 *
	 * @param request the steering or interruption request
	 *
	 * @param turnStarted the current turn's start signal
	 *
	 * @throws IOException if the requested control point is unsupported or the wait is interrupted
	 */
	private static void awaitRequestedTurnStart(JsonNode request, CountDownLatch turnStarted) throws IOException
	{
		assert request != null;
		if (!request.has("after"))
			return;
		if (!"turn_started".equals(requiredText(request, "after")))
			throw new IOException("Unsupported bridge control point: " + requiredText(request, "after"));
		if (turnStarted == null)
			throw new IOException("Bridge has no submitted turn start to await");
		try
		{
			turnStarted.await();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the active harness turn to start", e);
		}
	}

	/**
	 * Starts one managed session and retains the complete JSONL bridge transcript at the requested path.
	 *
	 * @param starter starts the isolated persistent session
	 *
	 * @param manager the run manager, or {@code null} when the caller owns lifecycle transitions
	 *
	 * @param keepRun whether a completed run should be retained
	 *
	 * @param input the JSONL client input
	 *
	 * @param output the durable JSONL client-output destination
	 *
	 * @return the run that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, lifecycle, or transcript I/O fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	public static HarnessRun serve(HarnessRunStarter starter, HarnessRunManager manager, boolean keepRun, Reader input,
		Path output) throws IOException
	{
		requireThat(output, "output").isNotNull();
		Path parent = output.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Bridge transcript has no parent directory: " + output);
		Files.createDirectories(parent);
		try (Writer transcript = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
		{
			return serve(starter, manager, keepRun, input, transcript);
		}
	}

	/**
	 * Starts one managed session and directs its JSONL transcript and receipt to the destinations that a runner's
	 * options request.
	 * <p>
	 * When the options name an output file, the transcript goes there instead of to {@code output}. When they also name
	 * a receipt, the bridge writes a receipt that names the run's retained project and that transcript, then prunes the
	 * run's runtime files.
	 *
	 * @param starter starts the isolated persistent session
	 *
	 * @param manager the run manager
	 *
	 * @param options the runner's parsed options
	 *
	 * @param json the operation-owned JSON mapper that writes the receipt
	 *
	 * @param replacer replaces the receipt
	 *
	 * @param input the JSONL client input
	 *
	 * @param output the JSONL client output when the options name no output file
	 *
	 * @return the run that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, lifecycle, transcript or receipt I/O fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	public static HarnessRun serve(HarnessRunStarter starter, HarnessRunManager manager, HarnessRunnerOptions options,
		JsonMapper json, FileReplacer replacer, Reader input, Writer output) throws IOException
	{
		return serve(starter, manager, options, json, replacer, input, output, null);
	}

	/**
	 * Serves a managed bridge with requested child sessions, then writes its retained-evidence receipt. The bridge
	 * reads its records from the options' input file when they name one, from their {@link Conversation} when they
	 * name one, and from {@code input} otherwise. A conversation also receives the bridge's records, and its last line,
	 * however the session ends, is {@code {"ended": "completed"}} when the bridge completed and
	 * {@code {"ended": "failed"}} otherwise.
	 *
	 * @param starter starts the parent session
	 * @param manager the lifecycle manager
	 * @param options runner options
	 * @param json the receipt mapper
	 * @param replacer replaces the receipt
	 * @param input the bridge input when the options name no input file
	 * @param output the bridge output when no output path is requested
	 * @param requestedStarter starts requested child runners
	 * @return the completed run
	 * @throws IOException if bridge execution or evidence publication fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	public static HarnessRun serve(HarnessRunStarter starter, HarnessRunManager manager, HarnessRunnerOptions options,
		JsonMapper json, FileReplacer replacer, Reader input, Writer output, RequestedSessionStarter requestedStarter)
		throws IOException
	{
		requireThat(manager, "manager").isNotNull();
		requireThat(options, "options").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		if (options.input() != null)
		{
			try (Reader records = Files.newBufferedReader(options.input(), StandardCharsets.UTF_8))
			{
				return serveRecords(starter, manager, options, json, replacer, records, output, requestedStarter);
			}
		}
		return serveRecords(starter, manager, options, json, replacer, input, output, requestedStarter);
	}

	/**
	 * Serves a managed bridge from one record source, then writes its retained-evidence receipt.
	 *
	 * @param starter          starts the parent session
	 * @param manager          the lifecycle manager
	 * @param options          runner options
	 * @param json             the receipt mapper
	 * @param replacer         replaces the receipt
	 * @param input            the bridge's records
	 * @param output           the bridge output when no output path is requested
	 * @param requestedStarter starts requested child runners
	 * @return the completed run
	 * @throws IOException if bridge execution or evidence publication fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	private static HarnessRun serveRecords(HarnessRunStarter starter, HarnessRunManager manager,
		HarnessRunnerOptions options, JsonMapper json, FileReplacer replacer, Reader input, Writer output,
		RequestedSessionStarter requestedStarter) throws IOException
	{
		if (options.output() == null)
			return serve(starter, manager, options.keepRun(), input, output, options, requestedStarter);
		HarnessRun run;
		Path parent = options.output().toAbsolutePath().getParent();
		Files.createDirectories(parent);
		try (Writer transcript = Files.newBufferedWriter(options.output(), StandardCharsets.UTF_8))
		{
			run = serve(starter, manager, options.keepRun(), input, transcript, options, requestedStarter);
		}
		if (options.receipt() != null)
		{
			List<Path> sessionTranscripts = List.of();
			if (options.sessionTranscripts())
				sessionTranscripts = sessionTranscripts(run);
			HarnessRunReceipt.write(json, replacer, options.receipt(), run, options.output(), sessionTranscripts);
			manager.pruneRuntime(run);
		}
		return run;
	}

	/**
	 * Starts request discovery for a session whose runner gave it access to session requests, so that each runner
	 * launch that the session's commands make is answered, with a refusal when the options allow no session request.
	 *
	 * @param session the parent session
	 * @param options runner options, null when the caller does not serve the session as a runner
	 * @param starter starts child runners
	 * @return the started broker, or null if {@code options} is null or the session's run has no requests directory
	 * @throws NullPointerException if the broker starts and {@code starter} is null
	 * @throws IOException          if the broker cannot state the run's limits
	 */
	private static SessionRequestBroker requestBroker(HarnessRunSession session, HarnessRunnerOptions options,
		RequestedSessionStarter starter) throws IOException
	{
		if (options == null || !Files.isDirectory(SessionRequestAccess.requestsDirectory(session.run())))
			return null;
		requireThat(starter, "requestedStarter").isNotNull();
		SessionRequestBroker broker = new SessionRequestBroker(session.run(), options, starter);
		try
		{
			broker.start(session.scope().heartbeatTicker());
		}
		catch (IOException | RuntimeException e)
		{
			try
			{
				broker.close();
			}
			catch (IOException suppressed)
			{
				e.addSuppressed(suppressed);
			}
			throw e;
		}
		return broker;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessRunnerBridge()
	{
	}

	/**
	 * Converts a harness turn that ended without a terminal result into the bridge's caller-visible failure.
	 *
	 * @param result the completed harness turn result
	 *
	 * @return the failure that preserves the harness exit status and safe diagnostic
	 */
	private static IOException harnessTurnFailure(HarnessProcessResult result)
	{
		assert result != null;
		String diagnostic = "Harness process ended before completing the requested turn (exit status " +
			result.exitCode() + ")";
		if (!result.standardError().isBlank())
			diagnostic += "; inspect the retained turn stderr artifact for details";
		return new IOException(diagnostic);
	}

	/**
	 * Parses one JSONL request.
	 *
	 * @param scope the live session scope
	 *
	 * @param line the request line
	 *
	 * @return the parsed object
	 *
	 * @throws IOException if the line is not a JSON object
	 */
	private static JsonNode read(HarnessRunnerScope scope, String line) throws IOException
	{
		assert scope != null;
		assert line != null;
		try
		{
			JsonNode result = scope.json().readTree(line);
			if (!result.isObject())
				throw new IOException("Bridge message must be a JSON object");
			return result;
		}
		catch (JacksonException e)
		{
			throw new IOException("Invalid bridge JSON", e);
		}
	}

	/**
	 * Gets a required non-blank string property.
	 *
	 * @param object the request object
	 *
	 * @param name the property name
	 *
	 * @return the property value
	 *
	 * @throws IOException if the property is absent or invalid
	 */
	private static String requiredText(JsonNode object, String name) throws IOException
	{
		assert object != null;
		assert name != null;
		JsonNode value = object.path(name);
		if (!value.isString() || value.stringValue().isBlank())
			throw new IOException("Bridge message requires a non-blank " + name);
		return value.stringValue();
	}

	/**
	 * Creates a bridge response object.
	 *
	 * @param scope the live session scope
	 *
	 * @param type the response type
	 *
	 * @return the response object
	 */
	private static ObjectNode event(HarnessRunnerScope scope, String type)
	{
		assert scope != null;
		assert type != null;
		return scope.json().createObjectNode().put("type", type);
	}

	/**
	 * Writes one harness event before its enclosing turn reaches completion.
	 *
	 * @param session the live harness session
	 *
	 * @param client the JSONL client output
	 *
	 * @param harnessEvent the raw harness event line
	 *
	 * @throws IOException if client output fails
	 */
	private static void forwardHarnessEvent(HarnessRunSession session, ClientOutput client, String harnessEvent)
		throws IOException
	{
		assert session != null;
		assert client != null;
		assert harnessEvent != null;
		ObjectNode response = event(session.scope(), "harness_event");
		response.put("run_id", session.run().id());
		response.put("event", harnessEvent);
		client.write(response);
	}

	/**
	 * Tells the client, once and as soon as the session's native transcript is known, where the harness writes it.
	 *
	 * @param session the live harness session
	 *
	 * @param client the JSONL client output
	 *
	 * @param recorded whether the record was written already
	 *
	 * @throws IOException if client output fails
	 */
	private static void recordSessionTranscript(HarnessRunSession session, ClientOutput client, AtomicBoolean recorded)
		throws IOException
	{
		assert session != null;
		assert client != null;
		assert recorded != null;
		if (recorded.get())
			return;
		Optional<Path> transcript = session.sessionTranscript();
		if (transcript.isEmpty() || !recorded.compareAndSet(false, true))
			return;
		ObjectNode response = event(session.scope(), "session_transcript");
		response.put("run_id", session.run().id());
		response.put("path", transcript.get().toString());
		client.write(response);
	}

	/**
	 * Lists the copies of the session transcripts that a run kept, in name order.
	 *
	 * @param run the completed run
	 *
	 * @return the copies, none if the run kept no directory of them
	 *
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<Path> sessionTranscripts(HarnessRun run) throws IOException
	{
		assert run != null;
		Path directory = run.sessionTranscripts();
		if (!Files.isDirectory(directory))
			return List.of();
		try (Stream<Path> files = Files.list(directory))
		{
			return files.sorted().toList();
		}
	}

	/**
	 * The bridge's JSONL output to its client. While a turn writes no response for {@link #QUIET_INTERVAL}, it tells
	 * the client that the turn is still running, and repeats that every {@link #QUIET_INTERVAL}.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 */
	private static final class ClientOutput implements Closeable
	{
		/**
		 * How long a turn stays quiet before the client is told that it is still running.
		 */
		private static final Duration QUIET_INTERVAL = Duration.ofSeconds(30);
		/**
		 * Guards the output and the turn's timing.
		 */
		private final Object lock = new Object();
		private final HarnessRunnerScope scope;
		private final Writer output;
		private final Clock clock;
		private final String runId;
		/**
		 * Indicates whether a turn has been submitted and has not yet ended. Changed only while holding {@link #lock},
		 * so that no {@code waiting} response follows {@code turn_completed}, and read without it, so that a request
		 * does not wait for a response that is being written.
		 */
		private volatile boolean turnActive;
		/**
		 * When the active turn started or last wrote a response other than {@code waiting}.
		 */
		private Instant lastResponse;
		/**
		 * When the next {@code waiting} response is due if the turn stays quiet.
		 */
		private Instant nextWaiting;
		/**
		 * Stops the heartbeat.
		 */
		private Closeable heartbeat;

		/**
		 * Creates the output.
		 *
		 * @param scope  the live session scope
		 * @param output the JSONL client output
		 * @param clock  measures how long a turn has been quiet
		 * @param runId  the run's identifier
		 */
		private ClientOutput(HarnessRunnerScope scope, Writer output, Clock clock, String runId)
		{
			this.scope = scope;
			this.output = output;
			this.clock = clock;
			this.runId = runId;
		}

		/**
		 * Creates the output of a session and starts its heartbeat. The session's scope supplies the clock that
		 * measures how long a turn has been quiet and the heartbeat that runs the check.
		 *
		 * @param session the live harness session
		 * @param output  the JSONL client output
		 * @return the output
		 */
		static ClientOutput open(HarnessRunSession session, Writer output)
		{
			assert session != null;
			assert output != null;
			ClientOutput client = new ClientOutput(session.scope(), output, session.scope().clock(),
				session.run().id());
			client.heartbeat = session.scope().heartbeatTicker().start(() ->
			{
				try
				{
					client.reportQuietTurn();
				}
				catch (IOException _)
				{
					// The client's output has failed; the bridge reports that failure at its next response.
				}
			});
			return client;
		}

		/**
		 * Writes one response.
		 *
		 * @param event the response
		 * @throws IOException if output fails
		 */
		void write(ObjectNode event) throws IOException
		{
			assert event != null;
			synchronized (lock)
			{
				writeLine(event);
				lastResponse = clock.instant();
				nextWaiting = lastResponse.plus(QUIET_INTERVAL);
			}
		}

		/**
		 * Writes one response while a turn is active, so that it precedes the turn's {@code turn_completed} or
		 * {@code turn_failed} response.
		 *
		 * @param event the response
		 * @return {@code false} if no turn is active, in which case nothing is written
		 * @throws IOException if output fails
		 */
		boolean writeInTurn(ObjectNode event) throws IOException
		{
			assert event != null;
			synchronized (lock)
			{
				if (!turnActive)
					return false;
				write(event);
				return true;
			}
		}

		/**
		 * Records that a turn was submitted.
		 */
		void startTurn()
		{
			synchronized (lock)
			{
				lastResponse = clock.instant();
				nextWaiting = lastResponse.plus(QUIET_INTERVAL);
				turnActive = true;
			}
		}

		/**
		 * Ends the active turn and then writes its {@code turn_completed} or {@code turn_failed} response. A client may
		 * send an interrupt as soon as it reads that response, before the turn's thread returns, so the turn ends first
		 * and the interrupt finds no active turn.
		 *
		 * @param event the response
		 * @throws IOException if output fails
		 */
		void endTurn(ObjectNode event) throws IOException
		{
			assert event != null;
			synchronized (lock)
			{
				turnActive = false;
				write(event);
			}
		}

		/**
		 * Records that the active turn has ended, whether or not it completed.
		 */
		void endTurn()
		{
			synchronized (lock)
			{
				turnActive = false;
			}
		}

		/**
		 * Indicates whether a turn has been submitted and has not yet ended.
		 *
		 * @return {@code true} if a turn is active
		 */
		boolean turnActive()
		{
			return turnActive;
		}

		/**
		 * Writes a {@code waiting} response if the active turn has been quiet for another {@link #QUIET_INTERVAL}.
		 *
		 * @throws IOException if output fails
		 */
		void reportQuietTurn() throws IOException
		{
			synchronized (lock)
			{
				if (!turnActive)
					return;
				Instant now = clock.instant();
				if (now.isBefore(nextWaiting))
					return;
				ObjectNode waiting = event(scope, "waiting");
				waiting.put("run_id", runId);
				waiting.put("quiet_seconds", Duration.between(lastResponse, now).toSeconds());
				writeLine(waiting);
				while (!nextWaiting.isAfter(now))
					nextWaiting = nextWaiting.plus(QUIET_INTERVAL);
			}
		}

		/**
		 * Writes and flushes one JSONL line.
		 *
		 * @param event the response
		 * @throws IOException if output fails
		 */
		private void writeLine(ObjectNode event) throws IOException
		{
			output.write(scope.json().writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(event));
			output.write('\n');
			output.flush();
		}

		@Override
		public void close() throws IOException
		{
			heartbeat.close();
		}
	}
}
