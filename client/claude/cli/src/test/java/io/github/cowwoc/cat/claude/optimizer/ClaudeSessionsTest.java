package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.common.runner.fakes.ScriptedHarness;
import io.github.cowwoc.cat.common.runner.fakes.ScriptedHarness.Turn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeSessions} over the real runner bridge with a scripted harness.
 */
public final class ClaudeSessionsTest
{
	private static final Duration LIMIT = Duration.ofMinutes(5);

	/**
	 * Creates the test.
	 */
	public ClaudeSessionsTest()
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
		try (InputStream stream = ClaudeSessionsTest.class.getResourceAsStream("/optimizer/" + name))
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
	private static ClaudeSessions port(Path runRoot, HarnessCapacity capacity, Clock clock,
		List<List<String>> arguments, List<Turn> script)
	{
		RunnerSessions.Settings settings = new RunnerSessions.Settings(runRoot, runRoot, "", false, Map.of(),
			runRoot, capacity, clock);
		return new ClaudeSessions(settings, (options, input, output) ->
		{
			arguments.add(options);
			ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "claude", script, input,
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
		return new SessionRequest(SessionRole.TRIAL, source, List.of(turns), readOnly, LIMIT, profile);
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
			List<String> events = recorded("claude-single-turn.jsonl");
			List<Turn> script = List.of(Turn.replies("first", events.toArray(String[]::new)),
				Turn.replies("second").writing("out.txt", "hello"));
			ClaudeSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), script);

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.responses(), "responses").isEqualTo(List.of("first", "second"));
			requireThat(completed.model(), "model").isEqualTo("claude-haiku-4-5-20251001");
			requireThat(completed.usage(), "usage").isEqualTo(new TokenUsage(56_055, 40_099, 565, 397));
			requireThat(completed.windows(), "windows").isEqualTo(List.of(
				new RateLimitWindow(0.3, Instant.ofEpochSecond(1_790_851_200L)),
				new RateLimitWindow(0.04, Instant.ofEpochSecond(1_791_064_800L))));
			requireThat(Files.readAllLines(completed.transcript()), "transcript").isEqualTo(events);
			requireThat(Files.readString(completed.effects().resolve("out.txt")), "effect").isEqualTo("hello");
			requireThat(Files.exists(completed.effects().resolveSibling("home")), "homeExists").isFalse();
			List<String> sources = completed.promptSources().lines().toList();
			requireThat(sources, "promptSources").contains("skill:cat:git-squash");
			requireThat(sources, "promptSources").contains("agent:cat:claude:optimize-medium");
		}
	}

	/**
	 * Creates a port whose runner must never be started.
	 *
	 * @param root the directory for run directories and the host configuration
	 * @param environment the host environment
	 * @return the port
	 */
	private static ClaudeSessions loginPort(Path root, Map<String, String> environment)
	{
		RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), "", false,
			environment, root, HarnessCapacity.unlimited(), Clock.systemUTC());
		return new ClaudeSessions(settings, (options, input, output) ->
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
			Files.writeString(root.resolve(".credentials.json"), "{\"claudeAiOauth\":{}}", UTF_8);

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
			Files.writeString(root.resolve(".credentials.json"), "{\"claudeAiOauth\":{}}", UTF_8);

			HostLogin login = loginPort(root, Map.of("ANTHROPIC_API_KEY", "")).hostLogin();

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
	 * Verifies that the runner receives the session's source as its project, and each read-only path as read-only
	 * evidence.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(timeOut = 120_000)
	public void passesEvidenceToTheRunnerReadOnly() throws Exception
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			Path first = Files.createDirectories(root.resolve("evidence-1"));
			Path second = Files.createDirectories(root.resolve("evidence-2"));
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			ClaudeSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				arguments, List.of(Turn.replies("ok")));

			await(sessions.start(request(source, List.of(first, second), "judge")));

			requireThat(arguments.size(), "arguments.size()").isEqualTo(1);
			List<String> options = arguments.get(0);
			requireThat(ScriptedHarness.optionValue(options, "--project"), "project").isEqualTo(source.toString());
			requireThat(options.indexOf("--read-only-evidence"), "firstEvidence").isGreaterThanOrEqualTo(0);
			requireThat(options.contains(first.toString()), "containsFirst").isTrue();
			requireThat(options.contains(second.toString()), "containsSecond").isTrue();
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
			List<String> events = recorded("claude-single-turn.jsonl");
			List<Turn> script = List.of(Turn.blocksUntilInterrupted(events.toArray(String[]::new)).announcing(entered),
				Turn.replies("never"));
			ClaudeSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), script);
			SessionHandle handle = sessions.start(request(source, List.of(), "one", "two"));
			requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();

			handle.interrupt();

			SessionResult result = await(handle);
			requireThat(result, "result").isInstanceOf(SessionResult.Interrupted.class);
			requireThat(result.windows().size(), "windows.size()").isEqualTo(2);
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
			String rejected = "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\"," +
				"\"resetsAt\":1790851200,\"rateLimitType\":\"five_hour\",\"unifiedWindows\":{\"five_hour\":" +
				"{\"utilization\":1.0,\"resetsAt\":1790851200}}}}";
			ClaudeSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.replies("limit", rejected)));

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

			requireThat(result, "result").isEqualTo(new SessionResult.RateLimited(Instant.ofEpochSecond(1_790_851_200L),
				List.of(new RateLimitWindow(1.0, Instant.ofEpochSecond(1_790_851_200L)))));
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
			String error = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":500," +
				"\"terminal_reason\":\"model_error\",\"result\":\"details\"}";
			ClaudeSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.replies("oops", error), Turn.replies("never")));

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

			requireThat(result, "result").isEqualTo(new SessionResult.Failed(
				"Claude reported an error result (api_error_status=500, terminal_reason=model_error)", List.of()));
		}
	}
}
