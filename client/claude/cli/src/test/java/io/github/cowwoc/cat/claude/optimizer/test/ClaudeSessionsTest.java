package io.github.cowwoc.cat.claude.optimizer.test;

import static io.github.cowwoc.cat.common.optimizer.adapter.fakes.test.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.claude.optimizer.ClaudeSessions;
import io.github.cowwoc.cat.claude.secrets.ClaudeSessionsSecrets;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.adapter.fakes.test.RoleRuleFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import io.github.cowwoc.cat.common.runner.fakes.test.ScriptedHarness;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * Tests {@link ClaudeSessions} over the real runner bridge with a scripted harness.
 * <p>
 * Inline rejection and error events use the fields documented by Anthropic's Agent SDK v0.2.163, which bundles
 * Claude Code 2.1.286, in
 * <a href="https://github.com/anthropics/claude-agent-sdk-python/blob/v0.2.163/src/claude_agent_sdk/types.py">
 * RateLimitInfo and ResultMessage</a>. Their values are representative parser inputs, not recorded provider outcomes.
 */
public final class ClaudeSessionsTest
{
	private static final Duration LIMIT = Duration.ofMinutes(5);
	private static final Path TEMP = Path.of(System.getProperty("cat.work.temp"));

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
	private static ClaudeSessions port(Path runRoot, HarnessCapacity capacity, Clock clock,
		List<List<String>> arguments, List<Turn> script, List<String> requests)
	{
		RunnerSessions.Settings settings = new RunnerSessions.Settings(runRoot, runRoot, List::of, "", false, Map.of(),
			runRoot, capacity, clock);
		return ClaudeSessionsSecrets.access().newClaudeSessions(settings, (options, input, output) ->
		{
			arguments.add(options);
			ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "claude", script, input,
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
			{SessionRole.PROMPT_AUTHOR}, {SessionRole.CONTRACT_AUTHOR}};
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
			ClaudeSessions sessions = ClaudeSessionsSecrets.access().newClaudeSessions(settings, (options, input, output) ->
			{
				arguments.add(options);
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "claude",
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
			{SessionRole.PROMPT_AUTHOR, Part.DEVELOPMENT}, {SessionRole.CONTRACT_AUTHOR, Part.DEVELOPMENT}};
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
			Path heldOut = Files.createDirectories(root.resolve("campaign/held_out"));
			Path runRoot = root.resolve("runs");
			Path heldOutRunRoot = root.resolve("runs-held_out");
			RunnerSessions.Settings settings = new RunnerSessions.Settings(artifact, runRoot, () -> List.of(heldOut), "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			List<Boolean> heldOutRunRootExisted = new CopyOnWriteArrayList<>();
			ClaudeSessions sessions = ClaudeSessionsSecrets.access().newClaudeSessions(settings, (options, input, output) ->
			{
				arguments.add(options);
				heldOutRunRootExisted.add(Files.isDirectory(heldOutRunRoot));
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "claude",
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
	 * Returns each settings file that outranks CAT's isolated settings, with a setting that turns off the sandbox
	 * that denies held-out reads and the setting's name.
	 *
	 * @return the file's location, its content and the setting's name
	 */
	@DataProvider
	public Object[][] exposingSettings()
	{
		List<Object[]> rows = new ArrayList<>();
		for (String location : List.of("managed", ".claude/settings.json", ".claude/settings.local.json"))
		{
			rows.add(new Object[]{location, "{\"sandbox\": {\"enabled\": false}}", "sandbox.enabled"});
			rows.add(new Object[]{location, "{\"sandbox\": {\"allowUnsandboxedCommands\": true}}",
				"sandbox.allowUnsandboxedCommands"});
			rows.add(new Object[]{location, "{\"sandbox\": {\"excludedCommands\": [\"cat\"]}}",
				"sandbox.excludedCommands"});
			rows.add(new Object[]{location, "{\"sandbox\": {\"filesystem\": {\"allowRead\": [\"/tmp/held-out\"]}}}",
				"sandbox.filesystem.allowRead"});
		}
		return rows.toArray(Object[][]::new);
	}

	/**
	 * Verifies that a managed or project settings file that turns off the sandbox, lets commands run outside it,
	 * excludes commands from it, or re-allows reads that it denies is reported with the file and the setting, and
	 * that the message asks the user to remove the setting and run {@code start} again.
	 *
	 * @param location {@code managed} for the managed settings file, or the project-relative path of the file
	 * @param content the file's content
	 * @param setting the name of the setting that the message names
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test(dataProvider = "exposingSettings")
	public void reportsSettingThatTurnsOffTheSandbox(String location, String content, String setting)
		throws IOException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-exposure-")).
				toRealPath();
			Path project = Files.createDirectories(root.resolve("project"));
			Path managed = root.resolve("managed-settings.json");
			Path file;
			if (location.equals("managed"))
				file = managed;
			else
				file = project.resolve(location);
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, UTF_8);

			Optional<String> exposure = exposurePort(root, managed).findHeldOutExposure(project);

			requireThat(exposure.isPresent(), "exposure.isPresent").isTrue();
			requireThat(exposure.orElseThrow(), "exposure").contains(file.toString()).contains(setting).
				contains("held-out").contains("run `start` again");
		}
	}

	/**
	 * Verifies that no exposure is reported when the managed and project settings files are absent, hold no sandbox
	 * settings, keep the sandbox on, or hold an empty or absent {@code sandbox.filesystem.allowRead}, and that the
	 * host's user settings, which CAT's isolated settings replace, are not consulted even when they turn the sandbox
	 * off.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void reportsNoExposureWhenTheSandboxStaysOn() throws IOException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-exposure-")).
				toRealPath();
			Path project = Files.createDirectories(root.resolve("project"));
			Path managed = root.resolve("managed-settings.json");
			Files.writeString(root.resolve("settings.json"), "{\"sandbox\": {\"enabled\": false}}", UTF_8);
			ClaudeSessions sessions = exposurePort(root, managed);
			requireThat(sessions.findHeldOutExposure(project), "absent").isEmpty();

			Files.writeString(managed, "{\"permissions\": {\"allow\": []}}", UTF_8);
			Files.createDirectories(project.resolve(".claude"));
			Files.writeString(project.resolve(".claude/settings.json"),
				"{\"sandbox\": {\"enabled\": true, \"allowUnsandboxedCommands\": false, \"excludedCommands\": []," +
					" \"filesystem\": {\"allowRead\": []}}}", UTF_8);
			Files.writeString(project.resolve(".claude/settings.local.json"),
				"{\"sandbox\": {\"filesystem\": {\"allowWrite\": [\"/tmp\"]}}}", UTF_8);
			requireThat(sessions.findHeldOutExposure(project), "sandboxOn").isEmpty();
		}
	}

	/**
	 * Creates a port that reads the given managed settings file and whose host configuration directory is
	 * {@code root}.
	 *
	 * @param root the host configuration directory
	 * @param managed the managed settings file
	 * @return the port
	 */
	private static ClaudeSessions exposurePort(Path root, Path managed)
	{
		RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "", false,
			Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
		return ClaudeSessionsSecrets.access().newClaudeSessions(settings, (_, _, _) ->
		{
			throw new AssertionError("No session may start");
		}, List.of(managed));
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
	 * selects the run's project directory, removes its isolated home and derives usage, tool calls and prompt sources
	 * from recorded events. The observed model and parsed rate-limit windows are retained in the completed result.
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
		// Requirement: R-C9
		// Requirement: R-E13
		// Requirement: R-E16
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			List<String> events = new ArrayList<>(recorded("claude-single-turn.jsonl"));
			events.addAll(recorded("claude-tool-calls.jsonl"));
			List<Turn> script = List.of(Turn.replies("first", events.toArray(String[]::new)),
				Turn.replies("second"));
			List<String> requests = new CopyOnWriteArrayList<>();
			ClaudeSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), script, requests);

			SessionResult result = await(sessions.start(request(source, List.of(), "one \"quoted\"", "two\nlines")));

			requireThat(requests, "requests").isEqualTo(List.of("one \"quoted\"", "two\nlines"));
			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.responses(), "responses").isEqualTo(List.of(List.of("first"), List.of("second")));
			requireThat(completed.toolCalls(), "toolCalls").isEqualTo(List.of(
				new ToolCall("Bash", "true", 0), new ToolCall("Bash", "false", 1),
				new ToolCall("Read", null, null)));
			requireThat(completed.model(), "model").isEqualTo("claude-haiku-4-5-20251001");
			requireThat(completed.usage(), "usage").isEqualTo(new TokenUsage(56_055, 40_099, 565, 397));
			requireThat(completed.windows(), "windows").isEqualTo(List.of(
				new RateLimitWindow(0.3, Instant.ofEpochSecond(1_790_851_200L)),
				new RateLimitWindow(0.04, Instant.ofEpochSecond(1_791_064_800L))));
			requireThat(Files.readAllLines(completed.transcript()), "transcript").isEqualTo(events);
			requireThat(completed.effects(), "effects").isEqualTo(completed.transcript().getParent().getParent().
				resolve("project"));
			requireThat(completed.effects(), "effects").isDirectory();
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
		RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "", false,
			environment, root, HarnessCapacity.unlimited(), Clock.systemUTC());
		return ClaudeSessionsSecrets.access().newClaudeSessions(settings, (options, input, output) ->
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
			Files.writeString(root.resolve(".credentials.json"), "{\"claudeAiOauth\":{}}", UTF_8);

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
			Files.writeString(root.resolve(".credentials.json"), "{\"claudeAiOauth\":{}}", UTF_8);

			HostLogin login = loginPort(root, Map.of("ANTHROPIC_API_KEY", "")).hostLogin();

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
	 * Verifies that the runner receives the session's source as its project, and each read-only path as read-only
	 * evidence.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void passesEvidenceToTheRunnerReadOnly()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
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
			int firstEvidence = options.indexOf("--read-only-evidence");
			requireThat(firstEvidence, "firstEvidence").isGreaterThanOrEqualTo(0);
			requireThat(options.subList(firstEvidence, firstEvidence + 4), "evidenceOptions").isEqualTo(List.of(
				"--read-only-evidence", first.toString(), "--read-only-evidence", second.toString()));
			requireThat(options.contains("--keep-run"), "keepRun").isTrue();
		}
	}

	/**
	 * Verifies that CAT classifies its requested interrupt as Interrupted and retains the parsed windows when the
	 * bridge supplies a terminal completion. ResultMessage documents cancellation after interrupt; the test makes no
	 * claim about the native harness's stop timing.
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
	 * Verifies that a provider rejection for a rate limit holds the harness's sessions until the reported reset time,
	 * and that the session sends no further turn.
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
			String rejected = "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\"," +
				"\"resetsAt\":1790851200,\"rateLimitType\":\"five_hour\",\"unifiedWindows\":{\"five_hour\":" +
				"{\"utilization\":1.0,\"resetsAt\":1790851200}}}}";
			List<String> requests = new CopyOnWriteArrayList<>();
			Instant resetsAt = Instant.ofEpochSecond(1_790_851_200L);
			HarnessCapacity capacity = HarnessCapacity.of(root.resolve("locks"), 1, () ->
			{
				throw new AssertionError("The session must not wait for capacity");
			}, Clock.fixed(resetsAt.minus(Duration.ofHours(1)), ZoneOffset.UTC));
			ClaudeSessions sessions = port(root.resolve("runs"), capacity, Clock.systemUTC(), new CopyOnWriteArrayList<>(),
				List.of(Turn.replies("limit", rejected), Turn.replies("never")), requests);

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

			requireThat(result, "result").isEqualTo(new SessionResult.RateLimited(new HarnessCapacity.Hold(resetsAt, 0),
				List.of(new RateLimitWindow(1.0, resetsAt))));
			requireThat(capacity.hold(), "hold").isEqualTo(new HarnessCapacity.Hold(resetsAt, 0));
			requireThat(requests, "requests").isEqualTo(List.of("one"));
		}
	}

	/**
	 * Verifies that CAT classifies an error result as an infrastructure failure, names the error and sends no further
	 * turn.
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
			String error = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":500," +
				"\"terminal_reason\":\"model_error\",\"result\":\"details\"}";
			List<String> requests = new CopyOnWriteArrayList<>();
			ClaudeSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.replies("oops", error), Turn.replies("never")), requests);

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

			requireThat(result, "result").isEqualTo(new SessionResult.Failed(
				"Claude reported an error result (api_error_status=500, terminal_reason=model_error)", List.of()));
			requireThat(requests, "requests").isEqualTo(List.of("one"));
		}
	}

	/**
	 * Verifies that the instruction files that a Claude Code trial reads at the project root are {@code CLAUDE.md},
	 * {@code .claude/CLAUDE.md}, {@code CLAUDE.local.md} and every Markdown file below {@code .claude/rules}, in that
	 * order, and that other files are left out.
	 *
	 * @throws IOException if the files cannot be written
	 */
	@Test
	public void derivesClaudeInstructions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-instructions-"));
			Path project = Files.createDirectories(root.resolve("project"));
			for (String file : List.of("CLAUDE.md", ".claude/CLAUDE.md", "CLAUDE.local.md", ".claude/rules/style.md",
				".claude/rules/java/testing.md", ".claude/rules/notes.txt", "docs/CLAUDE.md"))
			{
				Path path = project.resolve(file);
				Files.createDirectories(path.getParent());
				Files.writeString(path, "Instructions");
			}
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			ClaudeSessions sessions = ClaudeSessionsSecrets.access().newClaudeSessions(settings, (_, _, _) ->
			{
			});

			requireThat(sessions.derivedSources(project, Optional.empty()).stream().map(DerivedSource::path).toList(),
				"paths").isEqualTo(List.of("CLAUDE.md", ".claude/CLAUDE.md", "CLAUDE.local.md",
				".claude/rules/java/testing.md", ".claude/rules/style.md"));
		}
	}
}
