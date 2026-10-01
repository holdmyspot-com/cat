package io.github.cowwoc.cat.claude.optimizer;

import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
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
 *   <li>the harness failed or ended early: {@link SessionResult.Failed};</li>
 *   <li>otherwise {@link SessionResult.Completed}. A session that exceeded its time limit is completed with the
 *   replies it had, because design &sect;3.2 judges such a trial on its behavior instead of retrying it.</li>
 * </ol>
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class Session implements SessionHandle
{
	/**
	 * How often the driver wakes to check the clock, the interrupt request and the run's manifest.
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
	private static final String HARNESS = "claude";
	private final ClaudeSessions.Settings settings;
	private final SessionRequest request;
	private final Bridge bridge;
	private final Clock clock;
	private final JsonMapper json = JsonMapper.builder().build();
	private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
	private final LineFeed feed = new LineFeed();
	private final NativeEvents events = new NativeEvents(json);
	private final EventSink sink;
	private final AtomicBoolean interruptRequested = new AtomicBoolean();
	private final CompletableFuture<SessionResult> completion = new CompletableFuture<>();
	private final List<String> responses = new ArrayList<>();
	private String runId;
	private Message.Ended ended;
	private boolean timedOut;
	private boolean interruptSent;
	private boolean failureSuspected;
	private String failure;
	private Instant startupDeadline;
	private Instant limitDeadline;
	private Instant graceDeadline;

	/**
	 * Creates a session that has not started.
	 *
	 * @param settings how the runner is configured
	 * @param request what the session does
	 * @param bridge runs the runner's bridge
	 */
	Session(ClaudeSessions.Settings settings, SessionRequest request, Bridge bridge)
	{
		this.settings = settings;
		this.request = request;
		this.bridge = bridge;
		this.clock = settings.clock();
		Map<String, Path> placeholders = Map.of("source", request.source(), "artifact", settings.artifact());
		this.sink = new EventSink(json, events, harnessDirectory(), placeholders, messages);
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
	 * @throws IOException if the run directory cannot be read or cleaned
	 */
	private SessionResult drive() throws IOException
	{
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
				feed.send(turnRequest(request.turns().get(turn - 1)));
				outcome = awaitOutcome(turn);
				if (outcome != Outcome.TURN_COMPLETED)
					break;
			}
		}
		stopRunner(runner, outcome);
		return result();
	}

	/**
	 * Runs the bridge on the runner thread and reports how it ended.
	 */
	private void serve()
	{
		IOException problem = null;
		try
		{
			bridge.serve(arguments(), feed, sink);
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
			messages.add(new Message.Ended(problem));
		}
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
			if (message instanceof Message.TurnCompleted completed)
			{
				if (!timedOut)
					responses.add(completed.response());
				if (turn > 0)
					return Outcome.TURN_COMPLETED;
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
				requestStop(now, turn);
			}
			if (graceDeadline != null && now.isAfter(graceDeadline))
			{
				failure = "The runner did not stop within " + GRACE.toSeconds() + " seconds of an interrupt";
				return Outcome.STUCK;
			}
			if (turn > 0 && hasTurnFailed(turn))
				return Outcome.TURN_FAILED;
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
			feed.send("{\"type\":\"interrupt\",\"after\":\"turn-started\"}");
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
	 * @return {@code true} if CAT interrupted the session, the time limit passed, or the provider rejected a request
	 *   or the harness reported an error
	 */
	private boolean isStopping()
	{
		return interruptRequested.get() || timedOut || events.rejection().isPresent() || events.error() != null;
	}

	/**
	 * Detects a turn that ended without a terminal result.
	 * <p>
	 * The bridge records a finished turn in the run's manifest before it reports success. A turn that the manifest
	 * shows finished while the run is still working, and that stays so for a second polling interval, failed. The
	 * bridge reports that failure only when it receives its next request.
	 *
	 * @param turn the turn in progress
	 * @return {@code true} if the turn failed
	 */
	private boolean hasTurnFailed(int turn)
	{
		if (runId == null)
			return false;
		JsonNode manifest;
		try
		{
			manifest = json.readTree(runDirectory().resolve("run.json"));
		}
		catch (JacksonException _)
		{
			failureSuspected = false;
			return false;
		}
		boolean finishedWorking = manifest.path("turnNumber").asInt(0) == turn &&
			manifest.path("phase").asString("").equals("working");
		if (!finishedWorking)
		{
			failureSuspected = false;
			return false;
		}
		if (!failureSuspected)
		{
			failureSuspected = true;
			return false;
		}
		failure = "Turn " + turn + " ended without a result (exit status " + manifest.path("exitCode").asString("?") + ")";
		return true;
	}

	/**
	 * Classifies how the session ended and retains its evidence.
	 *
	 * @return the result
	 * @throws IOException if the run directory cannot be cleaned or its evidence cannot be recorded
	 */
	private SessionResult result() throws IOException
	{
		List<RateLimitWindow> windows = events.windows();
		if (ended != null)
			new HarnessRunManager(settings.runRoot(), clock).pruneTerminalRuntimes(HARNESS);
		if (interruptRequested.get())
			return new SessionResult.Interrupted(windows);
		Optional<NativeEvents.Rejection> rejection = events.rejection();
		if (rejection.isPresent())
			return new SessionResult.RateLimited(rejection.get().resetsAt(), windows);
		if (!timedOut)
		{
			if (failure == null && events.error() != null)
				failure = events.error();
			if (failure == null && responses.size() < request.turns().size())
				failure = "The session ended before every turn had a reply";
			if (failure != null)
				return new SessionResult.Failed(failure, windows);
		}
		Path artifacts = runDirectory().resolve("artifacts");
		return new SessionResult.Completed(artifacts.resolve(EventSink.TRANSCRIPT), responses,
			runDirectory().resolve("project"), events.model(), events.reasoning(), events.usage(), windows,
			sink.promptSources());
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
	 * Builds the runner's command-line options.
	 *
	 * @return the options
	 */
	private List<String> arguments()
	{
		List<String> arguments = new ArrayList<>();
		arguments.addAll(List.of("--artifact", settings.artifact().toString(), "--project",
			request.source().toString(), "--run-root", settings.runRoot().toString(), "--keep-run"));
		if (!settings.executable().isBlank())
			arguments.addAll(List.of("--executable", settings.executable()));
		if (!settings.model().isBlank())
			arguments.addAll(List.of("--model", settings.model()));
		if (!settings.effort().isBlank())
			arguments.addAll(List.of("--effort", settings.effort()));
		if (settings.installCat())
			arguments.add("--install-cat");
		for (Path evidence : request.readOnly())
			arguments.addAll(List.of("--read-only-evidence", evidence.toString()));
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
	 * Returns the directory that holds this harness's run directories.
	 *
	 * @return the directory
	 */
	private Path harnessDirectory()
	{
		return settings.runRoot().resolve(HARNESS);
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
