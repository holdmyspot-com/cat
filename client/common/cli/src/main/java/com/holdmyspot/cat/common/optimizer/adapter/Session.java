package com.holdmyspot.cat.common.optimizer.adapter;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TurnObserver;
import com.holdmyspot.cat.common.optimizer.proposal.DraftDirectory;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.LaunchRefusedException;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.RetainedTrialClaim;
import com.holdmyspot.cat.common.runner.RetainedTrialUnresumableException;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.runner.launcher.RunnerArguments;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import tools.jackson.databind.json.JsonMapper;

/**
 * One nested session. It drives a runner bridge turn by turn and classifies how the session ended.
 * <p>
 * A session has two threads. The runner thread runs the bridge, which blocks reading requests from a
 * {@link LineFeed} and writes responses to an {@link EventSink}. The driver thread holds the session's capacity
 * permit, sends one turn at a time, and decides the result.
 * <p>
 * The driver classifies the end of a session in this order:
 * <ol>
 *   <li>CAT asked for an interrupt: {@link SessionResult.Interrupted}, even if the harness then failed;</li>
 *   <li>the provider rejected a request for a rate limit: {@link SessionResult.RateLimited};</li>
 *   <li>the provider refused a request with HTTP status 403: {@link SessionResult.Refused};</li>
 *   <li>a contract author, judge, triage or prompt author session exceeded its time limit:
 *   {@link SessionResult.Failed}, because the limit says nothing about the prompt under test and the session must be
 *   retried;</li>
 *   <li>a trial session whose turn exceeded the time limit with no model output and no running hook:
 *   {@link SessionResult.Failed}, because the provider never answered the turn and the trial must be retried;</li>
 *   <li>the harness failed or ended early: {@link SessionResult.Failed};</li>
 *   <li>otherwise {@link SessionResult.Completed}. A trial session that exceeded its time limit in a turn that had
 *   model output or a running hook is completed with the replies it had, because exceeding the time limit is trial
 *   behavior to judge rather than retry.</li>
 * </ol>
 * <p>
 * A rejection or a refusal sets the harness's shared hold in its {@link HarnessCapacity}, and a turn that completes
 * without either resets the length of the next hold.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class Session implements SessionHandle
{
	/**
	 * How long the runner may take to start its harness.
	 */
	private static final Duration STARTUP_LIMIT = Duration.ofMinutes(10);
	/**
	 * How long the runner may take to stop after an interrupt or after the last turn.
	 */
	private static final Duration GRACE = Duration.ofSeconds(30);
	private final RunnerSessions sessions;
	private final RunnerSessions.Settings settings;
	private final SessionRequest request;
	private final Optional<UUID> requestId;
	private final Optional<RetainedTrialClaim> retained;
	private final Clock clock;
	/**
	 * The longest time that the driver waits for the runner's next message before it checks the clock again.
	 */
	private final Duration poll;
	private final JsonMapper json = JsonMapper.builder().build();
	private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
	private final LineFeed feed = new LineFeed();
	private final HarnessEvents events;
	private final EventSink sink;
	private final AtomicBoolean interruptRequested = new AtomicBoolean();
	private final CompletableFuture<SessionResult> completion = new CompletableFuture<>();
	private final List<List<String>> responses = new ArrayList<>();
	private volatile UnaryOperator<Path> evidencePaths = UnaryOperator.identity();
	private String sessionId;
	private Message.Ended ended;
	private boolean timedOut;
	private boolean endedEarly;
	private Map<String, Integer> settledAssertions = Map.of();
	private boolean timedOutWithoutOutput;
	private int timedOutTurn;
	private boolean interruptSent;
	private String failure;
	private Instant startupDeadline;
	private Instant limitDeadline;
	private Instant graceDeadline;

	/**
	 * Creates a session that has not started.
	 *
	 * @param sessions the port that starts the session
	 * @param request  what the session does
	 * @param poll     the longest time that the driver waits for the runner before it checks the clock again
	 */
	Session(RunnerSessions sessions, SessionRequest request, Duration poll)
	{
		this(sessions, request, poll, Optional.empty(), Optional.empty());
	}

	/**
	 * Creates a session, optionally bound to a durable trial request or retained checkpoint.
	 *
	 * @param sessions the port that starts the session
	 * @param request what the session does
	 * @param poll the longest wait for a runner message before the driver checks the clock
	 * @param requestId the original trial request identifier, or empty for an untracked session
	 * @param retained the checkpoint to resume, or empty for a fresh session
	 */
	Session(RunnerSessions sessions, SessionRequest request, Duration poll, Optional<UUID> requestId,
		Optional<RetainedTrialClaim> retained)
	{
		this.sessions = sessions;
		this.settings = sessions.settings();
		this.request = request;
		this.requestId = requestId;
		this.retained = retained;
		this.clock = settings.clock();
		this.poll = poll;
		this.events = sessions.newEvents(json);
		Map<String, Path> placeholders = Map.of("source", request.source(), "artifact", settings.artifact());
		this.sink = new EventSink(json, events, directories -> sessions.newPromptSources(json, directories),
			harnessDirectory(), placeholders, messages);
	}

	/**
	 * Starts the driver thread.
	 */
	void begin()
	{
		Thread.ofPlatform().daemon().name("optimizer-session").start(this::run);
	}

	@Override
	public CompletionStage<SessionResult> completion()
	{
		return completion.minimalCompletionStage();
	}

	@Override
	public void interrupt()
	{
		if (completion.isDone())
			return;
		interruptRequested.set(true);
		messages.add(new Message.Wake());
	}

	/**
	 * The driver thread's body. It completes {@link #completion} with the result or with the failure to determine
	 * one.
	 */
	private void run()
	{
		try
		{
			completion.complete(execute());
		}
		catch (IOException | RuntimeException e)
		{
			completion.completeExceptionally(e);
		}
	}

	/**
	 * Waits for a capacity permit, then drives the session while holding it.
	 *
	 * @return how the session ended
	 * @throws IOException if the permit, the session folder or the nested session's manifest cannot be used
	 */
	private SessionResult execute() throws IOException
	{
		Optional<HarnessCapacity.Permit> permit = settings.capacity().tryAcquire();
		while (permit.isEmpty())
		{
			if (interruptRequested.get())
				return new SessionResult.Interrupted(List.of());
			awaitMessage();
			permit = settings.capacity().tryAcquire();
		}
		try (HarnessCapacity.Permit _ = permit.get())
		{
			return drive();
		}
	}

	/**
	 * Runs the session's turns and shuts the runner down.
	 *
	 * @return how the session ended
	 * @throws IOException if the session folder cannot be read or cleaned, or the harness's hold cannot be updated
	 */
	private SessionResult drive() throws IOException
	{
		String brief = request.turns().getFirst();
		if (request.evidencePrompt().isEmpty() && (retained.isEmpty() || retained.get().completedTurns() == 0))
			brief = RoleRules.fill(settings.artifact(), request.role(), sessions.ruleVariables(settings.artifact()),
				brief);

		Thread runner = Thread.ofPlatform().daemon().name("optimizer-runner").start(this::serve);
		startupDeadline = clock.instant().plus(STARTUP_LIMIT);
		Outcome outcome = awaitOutcome(0);
		if (outcome == Outcome.STARTED)
		{
			try
			{
				limitDeadline = clock.instant().plus(request.timeLimit());
				int turn = restoredTurns();
				if (turn > 0)
					outcome = Outcome.TURN_COMPLETED;
				while (turn < request.turns().size() && !isStopping() && !endedEarly)
				{
					turn += 1;
					String prompt = request.turns().get(turn - 1);
					if (turn == 1)
					{
						if (request.evidencePrompt().isPresent())
						{
						String rendered = request.evidencePrompt().get().render(evidencePaths);
							prompt = RoleRules.fill(settings.artifact(), request.role(),
								sessions.ruleVariables(settings.artifact()), rendered);
						}
						else
							prompt = brief;
					}
					outcome = sendTurn(turn, prompt);
					if (outcome != Outcome.TURN_COMPLETED)
						break;
					if (turn < request.turns().size() && request.turnObserver().isPresent())
						observeTurn(request.turnObserver().get(), turn);
				}
				if (outcome == Outcome.TURN_COMPLETED && !isStopping() && isTurnClean() &&
					request.followUp().isPresent())
				{
					Optional<String> followUp = request.followUp().get().next(completed());
					if (followUp.isPresent())
					{
						// The follow-up turn answers the replies so far, so it gets a time limit of its own
						limitDeadline = clock.instant().plus(request.timeLimit());
						outcome = sendTurn(turn + 1, followUp.get());
					}
				}
			}
			catch (IOException | RuntimeException failure)
			{
				try
				{
					// An observer or evidence error cannot complete a tracked trial. Disconnect so the broker
					// settles the interrupted launch before a later driver decides whether to resume it.
					if (requestId.isPresent())
						stopRunner(runner, Outcome.STUCK);
					else
						stopRunner(runner, outcome);
				}
				catch (IOException | RuntimeException shutdownFailure)
				{
					if (shutdownFailure != failure)
						failure.addSuppressed(shutdownFailure);
				}
				throw failure;
			}
		}
		stopRunner(runner, outcome);
		return result();
	}

	/**
	 * Checks the prefix delivered by the broker and applies the last observer decision if the earlier driver did
	 * not persist it.
	 *
	 * @return the number of turns already completed
	 * @throws IOException if the delivered prefix or observer progress differs from the claimed checkpoint
	 */
	private int restoredTurns() throws IOException
	{
		if (retained.isEmpty())
			return 0;
		RetainedTrialClaim claim = retained.get();
		int turns = claim.completedTurns();
		if (!claim.identity().session().orElseThrow().sessionId().equals(sessionId) || responses.size() != turns)
			throw new IOException("The retained trial did not deliver its claimed checkpoint");
		if (request.turnObserver().isPresent())
		{
			TurnObserver observer = request.turnObserver().get();
			TurnObserver.Progress progress = observer.progress();
			if (progress.completedTurn() == turns)
			{
				endedEarly = progress.endedEarly();
				if (endedEarly)
					settledAssertions = progress.settledAssertions();
			}
			else if (progress.completedTurn() != turns - 1 || progress.endedEarly())
				throw new IOException("The retained trial's observer progress differs from its checkpoint");
			else if (turns < request.turns().size())
				observeTurn(observer, turns);
		}
		return turns;
	}

	/**
	 * Applies and remembers an observer's decision about a completed non-final turn.
	 *
	 * @param observer the trial's observer
	 * @param turn the completed turn's number
	 * @throws IOException if its evidence or decision cannot be recorded
	 */
	private void observeTurn(TurnObserver observer, int turn) throws IOException
	{
		if (observer.endAfter(turn, completed()))
		{
			endedEarly = true;
			settledAssertions = observer.settledAssertions();
		}
	}

	/**
	 * Sends one turn and waits for it to end.
	 *
	 * @param turn the turn's number, starting at 1
	 * @param prompt the turn's prompt
	 * @return how the wait ended
	 * @throws IOException if the harness's hold cannot be updated
	 */
	private Outcome sendTurn(int turn, String prompt) throws IOException
	{
		events.startTurn();
		feed.send(turnRequest(prompt));
		Outcome outcome = awaitOutcome(turn);
		if (outcome == Outcome.TURN_COMPLETED && isTurnClean())
			settings.capacity().completeTurn();
		return outcome;
	}

	/**
	 * Indicates whether the provider neither rejected nor refused the session and the harness reported no error.
	 *
	 * @return {@code true} if the session is clean
	 */
	private boolean isTurnClean()
	{
		return events.rejection().isEmpty() && !events.refused() && events.error() == null;
	}

	/**
	 * Runs the bridge on the runner thread and reports how it ended.
	 */
	private void serve()
	{
		IOException problem = null;
		Path workspace = null;
		Path permissions = null;
		// Closing the sink releases the nested session's transcript
		try (sink)
		{
			if (retained.isEmpty())
				workspace = writeWorkspace();
			permissions = writeExtraPermissions();
			List<String> options = arguments(workspace, permissions);
			if (requestId.isPresent())
				sessions.bridge().serveTrial(requestId.get(), retained, request.source(), options, feed, sink);
			else if (request.evidencePrompt().isPresent())
				sessions.bridge().serveWithEvidence(options, request.readOnly(), paths -> evidencePaths = paths, feed, sink);
			else
				sessions.bridge().serve(options, feed, sink);
		}
		catch (IOException e)
		{
			problem = e;
		}
		catch (RuntimeException e)
		{
			problem = new IOException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
		}
		finally
		{
			List<Path> directories = new ArrayList<>();
			if (workspace != null)
				directories.add(workspace);
			if (permissions != null)
				directories.add(permissions.getParent());
			for (Path directory : directories)
			{
				try
				{
					TemporaryDirectories.deleteRecursively(directory);
				}
				catch (IOException e)
				{
					if (problem == null)
						problem = e;
				}
			}
			messages.add(new Message.Ended(problem));
		}
	}

	/**
	 * Writes the files that the session's workspace receives, a trial's workspace files or the contract author's draft
	 * directory, to a new directory under the results folder of the session's part, so that the workspace files of a
	 * held-out test case stay in a held-out location.
	 *
	 * @return the directory, or {@code null} if the session receives no files
	 * @throws IOException if the files cannot be written
	 */
	private Path writeWorkspace() throws IOException
	{
		if (request.files().isEmpty())
			return null;
		Path directory = Files.createDirectories(resultsFolder().resolve("workspaces")).resolve(
			UUID.randomUUID().toString());
		Files.createDirectory(directory);
		try
		{
			request.files().write(directory);
		}
		catch (IOException | RuntimeException e)
		{
			TemporaryDirectories.deleteRecursively(directory);
			throw e;
		}
		return directory;
	}

	/**
	 * Writes the file that lists the request's extra permissions, in a new directory under the results folder of the
	 * session's part.
	 *
	 * @return the file, or {@code null} if the request asks for no extra permissions
	 * @throws IOException if the file cannot be written
	 */
	private Path writeExtraPermissions() throws IOException
	{
		if (request.extraPermissions().isEmpty())
			return null;
		Path directory = Files.createDirectories(resultsFolder().resolve("extra-permissions")).
			resolve(UUID.randomUUID().toString());
		Files.createDirectory(directory);
		Path file = directory.resolve(ExtraPermissions.FILE_NAME);
		try
		{
			request.extraPermissions().write(json, file);
		}
		catch (IOException | RuntimeException e)
		{
			TemporaryDirectories.deleteRecursively(directory);
			throw e;
		}
		return file;
	}

	/**
	 * Asks the runner to finish and waits for it, interrupting its thread if it does not stop in time.
	 *
	 * @param runner the runner thread
	 * @param outcome how the last wait ended
	 * @throws IOException if the wait is interrupted
	 */
	private void stopRunner(Thread runner, Outcome outcome) throws IOException
	{
		if (ended == null && outcome != Outcome.STUCK && outcome != Outcome.STARTUP_TIMEOUT)
		{
			// Every successful stop, including settlement between turns, completes the bridge protocol. EOF alone
			// means the requester disappeared and makes the bridge fail the session.
			feed.send("{\"type\":\"complete\"}");
			feed.end();
			graceDeadline = clock.instant().plus(GRACE);
			while (ended == null && !clock.instant().isAfter(graceDeadline))
				awaitMessage();
			if (ended != null && ended.failure() != null && failure == null)
				failure = describe(ended.failure(), "The runner failed");
		}
		if (ended == null)
		{
			if (failure == null)
				failure = "The runner did not stop within " + GRACE.toSeconds() + " seconds";
			runner.interrupt();
			try
			{
				runner.join(GRACE.toMillis());
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while stopping the runner", e);
			}
		}
	}

	/**
	 * Waits until the runner reports progress that the driver needs, applying the interrupt request, the time limit
	 * and the failure checks while it waits.
	 *
	 * @param turn the turn to wait for, or 0 to wait for the runner to start
	 * @return what happened
	 * @throws IOException if the wait is interrupted
	 */
	private Outcome awaitOutcome(int turn) throws IOException
	{
		while (true)
		{
			Message message = awaitMessage();
			if (message instanceof Message.Started && turn == 0)
				return Outcome.STARTED;
			if (message instanceof Message.Identified identified)
				sessionId = identified.sessionId();
			if (message instanceof TurnCompleted completed)
			{
				if (sessionId == null)
				{
					if (failure == null)
						failure = "The runner completed turn " + turn + " before it reported the session's ID";
					return Outcome.TURN_FAILED;
				}
				if (!timedOut)
					responses.add(completed.assistantMessages());
				if (turn > 0)
					return Outcome.TURN_COMPLETED;
			}
			if (message instanceof Message.TurnFailed turnFailed && turn > 0)
			{
				if (failure == null)
					failure = "Turn " + turn + " failed: " + turnFailed.error();
				return Outcome.TURN_FAILED;
			}
			if (message instanceof Message.Ended)
			{
				failure = describe(ended.failure(), "The runner ended before the session finished");
				return Outcome.ENDED;
			}
			Instant now = clock.instant();
			if (interruptRequested.get() && !interruptSent)
				requestStop(now, turn);
			if (turn == 0 && now.isAfter(startupDeadline))
			{
				failure = "The harness did not start within " + STARTUP_LIMIT.toMinutes() + " minutes";
				return Outcome.STARTUP_TIMEOUT;
			}
			if (turn > 0 && !timedOut && now.isAfter(limitDeadline))
			{
				timedOut = true;
				timedOutTurn = turn;
				timedOutWithoutOutput = !events.outputProduced() && !events.hookRunning();
				requestStop(now, turn);
			}
			if (graceDeadline != null && now.isAfter(graceDeadline))
			{
				failure = "The runner did not stop within " + GRACE.toSeconds() + " seconds of an interrupt";
				return Outcome.STUCK;
			}
		}
	}

	/**
	 * Interrupts the turn in progress and starts the clock on the runner's reply.
	 *
	 * @param now the current time
	 * @param turn the turn in progress, or 0 if the harness has not started
	 */
	private void requestStop(Instant now, int turn)
	{
		interruptSent = true;
		graceDeadline = now.plus(GRACE);
		// A request served through a nested session's runner refuses an interrupt record that holds anything but its type
		if (turn > 0)
			feed.send("{\"type\":\"interrupt\"}");
	}

	/**
	 * Waits for the runner's next message.
	 *
	 * @return the message, or {@code null} if none arrived within the polling interval
	 * @throws IOException if the wait is interrupted
	 */
	private Message awaitMessage() throws IOException
	{
		Message message;
		try
		{
			message = messages.poll(poll.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the runner", e);
		}
		if (message instanceof Message.Ended end)
			ended = end;
		return message;
	}

	/**
	 * Indicates whether the driver should send no further turn.
	 *
	 * @return {@code true} if CAT interrupted the session, the time limit passed, the provider rejected or refused a
	 *   request, or the harness reported an error
	 */
	private boolean isStopping()
	{
		return interruptRequested.get() || timedOut || events.rejection().isPresent() || events.refused() ||
			events.error() != null;
	}

	/**
	 * Classifies how the session ended and retains its evidence, recording a rejection or refusal in the harness's
	 * shared hold. A session that was refused before it started ends as {@link SessionResult.LaunchRefused}.
	 *
	 * @return the result
	 * @throws IOException if the session folder cannot be cleaned, its evidence cannot be recorded, or the hold cannot
	 *   be updated
	 */
	private SessionResult result() throws IOException
	{
		List<RateLimitWindow> windows = events.windows();
		if (ended != null)
			new NestedSessionManager(settings.scope().fileLocks(), resultsFolder(),
				clock, settings.replacer()).pruneTerminalRuntimes(sessions.harness());
		if (interruptRequested.get())
			return new SessionResult.Interrupted(windows);
		if (ended != null && ended.failure() instanceof RetainedTrialUnresumableException unresumable)
			throw unresumable;
		if (ended != null && ended.failure() instanceof LaunchRefusedException refused)
		{
			return new SessionResult.LaunchRefused(describe(refused, "The session was refused before it started"),
				windows);
		}
		Optional<HarnessEvents.Rejection> rejection = events.rejection();
		if (rejection.isPresent())
			return new SessionResult.RateLimited(settings.capacity().reject(rejection.get().resetsAt()), windows);
		if (events.refused())
		{
			HarnessCapacity.Refusal refusal = settings.capacity().refuse();
			return new SessionResult.Refused(refusal.hold(), refusal.afterLongestHold(), windows);
		}
		if (timedOut && request.role() != SessionRole.TRIAL)
		{
			String role = request.role().name().toLowerCase(Locale.ROOT).replace('_', ' ');
			return new SessionResult.Failed("The " + role + " session reached its " +
				request.timeLimit().toMinutes() + "-minute time limit", windows);
		}
		if (timedOut && timedOutWithoutOutput)
		{
			return new SessionResult.Failed("The trial's turn " + timedOutTurn + " reached its " +
				request.timeLimit().toMinutes() + "-minute time limit with no model output", windows);
		}
		if (!timedOut)
		{
			if (failure == null && events.error() != null)
				failure = events.error();
			if (failure == null && responses.size() < request.turns().size() && !endedEarly)
				failure = "The session ended before every turn had a reply";
			if (failure != null)
				return new SessionResult.Failed(failure, windows);
		}
		if (sessionId == null)
			return new SessionResult.Failed("The runner did not report the session's ID", windows);
		return completed();
	}

	/**
	 * Returns the session's replies and effects so far as a completed result.
	 *
	 * @return the result
	 */
	private SessionResult.Completed completed()
	{
		Path artifacts = sessionFolder().resolve("artifacts");
		return new SessionResult.Completed(artifacts.resolve(EventSink.TRANSCRIPT), responses,
			sessionFolder().resolve("project"), sessionFolder().resolve("initial-project"), events.model(),
			events.reasoning(), events.usage(), events.windows(), sink.promptSources(),
			events.toolCalls(), Math.max(request.turns().size(), responses.size()), endedEarly, settledAssertions);
	}

	/**
	 * Describes why the runner failed.
	 *
	 * @param problem the failure, or {@code null} if the runner returned normally
	 * @param fallback the description to use if {@code problem} has no message
	 * @return the description
	 */
	private static String describe(IOException problem, String fallback)
	{
		if (problem == null || problem.getMessage() == null || problem.getMessage().isBlank())
			return fallback;
		return problem.getMessage();
	}

	/**
	 * Builds the runner's command-line options. A trial asks for session transcripts and installs exactly the request's
	 * plugins: the first one whose manifest states CAT's repository is installed as CAT, and each other one as a plugin.
	 * Other sessions install no plugin. A trial on the control source adds the harness's control-source options.
	 * Every session is denied reading the host session's project, except its own nested session and the evidence that it
	 * reads, and a session of the development part is also denied reading the held-out locations. A session whose request
	 * asks for extra permissions names the file that lists them.
	 *
	 * @param workspace the directory that holds the trial's workspace files, or {@code null} if there are none
	 * @param permissions the file that lists the request's extra permissions, or {@code null} if it asks for none
	 * @return the options
	 * @throws IOException if the held-out locations cannot be created or listed
	 */
	private List<String> arguments(Path workspace, Path permissions) throws IOException
	{
		if (retained.isPresent())
			return resumeArguments(retained.get(), permissions);
		List<String> arguments = new ArrayList<>();
		Path artifact = settings.artifact();
		boolean installCat = false;
		for (TrialPluginInput plugin : request.pluginInputs())
		{
			if (!installCat && sessions.isCat(plugin.path()))
			{
				artifact = plugin.path();
				installCat = true;
			}
			else
				plugin.appendArguments(arguments);
		}
		arguments.addAll(List.of("--artifact", artifact.toString(), "--project", request.source().toString(),
			"--results", resultsFolder().toString()));
		// Only a trial's transcripts are read, to learn what its agents received
		if (request.role() == SessionRole.TRIAL)
			arguments.add("--session-transcripts");
		if (request.role() == SessionRole.CONTRACT_AUTHOR)
			arguments.addAll(List.of(RunnerArguments.RESULT_PROJECT_LINKS_OPTION, DraftDirectory.name(request.source())));
		if (request.part() == Part.HELD_OUT)
			arguments.add(RunnerArguments.HELD_OUT_OPTION);
		TrialProfile profile = request.profile();
		arguments.addAll(List.of("--model", profile.model(), "--effort", profile.effort()));
		if (profile.toolOutputTokenLimit() > 0)
			arguments.addAll(List.of("--tool-output-token-limit", String.valueOf(profile.toolOutputTokenLimit())));
		if (installCat)
			arguments.add("--install-cat");
		if (request.controlSource())
			arguments.addAll(sessions.controlSourceArguments());
		if (workspace != null)
			arguments.addAll(List.of("--workspace", workspace.toString()));
		if (permissions != null)
			arguments.addAll(List.of(ExtraPermissions.OPTION, permissions.toString()));
		arguments.addAll(sessions.readOnlyArguments(request.readOnly()));
		arguments.addAll(sessions.roleArguments(request.role()));
		List<Path> heldOut = sessions.heldOutLocations();
		if (request.part() == Part.DEVELOPMENT)
		{
			for (Path path : heldOut)
				arguments.addAll(List.of("--deny-read", path.toString()));
		}
		Path hostProject = settings.hostProject();
		if (hostProject != null)
		{
			// The results folder lies inside the host project, so the runner denies every file of the project except the
			// session's own nested session and evidence
			arguments.addAll(List.of("--deny-read", hostProject.toString()));
		}
		return arguments;
	}

	/**
	 * Builds the options that can be supplied when resuming, retaining the original session's fixed options.
	 *
	 * @param claim the previously inspected checkpoint
	 * @param permissions the current extra permissions file, or null
	 * @return the resume options
	 */
	private List<String> resumeArguments(RetainedTrialClaim claim, Path permissions)
	{
		String retainedSession = claim.identity().session().orElseThrow().sessionId();
		TrialProfile profile = request.profile();
		List<String> options = new ArrayList<>(List.of(RunnerArguments.RESUME_OPTION, retainedSession,
			"--results", resultsFolder().toString(), "--model", profile.model(), "--effort", profile.effort(),
			"--session-transcripts"));
		if (request.part() == Part.HELD_OUT)
			options.add(RunnerArguments.HELD_OUT_OPTION);
		if (permissions != null)
			options.addAll(List.of(ExtraPermissions.OPTION, permissions.toString()));
		return options;
	}

	/**
	 * Builds the request that sends one turn.
	 *
	 * @param prompt the turn's prompt
	 * @return the request line
	 */
	private String turnRequest(String prompt)
	{
		return json.createObjectNode().put("type", "turn").put("prompt", prompt).toString();
	}

	/**
	 * Returns the directory that holds this session's session folder: the held-out results folder for a session of the
	 * held-out part, and the results folder otherwise.
	 *
	 * @return the directory
	 */
	private Path resultsFolder()
	{
		if (request.part() == Part.HELD_OUT)
			return settings.heldOutResultsFolder();
		return settings.resultsFolder();
	}

	/**
	 * Returns the directory that holds this harness's session folders.
	 *
	 * @return the directory
	 */
	private Path harnessDirectory()
	{
		return resultsFolder().resolve(sessions.harness());
	}

	/**
	 * Returns the directory of this session's results, which the identifier that the harness gave the session names.
	 *
	 * @return the directory
	 */
	private Path sessionFolder()
	{
		return harnessDirectory().resolve(sessionId);
	}

	/**
	 * What the driver was waiting for.
	 */
	private enum Outcome
	{
		/**
		 * The runner started its harness.
		 */
		STARTED,
		/**
		 * The runner reported the end of the turn.
		 */
		TURN_COMPLETED,
		/**
		 * The turn ended without a result.
		 */
		TURN_FAILED,
		/**
		 * The runner returned or failed.
		 */
		ENDED,
		/**
		 * The runner did not start in time.
		 */
		STARTUP_TIMEOUT,
		/**
		 * The runner did not respond to an interrupt in time.
		 */
		STUCK
	}
}
