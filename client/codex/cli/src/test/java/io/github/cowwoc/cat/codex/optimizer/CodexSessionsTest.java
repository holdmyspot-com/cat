package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.cat.common.optimizer.adapter.fakes.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.adapter.fakes.RoleRuleFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
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

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexSessions} over the real runner bridge with a scripted harness.
 * <p>
 * Inline rejection and error events use the rate-limit and turn fields documented by codex-cli 0.147.0's
 * <a href="https://github.com/openai/codex/blob/rust-v0.147.0/codex-rs/app-server/README.md">app-server README</a>
 * (Rate limits, Turn events and Errors). Their values are representative parser inputs, not recorded provider outcomes.
 */
public final class CodexSessionsTest
{
	private static final Duration LIMIT = Duration.ofMinutes(5);
	private static final Path TEMP = Path.of(System.getProperty("cat.work.temp"));

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
		return port(runRoot, capacity, clock, arguments, script, new CopyOnWriteArrayList<>());
	}

	/**
	 * Creates a port and observes the prompts that CAT sends through the bridge.
	 *
	 * @param runRoot the directory for run directories
	 * @param capacity the shared capacity
	 * @param clock the clock for time limits
	 * @param arguments receives the runner's options for each session
	 * @param script what the harness does, turn by turn
	 * @param requests receives the decoded prompts in turn order
	 * @return the port
	 */
	private static CodexSessions port(Path runRoot, HarnessCapacity capacity, Clock clock,
		List<List<String>> arguments, List<Turn> script, List<String> requests)
	{
		RunnerSessions.Settings settings = new RunnerSessions.Settings(runRoot, runRoot, List::of, "", false, Map.of(),
			runRoot, capacity, clock);
		return new CodexSessions(settings, (options, input, output) ->
		{
			arguments.add(options);
			ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "codex", script, input,
				output, requests);
		});
	}

	/**
	 * Returns the roles whose runner options are checked.
	 *
	 * @return the session roles
	 */
	@DataProvider
	public Object[][] sessionRoles()
	{
		return new Object[][] {{SessionRole.TRIAL}, {SessionRole.JUDGE}, {SessionRole.TRIAGE},
			{SessionRole.AUTHOR}, {SessionRole.DERIVER}};
	}

	/**
	 * Verifies that a port configured to install CAT passes {@code --install-cat} only for trial sessions.
	 *
	 * @param role the session's role
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(dataProvider = "sessionRoles", timeOut = 120_000)
	public void passesInstallCatOnlyForTrialSessions(SessionRole role)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E20
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-role-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(artifact, root.resolve("runs"), List::of, "", true,
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			CodexSessions sessions = new CodexSessions(settings, (options, input, output) ->
			{
				arguments.add(options);
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "codex",
					List.of(Turn.replies("ok")), input, output);
			});

			SessionResult result = await(sessions.start(new SessionRequest(role, partOf(role), source, List.of("brief"),
				List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT)));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			requireThat(arguments.size(), "arguments.size()").isEqualTo(1);
			requireThat(arguments.get(0).contains("--install-cat"), "installCat").isEqualTo(role == SessionRole.TRIAL);
		}
	}

	/**
	 * Returns each session role with a part that it may request.
	 *
	 * @return the roles and parts
	 */
	@DataProvider
	public Object[][] sessionParts()
	{
		return new Object[][] {{SessionRole.TRIAL, Part.DEVELOPMENT}, {SessionRole.TRIAL, Part.HELD_OUT},
			{SessionRole.JUDGE, Part.HELD_OUT}, {SessionRole.TRIAGE, Part.DEVELOPMENT},
			{SessionRole.AUTHOR, Part.DEVELOPMENT}, {SessionRole.DERIVER, Part.DEVELOPMENT}};
	}

	/**
	 * Verifies that the held-out run root beside the run root exists before the session starts, that a session of the
	 * held-out part runs under it and is denied nothing, and that a session of the development part runs under the
	 * run root and passes each listed held-out directory and the held-out run root to the runner through
	 * {@code --deny-read}.
	 *
	 * @param role the session's role
	 * @param part the part that the session may read
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(dataProvider = "sessionParts", timeOut = 120_000)
	public void deniesHeldOutReadsToDevelopmentSessions(SessionRole role, Part part)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-denied-reads-")).
				toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));
			Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
			Path heldOut = Files.createDirectories(root.resolve("campaign/held-out"));
			Path runRoot = root.resolve("runs");
			Path heldOutRunRoot = root.resolve("runs-held-out");
			RunnerSessions.Settings settings = new RunnerSessions.Settings(artifact, runRoot, () -> List.of(heldOut), "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			List<Boolean> heldOutRunRootExisted = new CopyOnWriteArrayList<>();
			CodexSessions sessions = new CodexSessions(settings, (options, input, output) ->
			{
				arguments.add(options);
				heldOutRunRootExisted.add(Files.isDirectory(heldOutRunRoot));
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "codex",
					List.of(Turn.replies("ok")), input, output);
			});

			SessionResult result = await(sessions.start(new SessionRequest(role, part, source, List.of("brief"),
				List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT)));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			requireThat(arguments.size(), "arguments.size()").isEqualTo(1);
			requireThat(heldOutRunRootExisted, "heldOutRunRootExisted").isEqualTo(List.of(true));
			List<String> denied = new ArrayList<>();
			List<String> options = arguments.get(0);
			for (int i = 0; i < options.size() - 1; ++i)
			{
				if (options.get(i).equals("--deny-read"))
					denied.add(options.get(i + 1));
			}
			Path expectedRunRoot;
			List<String> expectedDenied;
			if (part == Part.HELD_OUT)
			{
				expectedRunRoot = heldOutRunRoot;
				expectedDenied = List.of();
			}
			else
			{
				expectedRunRoot = runRoot;
				expectedDenied = List.of(heldOut.toString(), heldOutRunRoot.toString());
			}
			requireThat(ScriptedHarness.optionValue(options, "--run-root"), "runRoot").
				isEqualTo(expectedRunRoot.toString());
			requireThat(denied, "denied").isEqualTo(expectedDenied);
			requireThat(((SessionResult.Completed) result).transcript().startsWith(expectedRunRoot), "transcript").
				isTrue();
		}
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
		return new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, source, List.of(turns), readOnly, LIMIT, profile);
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
	 * Verifies that CAT preserves prompt and response order through the bridge, records the unchanged event lines,
	 * selects the run's project directory, removes its isolated home and derives usage and prompt sources from recorded
	 * events. The observed model, reasoning effort and parsed rate-limit windows are retained in the completed result.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void completesEveryTurnWithObservedEvidence()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		// Requirement: R-E16
		// Requirement: R-E6
		// Requirement: R-C12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			List<String> events = new ArrayList<>(recorded("codex-children.jsonl"));
			events.addAll(recorded("codex-hooks.jsonl"));
			List<Turn> script = List.of(Turn.replies("first", events.toArray(String[]::new)),
				Turn.replies("second"));
			List<String> requests = new CopyOnWriteArrayList<>();
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), script, requests);

			SessionResult result = await(sessions.start(request(source, List.of(), "one \"quoted\"", "two\nlines")));

			requireThat(requests, "requests").isEqualTo(List.of("one \"quoted\"", "two\nlines"));
			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.responses(), "responses").isEqualTo(List.of(List.of("first"), List.of("second")));
			requireThat(completed.model(), "model").isEqualTo("gpt-6-luna");
			requireThat(completed.reasoning(), "reasoning").isEqualTo("medium");
			requireThat(completed.usage(), "usage").isEqualTo(new TokenUsage(203_636, 148_992, 1_626, 531));
			requireThat(completed.windows(), "windows").isEqualTo(List.of(
				new RateLimitWindow(0.36, Instant.ofEpochSecond(1_791_046_710L))));
			requireThat(Files.readAllLines(completed.transcript()), "transcript").isEqualTo(events);
			requireThat(completed.effects(), "effects").isEqualTo(completed.transcript().getParent().getParent().
				resolve("project"));
			requireThat(completed.effects(), "effects").isDirectory();
			requireThat(Files.exists(completed.effects().resolveSibling("home")), "homeExists").isFalse();
			requireThat(completed.promptSources(), "promptSources").contains("hook:sessionStart plugin ");
		}
	}

	/**
	 * Verifies that the completed trial carries root tool calls selected and reconciled from recorded events.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test
	public void completesWithRootToolCalls()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-C9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
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
		RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "", false,
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
	 * @throws IOException if a credential fixture cannot be created
	 */
	@Test(timeOut = 120_000)
	public void classifiesASubscriptionLogin() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Files.writeString(root.resolve("auth.json"), "{\"tokens\":{}}", UTF_8);

			HostLogin login = loginPort(root, Map.of()).hostLogin();

			requireThat(login, "login").isEqualTo(HostLogin.SUBSCRIPTION);
		}
	}

	/**
	 * Verifies that the port classifies a host whose environment selects an API key as an API-key login, even when a
	 * subscription credential file exists.
	 *
	 * @throws IOException if a credential fixture cannot be created
	 */
	@Test(timeOut = 120_000)
	public void classifiesAnApiKeyLogin() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Files.writeString(root.resolve("auth.json"), "{\"tokens\":{}}", UTF_8);

			HostLogin login = loginPort(root, Map.of("CODEX_API_KEY", "")).hostLogin();

			requireThat(login, "login").isEqualTo(HostLogin.API_KEY);
		}
	}

	/**
	 * Verifies that the port reports an unknown login when the host has neither a credential file nor a login
	 * variable.
	 *
	 * @throws IOException if a credential fixture cannot be created
	 */
	@Test(timeOut = 120_000)
	public void classifiesAMissingLoginAsUnknown() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));

			HostLogin login = loginPort(root, Map.of()).hostLogin();

			requireThat(login, "login").isEqualTo(HostLogin.UNKNOWN);
		}
	}

	/**
	 * Verifies that the runner receives the session's source as its project and is told to keep the run, because the
	 * run directory holds the session's evidence.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void startsTheRunnerWithTheSessionsSource()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
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
	 * Verifies that a session under a tool-output token limit passes the limit to the Codex runner, and that a
	 * session without one passes no limit.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void passesTheToolOutputTokenLimit()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-C19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				arguments, List.of(Turn.replies("ok")));

			await(sessions.start(request(new TrialProfile("gpt-6-luna", "low", 5000), source, List.of(), "limited")));
			await(sessions.start(request(new TrialProfile("gpt-6-luna", "low"), source, List.of(), "unlimited")));

			requireThat(arguments.size(), "arguments.size()").isEqualTo(2);
			List<String> limited = arguments.get(0);
			requireThat(ScriptedHarness.optionValue(limited, "--tool-output-token-limit"), "limit").isEqualTo("5000");
			requireThat(ScriptedHarness.optionValue(limited, "--effort"), "effort").isEqualTo("low");
			requireThat(arguments.get(1).contains("--tool-output-token-limit"), "unlimited").isFalse();
		}
	}

	/**
	 * Verifies that CAT classifies its requested interrupt as Interrupted and retains the parsed windows when the
	 * bridge supplies a terminal completion. The app-server README documents interrupted completion; this test makes
	 * no claim about the native harness's stop timing.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void reportsRequestedInterrupt()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
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
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void classifiesRateLimitRejection()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			String rejected = "{\"method\":\"account/rateLimits/updated\",\"params\":{\"rateLimits\":" +
				"{\"primary\":{\"usedPercent\":100,\"windowDurationMins\":300,\"resetsAt\":1791000000}," +
				"\"secondary\":null,\"rateLimitReachedType\":\"primary\"}}}";
			List<String> requests = new CopyOnWriteArrayList<>();
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.replies("limit", rejected), Turn.replies("never")), requests);

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

			requireThat(result, "result").isEqualTo(new SessionResult.RateLimited(Instant.ofEpochSecond(1_791_000_000L),
				List.of(new RateLimitWindow(1.0, Instant.ofEpochSecond(1_791_000_000L)))));
			requireThat(requests, "requests").isEqualTo(List.of("one"));
		}
	}

	/**
	 * Verifies that CAT classifies an error result as an infrastructure failure, names the failed status and sends no
	 * further turn.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void classifiesErrorResultAsFailure()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			String started = "{\"method\":\"thread/started\",\"params\":{\"thread\":{\"id\":\"root\"," +
				"\"parentThreadId\":null,\"model\":\"gpt-6-luna\",\"reasoningEffort\":\"medium\"}}}";
			String error = "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"root\",\"turn\":" +
				"{\"status\":\"failed\",\"error\":{\"message\":\"details\"}}}}";
			List<String> requests = new CopyOnWriteArrayList<>();
			CodexSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.replies("oops", started, error), Turn.replies("never")), requests);

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

			requireThat(result, "result").isEqualTo(new SessionResult.Failed(
				"Codex turn finished with status failed", List.of()));
			requireThat(requests, "requests").isEqualTo(List.of("one"));
		}
	}
}
