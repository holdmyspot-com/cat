package io.github.cowwoc.cat.common.optimizer.adapter;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;

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
	 * How often the driver wakes to check the clock and the interrupt request.
	 */
	private static final Duration POLL = Duration.ofMillis(250);
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
	private final Clock clock;
	private final JsonMapper json = JsonMapper.builder().build();
	private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
	private final LineFeed feed = new LineFeed();
	private final HarnessEvents events;
	private final EventSink sink;
	private final AtomicBoolean interruptRequested = new AtomicBoolean();
	private final CompletableFuture<SessionResult> completion = new CompletableFuture<>();
	private final List<List<String>> responses = new ArrayList<>();
	private String runId;
	private Message.Ended ended;
	private boolean timedOut;
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
	 * @param request what the session does
	 */
	Session(RunnerSessions sessions, SessionRequest request)
	{
		this.sessions = sessions;
		this.settings = sessions.settings();
		this.request = request;
		this.clock = settings.clock();
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
	 * @throws IOException if the permit, the run directory or the run's manifest cannot be used
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
	 * @throws IOException if the run directory cannot be read or cleaned, or the harness's hold cannot be updated
	 */
	private SessionResult drive() throws IOException
	{
		String brief = RoleRules.fill(settings.artifact(), request.role(), request.turns().getFirst());

		Thread runner = Thread.ofPlatform().daemon().name("optimizer-runner").start(this::serve);
		startupDeadline = clock.instant().plus(STARTUP_LIMIT);
		Outcome outcome = awaitOutcome(0);
		if (outcome == Outcome.STARTED)
		{
			limitDeadline = clock.instant().plus(request.timeLimit());
			int turn = 0;
			while (turn < request.turns().size() && !isStopping())
			{
				turn += 1;
				String prompt = request.turns().get(turn - 1);
				if (turn == 1)
					prompt = brief;
				outcome = sendTurn(turn, prompt);
				if (outcome != Outcome.TURN_COMPLETED)
					break;
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
		stopRunner(runner, outcome);
		return result();
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
		// Closing the sink releases the run's transcript
		try (sink)
		{
			workspace = writeWorkspace();
			sessions.bridge().serve(arguments(workspace), feed, sink);
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
			if (workspace != null)
			{
				try
				{
					TemporaryDirectories.deleteRecursively(workspace);
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
	 * directory, to a new directory under the run root of the session's part, so that the workspace files of a held-out
	 * test case stay in a held-out location.
	 *
	 * @return the directory, or {@code null} if the session receives no files
	 * @throws IOException if the files cannot be written
	 */
	private Path writeWorkspace() throws IOException
	{
		if (request.files().isEmpty())
			return null;
		Path directory = Files.createDirectories(runRoot().resolve("workspaces")).resolve(UUID.randomUUID().toString());
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
			// Asking for completion also makes the runner report a turn that failed without a result.
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
			if (message instanceof Message.Started started)
			{
				runId = started.runId();
				if (turn == 0)
					return Outcome.STARTED;
			}
			if (message instanceof TurnCompleted completed)
			{
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
		if (turn > 0)
			feed.send("{\"type\":\"interrupt\",\"after\":\"turn_started\"}");
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
			message = messages.poll(POLL.toMillis(), TimeUnit.MILLISECONDS);
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
	 * shared hold.
	 *
	 * @return the result
	 * @throws IOException if the run directory cannot be cleaned, its evidence cannot be recorded, or the hold cannot
	 *   be updated
	 */
	private SessionResult result() throws IOException
	{
		List<RateLimitWindow> windows = events.windows();
		if (ended != null)
			new HarnessRunManager(runRoot(), clock).pruneTerminalRuntimes(sessions.harness());
		if (interruptRequested.get())
			return new SessionResult.Interrupted(windows);
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
			if (failure == null && responses.size() < request.turns().size())
				failure = "The session ended before every turn had a reply";
			if (failure != null)
				return new SessionResult.Failed(failure, windows);
		}
		return completed();
	}

	/**
	 * Returns the session's replies and effects so far as a completed result.
	 *
	 * @return the result
	 */
	private SessionResult.Completed completed()
	{
		Path artifacts = runDirectory().resolve("artifacts");
		return new SessionResult.Completed(artifacts.resolve(EventSink.TRANSCRIPT), responses,
			runDirectory().resolve("project"), runDirectory().resolve("initial-project"), events.model(),
			events.reasoning(), events.usage(), events.windows(), sink.promptSources(), events.toolCalls());
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
	 * Builds the runner's command-line options. A trial installs exactly the request's plugins: the first one whose
	 * manifest name is CAT's is installed as CAT, and each other one as a plugin. Other sessions install no plugin.
	 *
	 * @param workspace the directory that holds the trial's workspace files, or {@code null} if there are none
	 * @return the options
	 * @throws IOException if the held-out locations cannot be created or listed
	 */
	private List<String> arguments(Path workspace) throws IOException
	{
		List<String> arguments = new ArrayList<>();
		Path artifact = settings.artifact();
		boolean installCat = false;
		for (Path plugin : request.plugins())
		{
			if (!installCat && sessions.isCat(plugin))
			{
				artifact = plugin;
				installCat = true;
			}
			else
				arguments.addAll(List.of("--plugin", plugin.toString()));
		}
		arguments.addAll(List.of("--artifact", artifact.toString(), "--project", request.source().toString(),
			"--run-root", runRoot().toString(), "--keep-run"));
		if (!settings.executable().isBlank())
			arguments.addAll(List.of("--executable", settings.executable()));
		TrialProfile profile = request.profile();
		if (!profile.model().isEmpty())
			arguments.addAll(List.of("--model", profile.model()));
		if (!profile.effort().isEmpty())
			arguments.addAll(List.of("--effort", profile.effort()));
		if (profile.toolOutputTokenLimit() > 0)
			arguments.addAll(List.of("--tool-output-token-limit", String.valueOf(profile.toolOutputTokenLimit())));
		if (installCat)
			arguments.add("--install-cat");
		if (workspace != null)
			arguments.addAll(List.of("--workspace", workspace.toString()));
		arguments.addAll(sessions.readOnlyArguments(request.readOnly()));
		List<Path> heldOut = sessions.heldOutLocations();
		if (request.part() == Part.DEVELOPMENT)
		{
			for (Path path : heldOut)
				arguments.addAll(List.of("--deny-read", path.toString()));
		}
		return arguments;
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
	 * Returns the directory that holds this session's run directory: the held-out run root for a session of the
	 * held-out part, and the run root otherwise.
	 *
	 * @return the directory
	 */
	private Path runRoot()
	{
		if (request.part() == Part.HELD_OUT)
			return settings.heldOutRunRoot();
		return settings.runRoot();
	}

	/**
	 * Returns the directory that holds this harness's run directories.
	 *
	 * @return the directory
	 */
	private Path harnessDirectory()
	{
		return runRoot().resolve(sessions.harness());
	}

	/**
	 * Returns the run directory of this session.
	 *
	 * @return the directory
	 */
	private Path runDirectory()
	{
		return harnessDirectory().resolve(runId);
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
