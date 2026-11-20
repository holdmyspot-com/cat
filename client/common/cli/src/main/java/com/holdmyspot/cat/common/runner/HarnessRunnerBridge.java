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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
	 * why, and the bridge then fails at its next request, unless the harness keeps running and either the client
	 * interrupted the turn, as Claude Code fails the turn that it was asked to interrupt, or the records are a
	 * conversation's: a failed turn then ends only that turn, so the bridge keeps serving and the next turn continues
	 * the same session. A conversation whose last turn failed fails at {@code complete}.
	 *
	 * @param starter starts the isolated persistent session
	 *
	 * @param input the JSONL client input
	 *
	 * @param output the JSONL client output
	 *
	 * @return the nested session that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, or stream fails
	 */
	public static NestedSession serve(NestedSessionStarter starter, Reader input, Writer output) throws IOException
	{
		return serve(starter, (NestedSessionManager) null, input, output);
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
	 * @return the nested session that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, or transcript I/O fails
	 */
	public static NestedSession serve(NestedSessionStarter starter, Reader input, Path output) throws IOException
	{
		return serve(starter, (NestedSessionManager) null, input, output);
	}

	/**
	 * Starts one session and serves JSONL messages while applying nested-session lifecycle transitions.
	 * <p>
	 * While a turn writes no response for 30 seconds, the bridge writes a {@code waiting} response with the turn's
	 * {@code quiet_seconds}, and repeats it every 30 seconds while the turn stays quiet.
	 * The session's {@link HarnessRunnerScope} supplies the clock that times the quiet turn and the heartbeat that checks
	 * it.
	 *
	 * @param starter starts the isolated persistent session
	 *
	 * @param manager the nested-session manager, or {@code null} when the caller owns lifecycle transitions
	 *
	 * @param input the JSONL client input
	 *
	 * @param output the JSONL client output
	 *
	 * @return the nested session that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, lifecycle, or stream fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	public static NestedSession serve(NestedSessionStarter starter, NestedSessionManager manager, Reader input,
		Writer output) throws IOException
	{
		return serve(starter, manager, input, output, null, null);
	}

	/**
	 * Serves a bridge with optional requested-session ownership.
	 *
	 * @param starter starts the parent session
	 * @param manager the lifecycle manager
	 * @param input the bridge input
	 * @param output the bridge output
	 * @param options the runner options, null when the caller does not serve the session as a runner
	 * @param requestedStarter starts child runners, null only when no request broker starts
	 * @return the completed parent nested session
	 * @throws IOException if the bridge or a request broker fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	private static NestedSession serve(NestedSessionStarter starter, NestedSessionManager manager, Reader input,
		Writer output, HarnessRunnerOptions options, RequestedSessionStarter requestedStarter) throws IOException
	{
		requireThat(starter, "starter").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		if (options == null || options.conversation() == null)
			return serve(starter, manager, input, output, options, requestedStarter, null);
		// The folder is checked before the session starts
		try (Conversation conversation = Conversation.open(options.conversation()))
		{
			// However the session ends, the asking session learns that no further turn_end comes
			String status = "failed";
			try
			{
				NestedSession nestedSession = serve(starter, manager, input, conversation.childWriter(output), options,
					requestedStarter, conversation);
				status = "completed";
				return nestedSession;
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
	 * @param input the bridge input when {@code conversation} is null
	 * @param output the bridge output
	 * @param options the runner options, null when the caller does not serve the session as a runner
	 * @param requestedStarter starts child runners, null only when no request broker starts
	 * @param conversation the conversation whose messages are the bridge's records, or null to read {@code input}
	 * @return the completed parent nested session
	 * @throws IOException if the bridge or a request broker fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	private static NestedSession serve(NestedSessionStarter starter, NestedSessionManager manager, Reader input,
		Writer output, HarnessRunnerOptions options, RequestedSessionStarter requestedStarter,
		Conversation conversation) throws IOException
	{
		NestedSession nestedSession = null;
		boolean completed = false;
		int turnNumber = 0;
		FutureTask<Void> activeTurn = null;
		// The thread of the last turn that started, which may still write the nested session after the turn is stopped
		Thread activeTurnThread = null;
		CountDownLatch activeTurnStarted = null;
		// Set once the client interrupts the active turn
		AtomicBoolean activeTurnInterrupted = new AtomicBoolean();
		AtomicBoolean transcriptRecorded = new AtomicBoolean();
		AtomicBoolean harnessSessionRecorded = new AtomicBoolean();
		// A nested session in a slot is fed by its server, which sends the complete record unless it ends first. A nested
		// session whose server ended before the nested session did records no end, so that its harness session can
		// continue.
		boolean recordsEnd = true;
		// A runner that resumes a nested session records no end until its harness reports the nested session's harness
		// session, so that a session that the harness cannot continue stays retained
		boolean resumes = options != null && options.resumeSlot() != null && manager != null;
		boolean tracksTurnCheckpoints = manager != null && manager.tracksTurnCheckpoints();
		// A conversation stays open after a turn fails while the harness keeps running, so that the asking session can
		// send the next turn once the cause is gone, such as a usage limit that resets
		boolean continueAfterFailedTurn = conversation != null || (options != null && options.continueAfterFailedTurn());
		// The records of a conversation come from a session that plays the user, so nothing scores each turn's reply and
		// a turn need not wait for the harness's background work, which would hold the asking session's next message back
		TurnMode turnMode;
		if (continueAfterFailedTurn)
			turnMode = TurnMode.CONVERSATION;
		else
			turnMode = TurnMode.STANDARD;
		// Why the last turn failed, if the bridge kept serving after it failed
		AtomicReference<IOException> failedTurn = new AtomicReference<>();
		boolean copiesChecked = false;
		try
		{
			try (LiveNestedSession session = starter.start();
				ClientOutput client = ClientOutput.open(session, output);
				SessionRequestBroker broker = requestBroker(session, manager, options, requestedStarter))
			{
				try
				{
					nestedSession = session.nestedSession();
					if (manager != null)
						manager.markIdle(nestedSession);
					ObjectNode started = event(session.scope(), "started");
					if (resumes)
					{
						// The resumed session's turns continue after the last turn that ended
						turnNumber = manager.turnNumber(nestedSession);
						started.put("resumed", true);
					}
					client.write(started);
					recordHarnessSession(session, manager, client, harnessSessionRecorded);
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
								failedTurn.set(null);
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
										boolean recoverableEvidence = true;
										try
										{
											if (tracksTurnCheckpoints)
												manager.recordTurnInProgress(session.nestedSession(), currentTurn);
											if (manager != null)
												manager.markWorking(session.nestedSession());
											result = session.sendTurn(prompt, harnessEvent ->
											{
												if (manager != null)
													manager.appendTurnEvent(session.nestedSession(), currentTurn, harnessEvent);
												forwardHarnessEvent(session, client, harnessEvent);
												recordSessionTranscript(session, client, transcriptRecorded);
												recordHarnessSession(session, manager, client, harnessSessionRecorded);
											}, turnMode);
											recordSessionTranscript(session, client, transcriptRecorded);
											recordHarnessSession(session, manager, client, harnessSessionRecorded);
											if (manager != null)
												manager.writeTurnOutput(session.nestedSession(), currentTurn, result);
											if (manager != null && result.terminal())
												manager.recordTurnEnded(session.nestedSession(), currentTurn);
											if (!result.completed())
												throw harnessTurnFailure(result, session.processIsAlive());
											// The requester reads the copy once it reads this turn's response
											if (options != null && options.turnCopy() != null)
											{
												Path evidence = session.nestedSession().directory();
												if (tracksTurnCheckpoints && options.sessionTranscripts() &&
													!turnInterrupted.get())
												{
													try
													{
														evidence = captureRetainedTurn(session, currentTurn);
													}
													catch (IOException _)
													{
														// The ordinary results copy can still serve this turn. Recovery
														// cannot use it as a complete prefix.
														recoverableEvidence = false;
													}
												}
												ResultsCopy.copyTurn(evidence, options.turnCopy());
											}
										}
										catch (IOException | RuntimeException e)
										{
											TerminalHarnessTurnFailure terminalFailure = terminalHarnessFailure(e);
											if (terminalFailure != null)
												persistTerminalFailure(manager, session, currentTurn, terminalFailure);
											if (broker != null)
												broker.stopChildren();
											reportTurnFailure(session, client, e);
											if (session.processIsAlive())
											{
												// The interrupt ended only this turn, and the harness serves the next one
												if (turnInterrupted.get())
												{
													if (manager != null)
													{
														if (terminalFailure == null)
															manager.recordTurnEnded(session.nestedSession(), currentTurn);
														manager.markIdle(session.nestedSession());
													}
													return null;
												}
												// The turn failed, and the harness serves the next one. The conversation's end still reports
												// the failure if no later turn replaces it.
												if (continueAfterFailedTurn)
												{
													failedTurn.set(asIoException(e));
													if (manager != null)
														manager.markIdle(session.nestedSession());
													return null;
												}
											}
											throw e;
										}
										if (manager != null)
											manager.markIdle(session.nestedSession());
										ObjectNode response = event(session.scope(), "turn_completed");
										response.put("exit_code", result.exitCode());
										response.put("error", result.standardError());
										response.put("assistant_response", result.assistantResponse());
										ArrayNode reply = response.putArray("assistant_messages");
										for (String message : result.assistantMessages())
											reply.add(message);
										if (tracksTurnCheckpoints && recoverableEvidence && !turnInterrupted.get())
										{
											String replyJson = session.scope().json().writer().without(SerializationFeature.INDENT_OUTPUT).
												writeValueAsString(response);
											manager.recordTurnCompleted(session.nestedSession(), currentTurn, replyJson);
										}
										client.endTurn(response);
										return null;
									}
									finally
									{
										client.endTurn();
									}
								});
								activeTurnThread = Thread.ofPlatform().start(activeTurn);
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
								client.write(event(session.scope(), "steered"));
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
									if (client.writeInTurn(event(session.scope(), "interrupted")))
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
								IOException lastFailure = failedTurn.get();
								if (lastFailure != null)
									throw lastFailure;
								client.write(event(session.scope(), "completed"));
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
					{
						if (options != null && options.slotId() != null)
							recordsEnd = false;
						throw new IOException("Bridge input ended without a complete message");
					}
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
				// The session has ended, so the copies are as it left them; it may not end the nested session with a result
				// that rests on changed copies
				copiesChecked = true;
				Optional<String> changes = manager.changedLinkCopies(nestedSession);
				if (changes.isPresent())
					throw new IOException(changes.get());
				manager.markCompleted(nestedSession);
			}
			return nestedSession;
		}
		catch (IOException e)
		{
			// The nested session's end must follow the turn's last write to it
			endTurn(activeTurn, activeTurnThread);
			if (manager != null && nestedSession != null && recordsEnd && (!resumes || harnessSessionRecorded.get()))
			{
				IOException failure = e;
				if (!copiesChecked)
					failure = withChangedLinkCopies(manager, nestedSession, e);
				String diagnostic = failure.getMessage();
				if (diagnostic == null)
					diagnostic = failure.getClass().getSimpleName();
				manager.fail(nestedSession, diagnostic);
				throw failure;
			}
			throw e;
		}
		finally
		{
			// The caller may remove the nested session once the bridge returns, so the turn must have stopped writing to it
			endTurn(activeTurn, activeTurnThread);
		}
	}

	/**
	 * Captures the same latest-turn evidence for the requester and any later recovery. Codex's observer uses the
	 * root native rollout as well as the runner's events.
	 *
	 * @param session the running nested session
	 * @param turn the completed turn number
	 * @return the private snapshot directory
	 * @throws IOException if the turn evidence cannot be captured
	 */
	private static Path captureRetainedTurn(LiveNestedSession session, int turn) throws IOException
	{
		Optional<String> nativeRoot = Optional.empty();
		if (session.nestedSession().harness().equals("codex"))
		{
			Path transcript = session.sessionTranscript().orElseThrow(() ->
				new IOException("The finished Codex turn has no native transcript"));
			nativeRoot = Optional.of(transcript.getFileName().toString());
		}
		return RetainedTrialPrefix.capture(session.nestedSession().directory(), turn, nativeRoot);
	}

	/**
	 * Adds to the failure of a nested session whose session has ended any change that the session made to the read-only
	 * copies of the targets of the project's links outside it, so that the nested session's diagnostic names each changed
	 * path whatever else failed.
	 *
	 * @param manager       the lifecycle manager
	 * @param nestedSession the failed nested session
	 * @param failure       why the nested session failed
	 * @return {@code failure}, or a failure that names the changed copies and is caused by {@code failure}
	 */
	private static IOException withChangedLinkCopies(NestedSessionManager manager, NestedSession nestedSession,
		IOException failure)
	{
		Optional<String> changes;
		try
		{
			changes = manager.changedLinkCopies(nestedSession);
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
		return new IOException(changes.get() + " The nested session also failed: " + reason, failure);
	}

	/**
	 * Returns the bridge's records as lines.
	 *
	 * @param session      the live harness session, whose scope paces the checks for a conversation's next message
	 * @param input        the bridge input when {@code conversation} is null
	 * @param conversation the conversation whose messages are the bridge's records, or null to read {@code input}
	 * @return the lines
	 */
	private static BufferedReader lines(LiveNestedSession session, Reader input, Conversation conversation)
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
	 * Interrupts a turn that is still running and waits for its thread to end, so that the turn no longer writes the
	 * nested session. Call it only once the session is closed, which ends a turn that waits for the harness.
	 *
	 * @param turn   the last turn that started, or {@code null} if none started
	 * @param thread the thread of {@code turn}, or {@code null} if none started
	 */
	private static void endTurn(FutureTask<Void> turn, Thread thread)
	{
		stopTurn(turn);
		if (thread == null)
			return;
		try
		{
			thread.join();
		}
		catch (InterruptedException _)
		{
			// The bridge's caller is stopping it, and the turn has already been asked to end
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Tells the client that the active turn failed, so that it stops waiting for the turn's {@code turn_completed}
	 * response. Unless the harness keeps running and the bridge keeps serving after the turn, the bridge reports the
	 * same failure again when it receives its next request.
	 *
	 * @param session the live harness session
	 * @param client  the bridge's output
	 * @param failure why the turn failed
	 */
	private static void reportTurnFailure(LiveNestedSession session, ClientOutput client, Exception failure)
	{
		assert failure != null;
		String error = failure.getMessage();
		if (error == null || error.isBlank())
			error = failure.getClass().getSimpleName();
		try
		{
			client.endTurn(event(session.scope(), "turn_failed").put("error", error));
		}
		catch (IOException e)
		{
			failure.addSuppressed(e);
		}
	}

	/**
	 * Returns a turn's failure as the {@code IOException} that the bridge reports.
	 *
	 * @param failure why the turn failed
	 * @return {@code failure} if it is an {@code IOException}; otherwise a failure that it causes
	 */
	private static IOException asIoException(Exception failure)
	{
		if (failure instanceof IOException io)
			return io;
		return new IOException("Active harness turn failed", failure);
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
	 * @param manager the nested-session manager, or {@code null} when the caller owns lifecycle transitions
	 *
	 * @param input the JSONL client input
	 *
	 * @param output the durable JSONL client-output destination
	 *
	 * @return the nested session that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, lifecycle, or transcript I/O fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	public static NestedSession serve(NestedSessionStarter starter, NestedSessionManager manager, Reader input,
		Path output) throws IOException
	{
		requireThat(output, "output").isNotNull();
		Path parent = output.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Bridge transcript has no parent directory: " + output);
		Files.createDirectories(parent);
		try (Writer transcript = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
		{
			return serve(starter, manager, input, transcript);
		}
	}

	/**
	 * Starts one managed session and directs its JSONL transcript and receipt to the destinations that a runner's
	 * options request.
	 * <p>
	 * When the options name an output file, the transcript goes there instead of to {@code output}. When they also name
	 * a receipt, the bridge writes a receipt that names the nested session's retained project and that transcript, then
	 * prunes the nested session's runtime files.
	 *
	 * @param starter starts the isolated persistent session
	 *
	 * @param manager the nested-session manager
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
	 * @return the nested session that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, lifecycle, transcript or receipt I/O fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	public static NestedSession serve(NestedSessionStarter starter, NestedSessionManager manager,
		HarnessRunnerOptions options, JsonMapper json, FileReplacer replacer, Reader input, Writer output)
		throws IOException
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
	 * @return the completed nested session
	 * @throws IOException if bridge execution or evidence publication fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	public static NestedSession serve(NestedSessionStarter starter, NestedSessionManager manager,
		HarnessRunnerOptions options, JsonMapper json, FileReplacer replacer, Reader input, Writer output,
		RequestedSessionStarter requestedStarter)
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
	 * @return the completed nested session
	 * @throws IOException if bridge execution or evidence publication fails,
	 *                     or the session changed a read-only copy of the target of a project link outside it
	 */
	private static NestedSession serveRecords(NestedSessionStarter starter, NestedSessionManager manager,
		HarnessRunnerOptions options, JsonMapper json, FileReplacer replacer, Reader input, Writer output,
		RequestedSessionStarter requestedStarter) throws IOException
	{
		if (options.output() == null)
			return serve(starter, manager, input, output, options, requestedStarter);
		NestedSession nestedSession;
		Path parent = options.output().toAbsolutePath().getParent();
		Files.createDirectories(parent);
		try (Writer transcript = Files.newBufferedWriter(options.output(), StandardCharsets.UTF_8))
		{
			nestedSession = serve(starter, manager, input, transcript, options, requestedStarter);
		}
		if (options.receipt() != null)
		{
			List<Path> sessionTranscripts = List.of();
			if (options.sessionTranscripts())
				sessionTranscripts = sessionTranscripts(nestedSession);
			NestedSessionReceipt.write(json, replacer, options.receipt(), nestedSession, options.output(),
				sessionTranscripts);
			manager.pruneRuntime(nestedSession);
		}
		return nestedSession;
	}

	/**
	 * Starts request discovery for a session whose runner gave it access to session requests, so that each runner
	 * launch that the session's commands make is answered, with a refusal when the options allow no session request.
	 *
	 * @param session the parent session
	 * @param manager the nested session's lifecycle manager, which is not null when {@code options} is not null
	 * @param options runner options, null when the caller does not serve the session as a runner
	 * @param starter starts child runners
	 * @return the started broker, or null if {@code options} is null or the session's nested session has no requests
	 *         directory
	 * @throws NullPointerException if the broker starts and {@code starter} is null
	 * @throws IOException          if the broker cannot state the nested session's limits
	 */
	private static SessionRequestBroker requestBroker(LiveNestedSession session, NestedSessionManager manager,
		HarnessRunnerOptions options, RequestedSessionStarter starter) throws IOException
	{
		if (options == null || !Files.isDirectory(SessionRequestAccess.requestsDirectory(session.nestedSession())))
			return null;
		requireThat(starter, "requestedStarter").isNotNull();
		SessionRequestBroker broker = new SessionRequestBroker(session.nestedSession(), manager, options,
			session.catPlugin().orElse(null), starter);
		try
		{
			if (options.resumeSlot() != null)
				broker.endUnanswered();
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
	 * Persists a provider-reported terminal failure without replacing that failure if persistence itself fails.
	 *
	 * @param manager the nested session manager, or null when the session is unmanaged
	 * @param session the live nested session
	 * @param turnNumber the terminal turn number
	 * @param failure the provider-reported terminal failure
	 */
	private static void persistTerminalFailure(NestedSessionManager manager, LiveNestedSession session, int turnNumber,
		TerminalHarnessTurnFailure failure)
	{
		if (manager == null)
			return;
		try
		{
			manager.writeTurnOutput(session.nestedSession(), turnNumber, failure.result());
			manager.recordTurnEnded(session.nestedSession(), turnNumber);
		}
		catch (IOException | RuntimeException persistFailure)
		{
			failure.addSuppressed(persistFailure);
		}
	}

	/**
	 * Returns whether a caught exception contains a provider-reported terminal turn result.
	 *
	 * @param failure the caught exception
	 * @return the typed terminal failure, or null when it is an ordinary exception
	 */
	private static TerminalHarnessTurnFailure terminalHarnessFailure(Exception failure)
	{
		if (failure instanceof TerminalHarnessTurnFailure terminalFailure)
			return terminalFailure;
		return null;
	}

	/**
	 * Converts a harness turn that ended without a terminal result into the bridge's caller-visible failure.
	 *
	 * @param result the completed harness turn result
	 * @param harnessRuns indicates whether the harness process is still alive
	 *
	 * @return the failure that preserves the harness exit status and safe diagnostic, or, if the harness runs, its
	 *   own description of the failure
	 */
	private static IOException harnessTurnFailure(HarnessProcessResult result, boolean harnessRuns)
	{
		assert result != null;
		// A harness that still runs names why it failed the turn, such as a usage limit, in its own words
		if (harnessRuns && !result.standardError().isBlank())
			return new IOException("The harness failed the turn and keeps running: " + result.standardError().strip());
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
	private static void forwardHarnessEvent(LiveNestedSession session, ClientOutput client, String harnessEvent)
		throws IOException
	{
		assert session != null;
		assert client != null;
		assert harnessEvent != null;
		ObjectNode response = event(session.scope(), "harness_event");
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
	private static void recordSessionTranscript(LiveNestedSession session, ClientOutput client, AtomicBoolean recorded)
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
		response.put("path", transcript.get().toString());
		client.write(response);
	}

	/**
	 * Records the identifier that the harness gave its session in the nested session's manifest, once the harness has
	 * reported it, and tells the client the identifier in a {@code session_id} record, once per session.
	 *
	 * @param session  the session
	 * @param manager  the nested session's lifecycle manager, or {@code null} if the caller does not own the nested
	 *                 session
	 * @param client   the client
	 * @param recorded indicates whether the identifier was recorded already
	 * @throws IOException if the nested session recorded another identifier, or the record cannot be written
	 */
	private static void recordHarnessSession(LiveNestedSession session, NestedSessionManager manager, ClientOutput client,
		AtomicBoolean recorded) throws IOException
	{
		assert session != null;
		assert client != null;
		assert recorded != null;
		if (recorded.get())
			return;
		Optional<String> id = session.harnessSessionId();
		if (id.isEmpty())
			return;
		// A nested session that records another session refuses this one before the session counts as recorded
		if (manager != null)
			manager.recordHarnessSession(session.nestedSession(), id.get());
		if (!recorded.compareAndSet(false, true))
			return;
		ObjectNode response = event(session.scope(), "session_id");
		response.put("session_id", id.get());
		client.write(response);
	}

	/**
	 * Lists the copies of the session transcripts that a nested session kept, in name order.
	 *
	 * @param nestedSession the completed nested session
	 *
	 * @return the copies, none if the nested session kept no directory of them
	 *
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<Path> sessionTranscripts(NestedSession nestedSession) throws IOException
	{
		assert nestedSession != null;
		Path directory = nestedSession.sessionTranscripts();
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
		 */
		private ClientOutput(HarnessRunnerScope scope, Writer output, Clock clock)
		{
			this.scope = scope;
			this.output = output;
			this.clock = clock;
		}

		/**
		 * Creates the output of a session and starts its heartbeat. The session's scope supplies the clock that
		 * measures how long a turn has been quiet and the heartbeat that runs the check.
		 *
		 * @param session the live harness session
		 * @param output  the JSONL client output
		 * @return the output
		 */
		static ClientOutput open(LiveNestedSession session, Writer output)
		{
			assert session != null;
			assert output != null;
			ClientOutput client = new ClientOutput(session.scope(), output, session.scope().clock());
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
