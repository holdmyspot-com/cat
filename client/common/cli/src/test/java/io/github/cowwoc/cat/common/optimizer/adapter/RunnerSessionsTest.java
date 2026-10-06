package io.github.cowwoc.cat.common.optimizer.adapter;

import static io.github.cowwoc.cat.common.optimizer.adapter.fakes.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.fakes.ManualClock;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.adapter.fakes.RoleRuleFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunnerBridge;
import io.github.cowwoc.cat.common.runner.fakes.ScriptedHarness;
import io.github.cowwoc.cat.common.runner.fakes.ScriptedHarness.Turn;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link RunnerSessions} over the real runner bridge with a scripted harness.
 * <p>
 * Blocking turns supply the interrupted terminal completion documented by codex-cli 0.147.0's app-server README,
 * as cited by ScriptedHarness. They test CAT's classification and permit lifetime given that input, not the native
 * harness's reaction. The direct bridge fixture supplies the CAT-owned JSONL protocol documented by
 * {@link HarnessRunnerBridge}.
 */
public final class RunnerSessionsTest
{
	private static final Duration LIMIT = Duration.ofMinutes(5);
	private static final Path TEMP = Path.of(System.getProperty("cat.work.temp"));
	private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");

	/**
	 * Creates the test.
	 */
	public RunnerSessionsTest()
	{
	}

	/**
	 * Creates a port whose runner plays a script.
	 *
	 * @param runRoot the directory for run directories
	 * @param capacity the shared capacity
	 * @param clock the clock for time limits
	 * @param arguments receives the runner's options for each session
	 * @param script what the harness does, turn by turn
	 * @return the port
	 */
	private static TestSessions port(Path runRoot, HarnessCapacity capacity, Clock clock,
		List<List<String>> arguments, List<Turn> script)
	{
		return port(runRoot, runRoot, capacity, clock, arguments, script);
	}

	/**
	 * Creates a port whose runner plays a script.
	 *
	 * @param artifact the installed artifact that supplies the roles' rules
	 * @param runRoot the directory for run directories
	 * @param capacity the shared capacity
	 * @param clock the clock for time limits
	 * @param arguments receives the runner's options for each session
	 * @param script what the harness does, turn by turn
	 * @return the port
	 */
	private static TestSessions port(Path artifact, Path runRoot, HarnessCapacity capacity, Clock clock,
		List<List<String>> arguments, List<Turn> script)
	{
		RunnerSessions.Settings settings = new RunnerSessions.Settings(artifact, runRoot, List::of, "", false,
			Map.of(), runRoot, capacity, clock);
		return new TestSessions(settings, (options, input, output) ->
		{
			arguments.add(options);
			ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), TestSessions.HARNESS,
				script, input, output);
		});
	}

	/**
	 * Creates a request.
	 *
	 * @param source the directory that the session works in
	 * @param readOnly the evidence that the session may read
	 * @param turns the turns
	 * @return the request
	 */
	private static SessionRequest request(Path source, List<Path> readOnly, String... turns)
	{
		return request(TrialProfile.HARNESS_DEFAULT, source, readOnly, turns);
	}

	/**
	 * Creates a request under a profile.
	 *
	 * @param profile the model and reasoning effort that the session starts under
	 * @param source the directory that the session works in
	 * @param readOnly the evidence that the session may read
	 * @param turns the turns
	 * @return the request
	 */
	private static SessionRequest request(TrialProfile profile, Path source, List<Path> readOnly, String... turns)
	{
		return request(SessionRole.TRIAL, profile, source, readOnly, turns);
	}

	/**
	 * Creates a request for a role.
	 *
	 * @param role the kind of work that the session performs
	 * @param profile the model and reasoning effort that the session starts under
	 * @param source the directory that the session works in
	 * @param readOnly the evidence that the session may read
	 * @param turns the turns
	 * @return the request
	 */
	private static SessionRequest request(SessionRole role, TrialProfile profile, Path source, List<Path> readOnly,
		String... turns)
	{
		return new SessionRequest(role, partOf(role), source, List.of(turns), readOnly, LIMIT, profile);
	}

	/**
	 * Waits for a session's result.
	 *
	 * @param handle the session
	 * @return the result
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session could not determine a result
	 * @throws TimeoutException if the session does not end
	 */
	private static SessionResult await(SessionHandle handle)
		throws InterruptedException, ExecutionException, TimeoutException
	{
		return handle.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
	}

	/**
	 * Verifies that the port declares the run root and the held-out run root as the directories that hold its runs.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void declaresRunRoots() throws IOException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path runRoot = root.resolve("runs").normalize();
			TestSessions sessions = port(runRoot, HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of());
			Path heldOutRunRoot = root.resolve("runs-held_out").normalize();
			requireThat(sessions.runRoots(), "runRoots").isEqualTo(List.of(runRoot, heldOutRunRoot));
		}
	}

	/**
	 * Verifies that CAT passes bound model and reasoning-effort dimensions to the runner and omits the options for
	 * unbound dimensions.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void passesTheRequestedProfile()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E17
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			TestSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				arguments, List.of(Turn.replies("ok")));

			await(sessions.start(request(new TrialProfile("model-a", "high"), source, List.of(), "complete")));
			await(sessions.start(request(new TrialProfile("model-b", ""), source, List.of(), "partial")));
			await(sessions.start(request(new TrialProfile("", "low"), source, List.of(), "effortOnly")));

			requireThat(arguments.size(), "arguments.size()").isEqualTo(3);
			List<String> complete = arguments.get(0);
			requireThat(ScriptedHarness.optionValue(complete, "--model"), "completeModel").isEqualTo("model-a");
			requireThat(ScriptedHarness.optionValue(complete, "--effort"), "completeEffort").isEqualTo("high");
			List<String> partial = arguments.get(1);
			requireThat(ScriptedHarness.optionValue(partial, "--model"), "partialModel").isEqualTo("model-b");
			requireThat(partial.contains("--effort"), "partialEffort").isFalse();
			List<String> effortOnly = arguments.get(2);
			requireThat(effortOnly.contains("--model"), "effortOnlyModel").isFalse();
			requireThat(ScriptedHarness.optionValue(effortOnly, "--effort"), "effortOnlyEffort").isEqualTo("low");
		}
	}

	/**
	 * Verifies every turn retains its ordered messages, independently of the bridge's final-response field.
	 *
	 * @throws IOException if the run directory cannot be created
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	// Requirement: R-C12
	@Test
	public void retainsMessagesForEachTurn()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "", false,
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
				HarnessRun run = manager.create(TestSessions.HARNESS);
				manager.initialize(run, root, root, "scripted", List.of("scripted"));
				output.write("{\"type\":\"started\",\"run_id\":\"" + run.id() + "\"}\n");
				BufferedReader requests = new BufferedReader(input);
				for (int turn = 0; turn < 2; ++turn)
				{
					requests.readLine();
					output.write("{\"type\":\"turn_completed\",\"assistant_response\":\"final-only\"," +
						"\"assistant_messages\":[\"interim-" + turn + "\",\"final-" + turn + "\"]}\n");
				}
				requests.readLine();
			});

			SessionResult result = await(sessions.start(request(root, List.of(), "one", "two")));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			requireThat(((SessionResult.Completed) result).responses(), "responses").isEqualTo(
				List.of(List.of("interim-0", "final-0"), List.of("interim-1", "final-1")));
		}
	}

	/**
	 * Verifies that CAT sends the interrupt request with the bridge's {@code turn_started} control point and classifies
	 * the supplied terminal completion as Interrupted. This observes CAT's outgoing request, not native harness timing.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void sendsInterruptAfterTurnStart()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			Path runs = root.resolve("runs");
			List<String> requests = new CopyOnWriteArrayList<>();
			CountDownLatch turnReceived = new CountDownLatch(1);
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, runs, List::of, "", false, Map.of(), root,
				HarnessCapacity.unlimited(), Clock.systemUTC());
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				HarnessRunManager manager = new HarnessRunManager(runs, Clock.systemUTC());
				HarnessRun run = manager.create(TestSessions.HARNESS);
				manager.initialize(run, root, root, "scripted", List.of("scripted"));
				output.write("{\"type\":\"started\",\"run_id\":\"" + run.id() + "\"}\n");
				output.flush();
				BufferedReader lines = new BufferedReader(input);
				String line = lines.readLine();
				while (line != null)
				{
					requests.add(line);
					if (line.contains("\"type\":\"turn\""))
						turnReceived.countDown();
					if (line.contains("\"type\":\"interrupt\""))
					{
						output.write("{\"type\":\"turn_completed\",\"assistant_messages\":[\"stopped\"]}\n");
						output.flush();
					}
					line = lines.readLine();
				}
			});
			SessionHandle handle = sessions.start(request(source, List.of(), "one"));
			requireThat(turnReceived.await(60, TimeUnit.SECONDS), "turnReceived").isTrue();

			handle.interrupt();

			requireThat(await(handle), "result").isInstanceOf(SessionResult.Interrupted.class);
			requireThat(requests, "requests").contains("{\"type\":\"interrupt\",\"after\":\"turn_started\"}");
		}
	}



	/**
	 * Verifies that an interrupt that arrives while the session waits for capacity ends it without starting a
	 * runner.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void interruptsWhileWaitingForCapacity()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			HarnessCapacity capacity = HarnessCapacity.of(root.resolve("locks"), 1, () ->
			{
			});
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			TestSessions sessions = port(root.resolve("runs"), capacity, Clock.systemUTC(), arguments,
				List.of(Turn.replies("never")));
			try (HarnessCapacity.Permit _ = capacity.acquire())
			{
				SessionHandle handle = sessions.start(request(source, List.of(), "one"));

				handle.interrupt();

				requireThat(await(handle), "result").isEqualTo(new SessionResult.Interrupted(List.of()));
				requireThat(arguments, "arguments").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a session holds one capacity permit from the moment its runner starts until it ends.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void holdsOnePermitForTheSessionsLifetime()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			HarnessCapacity capacity = HarnessCapacity.of(root.resolve("locks"), 1, () ->
			{
			});
			CountDownLatch entered = new CountDownLatch(1);
			TestSessions sessions = port(root.resolve("runs"), capacity, Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.blocksUntilInterrupted().announcing(entered)));
			SessionHandle handle = sessions.start(request(source, List.of(), "one"));
			requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();

			Optional<HarnessCapacity.Permit> duringSession = capacity.tryAcquire();
			handle.interrupt();
			await(handle);
			Optional<HarnessCapacity.Permit> afterSession = capacity.tryAcquire();

			requireThat(duringSession.isPresent(), "duringSession").isFalse();
			requireThat(afterSession.isPresent(), "afterSession").isTrue();
			afterSession.get().close();
		}
	}

	/**
	 * Verifies that CAT classifies a supplied nonzero turn exit without a terminal result as an infrastructure failure
	 * and identifies the failed turn. It exercises the bridge manifest boundary that records that exit.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void classifiesTurnWithoutResultAsFailure()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			TestSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.replies("first"), Turn.fails(), Turn.replies("third")));

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two", "three")));

			requireThat(result, "result").isInstanceOf(SessionResult.Failed.class);
			requireThat(((SessionResult.Failed) result).reason(), "reason").contains("Turn 2");
		}
	}

	/**
	 * Verifies that a runner that fails before the session starts is an infrastructure failure that carries the
	 * runner's message.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void classifiesRunnerStartupFailureAsFailure()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "", false,
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				throw new IOException("artifact is invalid");
			});

			SessionResult result = await(sessions.start(request(source, List.of(), "one")));

			requireThat(result, "result").isEqualTo(new SessionResult.Failed("artifact is invalid", List.of()));
		}
	}

	/**
	 * Verifies that CAT classifies an expired trial session as Completed and excludes the late terminal response supplied
	 * after its stop request, retaining only replies observed before the limit. The turn that reaches the limit has
	 * produced model output.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void completesWithRepliesSoFarAtTheTimeLimit()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			ManualClock clock = new ManualClock(START);
			CountDownLatch entered = new CountDownLatch(1);
			List<Turn> script = List.of(Turn.replies("first"),
				Turn.blocksUntilInterrupted(TestSessions.OUTPUT_EVENT).announcing(entered));
			TestSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), clock,
				new CopyOnWriteArrayList<>(), script);
			SessionHandle handle = sessions.start(request(source, List.of(), "one", "two", "three"));
			requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();

			clock.advance(LIMIT.plusSeconds(1));

			SessionResult result = await(handle);
			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			requireThat(((SessionResult.Completed) result).responses(), "responses").isEqualTo(List.of(List.of("first")));
		}
	}

	/**
	 * Returns each role that is not a trial and the lower-case word that its time-limit reason uses.
	 *
	 * @return the role and the word
	 */
	@DataProvider
	public Object[][] nonTrialRoles()
	{
		return new Object[][]
			{
				{SessionRole.DERIVER, "deriver"},
				{SessionRole.JUDGE, "judge"},
				{SessionRole.TRIAGE, "triage"},
				{SessionRole.AUTHOR, "author"}
			};
	}

	/**
	 * Verifies that a deriver, judge, triage or author session that reaches its time limit fails with a reason that
	 * names the session and the limit, instead of completing with the replies that it gave before the limit.
	 *
	 * @param role the role that the session performs
	 * @param word the role's word in the reason
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(dataProvider = "nonTrialRoles", timeOut = 120_000)
	public void failsAtTheTimeLimitUnlessTrial(SessionRole role, String word)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			ManualClock clock = new ManualClock(START);
			CountDownLatch entered = new CountDownLatch(1);
			List<Turn> script = List.of(Turn.replies("first"), Turn.blocksUntilInterrupted().announcing(entered));
			Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
			TestSessions sessions = port(artifact, root.resolve("runs"), HarnessCapacity.unlimited(), clock,
				new CopyOnWriteArrayList<>(), script);
			SessionHandle handle = sessions.start(request(role, TrialProfile.HARNESS_DEFAULT, source, List.of(), "one",
				"two", "three"));
			requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();

			clock.advance(LIMIT.plusSeconds(1));

			SessionResult result = await(handle);
			requireThat(result, "result").isEqualTo(new SessionResult.Failed("The " + word +
				" session reached its 5-minute time limit", List.of()));
		}
	}

	/**
	 * Runs a trial whose last turn blocks until the time limit and returns how the session ended.
	 *
	 * @param earlier the turns that end before the last one
	 * @param lastEvents the harness events that the last turn prints before it blocks
	 * @return the result
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	private static SessionResult timeOutTrial(List<Turn> earlier, String... lastEvents)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			ManualClock clock = new ManualClock(START);
			CountDownLatch entered = new CountDownLatch(1);
			List<Turn> script = new ArrayList<>(earlier);
			script.add(Turn.blocksUntilInterrupted(lastEvents).announcing(entered));
			TestSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), clock,
				new CopyOnWriteArrayList<>(), script);
			String[] turns = new String[script.size()];
			Arrays.fill(turns, "turn");
			SessionHandle handle = sessions.start(request(source, List.of(), turns));
			requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();

			clock.advance(LIMIT.plusSeconds(1));

			return await(handle);
		}
	}

	/**
	 * Returns the events that leave a trial's turn without model output at its time limit.
	 *
	 * @return the events that the turn prints
	 */
	@DataProvider
	public Object[][] turnsWithoutOutput()
	{
		return new Object[][]
			{
				{new String[0]},
				{new String[]{TestSessions.HOOK_STARTED_EVENT, TestSessions.HOOK_COMPLETED_EVENT}}
			};
	}

	/**
	 * Verifies that a trial whose turn reaches its time limit with no model output, and with no hook still running,
	 * fails, so that the driver records an infrastructure attempt instead of judging a turn that the provider never
	 * answered.
	 *
	 * @param events the events that the turn prints
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(dataProvider = "turnsWithoutOutput", timeOut = 120_000)
	public void failsWhenTurnHasNoOutputAtTheLimit(String[] events)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		SessionResult result = timeOutTrial(List.of(), events);

		requireThat(result, "result").isEqualTo(new SessionResult.Failed(
			"The trial's turn 1 reached its 5-minute time limit with no model output", List.of()));
	}

	/**
	 * Returns the events of a turn that the model or a hook was working on at its time limit.
	 *
	 * @return the events that the turn prints
	 */
	@DataProvider
	public Object[][] turnsWithActivity()
	{
		return new Object[][]
			{
				{new String[]{TestSessions.OUTPUT_EVENT}},
				{new String[]{TestSessions.HOOK_STARTED_EVENT}},
				{new String[]{TestSessions.HOOK_STARTED_EVENT, TestSessions.OUTPUT_EVENT,
					TestSessions.HOOK_COMPLETED_EVENT}}
			};
	}

	/**
	 * Verifies that a trial whose turn reaches its time limit after the model produced output, or while a hook is
	 * still running, completes and is judged on its behavior.
	 *
	 * @param events the events that the turn prints
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(dataProvider = "turnsWithActivity", timeOut = 120_000)
	public void completesWhenTurnHasActivityAtLimit(String[] events)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		SessionResult result = timeOutTrial(List.of(), events);

		requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
	}

	/**
	 * Verifies that output in an earlier turn does not count for the turn that reaches the time limit, and that the
	 * reason names that turn.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void failsNamingTheTurnThatHadNoOutput()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		SessionResult result = timeOutTrial(List.of(Turn.replies("first", TestSessions.OUTPUT_EVENT)));

		requireThat(result, "result").isEqualTo(new SessionResult.Failed(
			"The trial's turn 2 reached its 5-minute time limit with no model output", List.of()));
	}
}
