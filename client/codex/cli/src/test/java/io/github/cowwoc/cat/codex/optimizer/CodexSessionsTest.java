package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
		RunnerSessions.Settings settings = new RunnerSessions.Settings(runRoot, runRoot, "", false, Map.of(),
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
	 * Verifies that the completed trial carries root tool calls selected and reconciled from recorded events.
	 *
	 * @throws Exception if the session fails
	 */
	@Test
	public void completesWithRootToolCalls()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-C9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			List<String> events = recorded("codex-tool-calls.jsonl");
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.replies("done", events.toArray(String[]::new))));

			SessionResult result = await(sessions.start(request(source, List.of(), "run commands")));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.toolCalls(), "toolCalls").isEqualTo(List.of(
				new ToolCall("commandExecution", "/bin/bash -lc true", 1),
				new ToolCall("commandExecution", "/bin/bash -lc false", 1), new ToolCall("wait", null, null)));
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
		RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), "", false,
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
}
