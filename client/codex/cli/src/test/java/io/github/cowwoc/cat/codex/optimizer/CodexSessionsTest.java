package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.fakes.ManualClock;
import io.github.cowwoc.cat.common.runner.fakes.ScriptedHarness;
import io.github.cowwoc.cat.common.runner.fakes.ScriptedHarness.Turn;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.Test;

/**
 * Tests {@link CodexSessions} over the real runner bridge with a scripted harness.
 */
public final class CodexSessionsTest
{
	private static final Duration LIMIT = Duration.ofMinutes(5);
	private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");

	/**
	 * Creates the test.
	 */
	public CodexSessionsTest()
	{
	}

	/**
	 * Reads the lines of a recorded harness event file.
	 *
	 * @param name the resource name below {@code optimizer}
	 * @return the lines
	 * @throws IOException if the resource cannot be read
	 */
	private static List<String> recorded(String name) throws IOException
	{
		try (InputStream stream = CodexSessionsTest.class.getResourceAsStream("/optimizer/" + name))
		{
			requireThat(stream, "resource").isNotNull();
			return new String(stream.readAllBytes(), UTF_8).lines().toList();
		}
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
	private static CodexSessions port(Path runRoot, HarnessCapacity capacity, Clock clock,
		List<List<String>> arguments, List<Turn> script)
	{
		CodexSessions.Settings settings = new CodexSessions.Settings(runRoot, runRoot, "", "", false, Map.of(),
			runRoot, capacity, clock);
		return new CodexSessions(settings, (options, input, output) ->
		{
			arguments.add(options);
			ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "codex", script, input,
				output);
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
		return new SessionRequest(SessionRole.TRIAL, source, List.of(turns), readOnly, LIMIT);
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
	 * Verifies that a session sends each turn through the runner, and that the completed result carries the replies,
	 * the transcript, the project effects, the observed model, the usage and the rate-limit windows.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void completesEveryTurnWithObservedEvidence() throws Exception
	{
		// Requirement: R-E13
		// Requirement: R-E16
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			List<String> events = new ArrayList<>(recorded("codex-children.jsonl"));
			events.addAll(recorded("codex-hooks.jsonl"));
			List<Turn> script = List.of(Turn.replies("first", events.toArray(String[]::new)),
				Turn.replies("second").writing("out.txt", "hello"));
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), script);

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.responses(), "responses").isEqualTo(List.of("first", "second"));
			requireThat(completed.model(), "model").isEqualTo("gpt-6-luna");
			requireThat(completed.reasoning(), "reasoning").isEqualTo("medium");
			requireThat(completed.usage(), "usage").isEqualTo(new TokenUsage(203_636, 148_992, 1_626, 531));
			requireThat(completed.windows(), "windows").isEqualTo(List.of(
				new RateLimitWindow(0.36, Instant.ofEpochSecond(1_791_046_710L))));
			requireThat(Files.readAllLines(completed.transcript()), "transcript").isEqualTo(events);
			requireThat(Files.readString(completed.effects().resolve("out.txt")), "effect").isEqualTo("hello");
			requireThat(Files.exists(completed.effects().resolveSibling("home")), "homeExists").isFalse();
			requireThat(completed.promptSources(), "promptSources").contains("hook:sessionStart plugin ");
		}
	}

	/**
	 * Creates a port whose runner must never be started.
	 *
	 * @param root the directory for run directories and the host configuration
	 * @param environment the host environment
	 * @return the port
	 */
	private static CodexSessions loginPort(Path root, Map<String, String> environment)
	{
		CodexSessions.Settings settings = new CodexSessions.Settings(root, root.resolve("runs"), "", "", false,
			environment, root, HarnessCapacity.unlimited(), Clock.systemUTC());
		return new CodexSessions(settings, (options, input, output) ->
		{
			throw new AssertionError("A login classification must not start a session");
		});
	}

	/**
	 * Verifies that the port classifies a signed-in host as a subscription, from the key names of its credential
	 * file, without starting a session.
	 *
	 * @throws Exception if the classification fails
	 */
	@Test(timeOut = 120_000)
	public void classifiesASubscriptionLogin() throws Exception
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Files.writeString(root.resolve("auth.json"), "{\"tokens\":{}}", UTF_8);

			HostLogin login = loginPort(root, Map.of()).hostLogin();

			requireThat(login, "login").isEqualTo(HostLogin.SUBSCRIPTION);
		}
	}

	/**
	 * Verifies that the port classifies a host whose environment selects an API key as an API-key login, even when a
	 * subscription credential file exists.
	 *
	 * @throws Exception if the classification fails
	 */
	@Test(timeOut = 120_000)
	public void classifiesAnApiKeyLogin() throws Exception
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Files.writeString(root.resolve("auth.json"), "{\"tokens\":{}}", UTF_8);

			HostLogin login = loginPort(root, Map.of("CODEX_API_KEY", "")).hostLogin();

			requireThat(login, "login").isEqualTo(HostLogin.API_KEY);
		}
	}

	/**
	 * Verifies that the port reports an unknown login when the host has neither a credential file nor a login
	 * variable.
	 *
	 * @throws Exception if the classification fails
	 */
	@Test(timeOut = 120_000)
	public void classifiesAMissingLoginAsUnknown() throws Exception
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));

			HostLogin login = loginPort(root, Map.of()).hostLogin();

			requireThat(login, "login").isEqualTo(HostLogin.UNKNOWN);
		}
	}

	/**
	 * Verifies that the runner receives the session's source as its project and is told to keep the run, because the
	 * run directory holds the session's evidence.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void startsTheRunnerWithTheSessionsSource() throws Exception
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				arguments, List.of(Turn.replies("ok")));

			await(sessions.start(request(source, List.of(), "judge")));

			requireThat(arguments.size(), "arguments.size()").isEqualTo(1);
			List<String> options = arguments.get(0);
			requireThat(ScriptedHarness.optionValue(options, "--project"), "project").isEqualTo(source.toString());
			requireThat(options.contains("--keep-run"), "keepRun").isTrue();
		}
	}

	/**
	 * Verifies that an interrupt that CAT requests while a turn runs ends the session as an interruption, and that the
	 * runner is asked to stop the turn.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void reportsRequestedInterrupt() throws Exception
	{
		// Requirement: R-E15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			CountDownLatch entered = new CountDownLatch(1);
			List<String> events = recorded("codex-children.jsonl");
			List<Turn> script = List.of(Turn.blocksUntilInterrupted(events.toArray(String[]::new)).announcing(entered),
				Turn.replies("never"));
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), script);
			SessionHandle handle = sessions.start(request(source, List.of(), "one", "two"));
			requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();

			handle.interrupt();

			SessionResult result = await(handle);
			requireThat(result, "result").isInstanceOf(SessionResult.Interrupted.class);
			requireThat(result.windows().size(), "windows.size()").isEqualTo(1);
		}
	}

	/**
	 * Verifies that the interrupt request that the port sends the runner waits for the turn to start, so that an
	 * interrupt that reaches the runner before its turn is running is not lost.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void interruptWaitsForTheTurnStart() throws Exception
	{
		// Requirement: R-E15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			Path runs = root.resolve("runs");
			List<String> requests = new CopyOnWriteArrayList<>();
			CountDownLatch turnReceived = new CountDownLatch(1);
			CodexSessions.Settings settings = new CodexSessions.Settings(root, runs, "", "", false, Map.of(), root,
				HarnessCapacity.unlimited(), Clock.systemUTC());
			CodexSessions sessions = new CodexSessions(settings, (options, input, output) ->
			{
				HarnessRunManager manager = new HarnessRunManager(runs, Clock.systemUTC());
				HarnessRun run = manager.create("codex");
				manager.initialize(run, root, root, "scripted", List.of("scripted"));
				output.write("{\"type\":\"started\",\"runId\":\"" + run.id() + "\"}\n");
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
						output.write("{\"type\":\"turn-completed\",\"assistant_response\":\"stopped\"}\n");
						output.flush();
					}
					line = lines.readLine();
				}
			});
			SessionHandle handle = sessions.start(request(source, List.of(), "one"));
			requireThat(turnReceived.await(60, TimeUnit.SECONDS), "turnReceived").isTrue();

			handle.interrupt();

			requireThat(await(handle), "result").isInstanceOf(SessionResult.Interrupted.class);
			requireThat(requests, "requests").contains("{\"type\":\"interrupt\",\"after\":\"turn-started\"}");
		}
	}

	/**
	 * Verifies that a harness that fails after CAT asked it to stop still yields an interruption, because the failure
	 * is a consequence of the interrupt (design &sect;11, H10).
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void reportsInterruptDespiteFailure() throws Exception
	{
		// Requirement: R-E15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			CountDownLatch entered = new CountDownLatch(1);
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.failsWhenInterrupted().announcing(entered)));
			SessionHandle handle = sessions.start(request(source, List.of(), "one"));
			requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();

			handle.interrupt();

			requireThat(await(handle), "result").isInstanceOf(SessionResult.Interrupted.class);
		}
	}

	/**
	 * Verifies that interrupting a session that already ended does nothing.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void ignoresInterruptAfterTheSessionEnded() throws Exception
	{
		// Requirement: R-E15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.replies("done")));
			SessionHandle handle = sessions.start(request(source, List.of(), "one"));
			SessionResult result = await(handle);

			handle.interrupt();

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			requireThat(await(handle), "second").isEqualTo(result);
		}
	}

	/**
	 * Verifies that an interrupt that arrives while the session waits for capacity ends it without starting a
	 * runner.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void interruptsWhileWaitingForCapacity() throws Exception
	{
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			HarnessCapacity capacity = HarnessCapacity.of(root.resolve("locks"), 1, () ->
			{
			});
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			CodexSessions sessions = port(root.resolve("runs"), capacity, Clock.systemUTC(), arguments,
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
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void holdsOnePermitForTheSessionsLifetime() throws Exception
	{
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			HarnessCapacity capacity = HarnessCapacity.of(root.resolve("locks"), 1, () ->
			{
			});
			CountDownLatch entered = new CountDownLatch(1);
			CodexSessions sessions = port(root.resolve("runs"), capacity, Clock.systemUTC(),
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
	 * Verifies that a turn that ends without a result is an infrastructure failure, even though the bridge reports
	 * the failure only when it receives its next request.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void classifiesTurnWithoutResultAsFailure() throws Exception
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
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
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void classifiesRunnerStartupFailureAsFailure() throws Exception
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			CodexSessions.Settings settings = new CodexSessions.Settings(root, root.resolve("runs"), "", "", false,
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			CodexSessions sessions = new CodexSessions(settings, (options, input, output) ->
			{
				throw new IOException("artifact is invalid");
			});

			SessionResult result = await(sessions.start(request(source, List.of(), "one")));

			requireThat(result, "result").isEqualTo(new SessionResult.Failed("artifact is invalid", List.of()));
		}
	}

	/**
	 * Verifies that a provider rejection for a rate limit is reported with its reset time, and that the session sends
	 * no further turn.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void classifiesRateLimitRejection() throws Exception
	{
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			String rejected = "{\"method\":\"account/rateLimits/updated\",\"params\":{\"rateLimits\":" +
				"{\"primary\":{\"usedPercent\":100,\"windowDurationMins\":300,\"resetsAt\":1791000000}," +
				"\"secondary\":null,\"rateLimitReachedType\":\"primary\"}}}";
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.replies("limit", rejected)));

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

			requireThat(result, "result").isEqualTo(new SessionResult.RateLimited(Instant.ofEpochSecond(1_791_000_000L),
				List.of(new RateLimitWindow(1.0, Instant.ofEpochSecond(1_791_000_000L)))));
		}
	}

	/**
	 * Verifies that an error result is an infrastructure failure that names the error.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void classifiesErrorResultAsFailure() throws Exception
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			String started = "{\"method\":\"thread/started\",\"params\":{\"thread\":{\"id\":\"root\"," +
				"\"parentThreadId\":null,\"model\":\"gpt-6-luna\",\"reasoningEffort\":\"medium\"}}}";
			String error = "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"root\",\"turn\":" +
				"{\"status\":\"failed\",\"error\":{\"message\":\"details\"}}}}";
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.replies("oops", started, error), Turn.replies("never")));

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

			requireThat(result, "result").isEqualTo(new SessionResult.Failed(
				"Codex turn finished with status failed", List.of()));
		}
	}

	/**
	 * Verifies that a session that exceeds its time limit is completed with the replies that it had, because CAT
	 * judges such a trial on its behavior instead of retrying it (design &sect;3.2).
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void completesWithRepliesSoFarAtTheTimeLimit() throws Exception
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			ManualClock clock = new ManualClock(START);
			CountDownLatch entered = new CountDownLatch(1);
			List<Turn> script = List.of(Turn.replies("first"), Turn.blocksUntilInterrupted().announcing(entered));
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), clock,
				new CopyOnWriteArrayList<>(), script);
			SessionHandle handle = sessions.start(request(source, List.of(), "one", "two", "three"));
			requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();

			clock.advance(LIMIT.plusSeconds(1));

			SessionResult result = await(handle);
			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			requireThat(((SessionResult.Completed) result).responses(), "responses").isEqualTo(List.of("first"));
		}
	}

	/**
	 * Verifies that a runner that ignores an interrupt does not hold the session forever, and that the session still
	 * reports the interruption.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void givesUpOnARunnerThatIgnoresAnInterrupt() throws Exception
	{
		// Requirement: R-E15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			ManualClock clock = new ManualClock(START);
			CountDownLatch entered = new CountDownLatch(1);
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), clock,
				new CopyOnWriteArrayList<>(), List.of(Turn.blocksForever().announcing(entered)));
			SessionHandle handle = sessions.start(request(source, List.of(), "one"));
			requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();

			handle.interrupt();
			// The driver sends the interrupt on its next poll and then starts the grace period on the clock.
			List<SessionResult> results = new ArrayList<>();
			handle.completion().thenAccept(results::add);
			while (results.isEmpty())
			{
				clock.advance(Duration.ofSeconds(31));
				handle.completion().toCompletableFuture().completeOnTimeout(null, 1, TimeUnit.SECONDS).get();
			}

			requireThat(results.get(0), "result").isInstanceOf(SessionResult.Interrupted.class);
		}
	}
}
