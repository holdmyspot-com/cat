package com.holdmyspot.cat.claude.optimizer.test;

import static com.holdmyspot.cat.common.optimizer.adapter.fakes.test.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.ClaudeSessionHarness;
import com.holdmyspot.cat.claude.optimizer.ClaudeSessions;
import com.holdmyspot.cat.claude.secrets.ClaudeSessionsSecrets;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.adapter.fakes.test.ContractAuthorFixtures;
import com.holdmyspot.cat.common.optimizer.adapter.fakes.test.RoleRuleFixtures;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.ToolCall;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspace;
import com.holdmyspot.cat.common.optimizer.port.HookRegistration;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;
import com.holdmyspot.cat.common.optimizer.port.UnreachableReference;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness;

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
import tools.jackson.databind.json.JsonMapper;

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
	private static final Path TEMP = Path.of(System.getProperty("cat.session.temp"));

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
	 * Verifies that a trial passes exactly its plugins to the runner, CAT as the artifact to install and every other
	 * plugin on its own, that a trial without CAT does not install it, and that every other session installs no plugin
	 * and runs from the port's artifact.
	 *
	 * @param role the session's role
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(dataProvider = "sessionRoles", timeOut = 120_000)
	public void passesPluginsOnlyForTrialSessions(SessionRole role)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E20
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-role-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			Path artifact = plugin(RoleRuleFixtures.write(root.resolve("artifact")), "cat");
			RunnerSessions.Settings settings = new RunnerSessions.Settings(artifact, root.resolve("runs"), List::of, "", true,
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			ClaudeSessions sessions = ClaudeSessionsSecrets.access().newClaudeSessions(settings, (options, input, output) ->
			{
				arguments.add(options);
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "claude",
					List.of(Turn.replies("ok")), input, output);
			});
			Path cat = plugin(root.resolve("plugins/cat"), "cat");
			Path tools = plugin(root.resolve("plugins/tools"), "tools");
			List<Path> plugins = List.of();
			if (role == SessionRole.TRIAL)
				plugins = List.of(tools, cat);

			SessionResult result = await(sessions.start(new SessionRequest(role, partOf(role), source, List.of("brief"),
				List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT, TestWorkspace.NONE, plugins)));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			requireThat(arguments.size(), "arguments.size()").isEqualTo(1);
			List<String> options = arguments.getFirst();
			if (role == SessionRole.TRIAL)
			{
				requireThat(options, "options").contains("--install-cat");
				requireThat(ScriptedHarness.optionValue(options, "--artifact"), "artifact").isEqualTo(cat.toString());
				requireThat(options.subList(0, 2), "plugin").isEqualTo(List.of("--plugin", tools.toString()));
				requireThat(options.lastIndexOf("--plugin"), "lastPlugin").isEqualTo(0);

				requireThat(await(sessions.start(new SessionRequest(role, partOf(role), source, List.of("brief"),
					List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT, TestWorkspace.NONE, List.of(tools)))), "withoutCat").
					isInstanceOf(SessionResult.Completed.class);
				requireThat(arguments.getLast(), "withoutCatOptions").doesNotContain("--install-cat");
				requireThat(ScriptedHarness.optionValue(arguments.getLast(), "--artifact"), "withoutCatArtifact").
					isEqualTo(artifact.toString());
				return;
			}
			requireThat(options, "options").doesNotContain("--install-cat").doesNotContain("--plugin");
			requireThat(ScriptedHarness.optionValue(options, "--artifact"), "artifact").isEqualTo(artifact.toString());
		}
	}

	/**
	 * Writes a plugin manifest into a directory.
	 *
	 * @param directory the plugin directory
	 * @param name the plugin's name
	 * @return the directory
	 * @throws IOException if the manifest cannot be written
	 */
	private static Path plugin(Path directory, String name) throws IOException
	{
		Files.createDirectories(directory.resolve(".claude-plugin"));
		Files.writeString(directory.resolve(".claude-plugin/plugin.json"), "{\"name\": \"" + name + "\"}\n");
		return directory;
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
	 * Verifies that a contract author session receives its draft directory and the context file of the contract tools as
	 * the workspace files that the runner copies into the session's project, that nothing passes the project read-only,
	 * and that the runner denies the session reading only the held-out locations, so that the session may run the
	 * launcher and write the draft and the proposal file in its project.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void contractAuthorWritesDraftAndRunsTools()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-contract-author-")).
				toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));
			Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
			Path heldOut = Files.createDirectories(root.resolve("campaign/held_out"));
			Path launcher = root.resolve("image/bin/prompt-optimizer");
			RunnerSessions.Settings settings = new RunnerSessions.Settings(artifact, root.resolve("runs"),
				() -> List.of(heldOut), "", false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			List<List<String>> received = new CopyOnWriteArrayList<>();
			ClaudeSessions sessions = ClaudeSessionsSecrets.access().newClaudeSessions(settings, (options, input, output) ->
			{
				arguments.add(options);
				received.add(ContractAuthorFixtures.regularFiles(Path.of(ScriptedHarness.optionValue(options,
					"--workspace"))));
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "claude",
					List.of(Turn.replies("ok")), input, output);
			});

			SessionResult result = await(sessions.start(new SessionRequest(SessionRole.CONTRACT_AUTHOR,
				Part.DEVELOPMENT, source, List.of("brief"), List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT,
				ContractAuthorFixtures.files(JsonMapper.builder().build()), List.of())));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			List<String> options = arguments.getFirst();
			requireThat(ScriptedHarness.optionValue(options, "--project"), "project").isEqualTo(source.toString());
			requireThat(received, "received").isEqualTo(List.of(ContractAuthorFixtures.FILES));
			requireThat(options, "options").doesNotContain("--read-only-evidence");
			List<Path> denied = new ArrayList<>();
			for (int i = 0; i < options.size() - 1; ++i)
			{
				if (options.get(i).equals("--deny-read"))
					denied.add(Path.of(options.get(i + 1)));
			}
			requireThat(denied, "denied").isEqualTo(List.of(heldOut, root.resolve("runs-held_out")));
			for (Path path : denied)
				requireThat(launcher.startsWith(path), "launcher.startsWith(" + path + ")").isFalse();
		}
	}

	/**
	 * Verifies that a development session started for a campaign in a session's CAT session directory runs under that
	 * session's run root and is denied reading the {@code held_out} directories of the campaigns of every session and
	 * the session's held-out run root.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void deniesHeldOutReadsInSessionDirectory()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-session-denied-")).
				toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));
			Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
			JsonMapper json = JsonMapper.builder().build();
			String sessionId = "2c4e6a8b-1d3f-4a5c-9e7b-0f2d4c6e8a1b";
			String otherSessionId = "5a7c9e1b-3d2f-4a6b-8c0e-9f1d3b5a7c2e";
			SessionDirectory session = SessionDirectory.open(root, sessionId, ClaudeSessionHarness.HARNESS,
				root.resolve("configuration"), Instant.EPOCH, json);
			Path heldOut = Files.createDirectories(session.campaigns().resolve("c1/held_out"));
			Path otherHeldOut = Files.createDirectories(root.resolve(".cat/sessions/" + otherSessionId +
				"/optimizer/c2/held_out"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(artifact, session.runRoot("claude"),
				() -> List.of(heldOut, otherHeldOut), "", false, Map.of(), root, HarnessCapacity.unlimited(),
				Clock.systemUTC());
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			ClaudeSessions sessions = ClaudeSessionsSecrets.access().newClaudeSessions(settings, (options, input, output) ->
			{
				arguments.add(options);
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "claude",
					List.of(Turn.replies("ok")), input, output);
			});

			SessionResult result = await(sessions.start(new SessionRequest(SessionRole.PROMPT_AUTHOR,
				Part.DEVELOPMENT, source, List.of("brief"), List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT)));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			List<String> options = arguments.getFirst();
			Path sessionDirectory = root.resolve(".cat/sessions/" + sessionId);
			requireThat(ScriptedHarness.optionValue(options, "--run-root"), "runRoot").
				isEqualTo(sessionDirectory.resolve("optimizer-runs/claude").toString());
			List<String> denied = new ArrayList<>();
			for (int i = 0; i < options.size() - 1; ++i)
			{
				if (options.get(i).equals("--deny-read"))
					denied.add(options.get(i + 1));
			}
			requireThat(denied, "denied").isEqualTo(List.of(
				sessionDirectory.resolve("optimizer/c1/held_out").toString(),
				root.resolve(".cat/sessions/" + otherSessionId + "/optimizer/c2/held_out").toString(),
				sessionDirectory.resolve("optimizer-runs/claude-held_out").toString()));
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

			requireThat(sessions.loadedSources(project, List.of()).trials().stream().map(DerivedSource::path).toList(),
				"paths").isEqualTo(List.of("CLAUDE.md", ".claude/CLAUDE.md", "CLAUDE.local.md",
				".claude/rules/java/testing.md", ".claude/rules/style.md"));
		}
	}

	/**
	 * Verifies that the files that an instruction file imports through {@code @path} are listed after it, resolved
	 * against the importing file's directory, for at most four hops; and that a path in a code span or fenced code
	 * block, an email address, a missing file, a file outside the project and a file already listed are left out.
	 *
	 * @throws IOException if the files cannot be written
	 */
	@Test
	public void derivesImportedInstructions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-imports-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Map<String, String> files = Map.of(
				"CLAUDE.md", """
					@AGENTS.md
					Mention `@docs/span.md` or write to user@example.com, but read @docs/design\\ notes.md first.
					```
					@docs/fenced.md
					```
					@docs/a.md @docs/missing.md @../outside.md
					""",
				"AGENTS.md", "Answer briefly. @CLAUDE.md",
				"docs/a.md", "@b.md",
				"docs/b.md", "@c.md",
				"docs/c.md", "@d.md",
				"docs/d.md", "@e.md",
				"docs/e.md", "Too deep",
				"docs/design notes.md", "Design",
				"docs/span.md", "Span",
				"docs/fenced.md", "Fenced");
			for (Map.Entry<String, String> file : files.entrySet())
			{
				Path path = project.resolve(file.getKey());
				Files.createDirectories(path.getParent());
				Files.writeString(path, file.getValue());
			}
			Files.writeString(root.resolve("outside.md"), "Outside");
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			ClaudeSessions sessions = ClaudeSessionsSecrets.access().newClaudeSessions(settings, (_, _, _) ->
			{
			});

			requireThat(sessions.loadedSources(project, List.of()).trials().stream().map(DerivedSource::path).toList(),
				"paths").isEqualTo(List.of("CLAUDE.md", "AGENTS.md", "docs/design notes.md", "docs/a.md", "docs/b.md",
				"docs/c.md", "docs/d.md"));
		}
	}

	/**
	 * Verifies that an import of a missing file, and an import of a file outside the project, are reported as
	 * references that trials cannot receive, while an import that resolves in the project and a mention that names no
	 * file, such as a package scope, are not.
	 */
	@Test
	public void reportsUnreachableImports() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-imports-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve("CLAUDE.md"), """
				@AGENTS.md
				Install @types/node, then read @docs/missing.md and @../outside.md and @~/notes.md first
				""");
			Files.writeString(project.resolve("AGENTS.md"), "@./gone.txt");
			Files.writeString(root.resolve("outside.md"), "Outside");
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			ClaudeSessions sessions = ClaudeSessionsSecrets.access().newClaudeSessions(settings, (_, _, _) ->
			{
			});

			requireThat(sessions.loadedSources(project, List.of()).unreachable(), "unreachable").isEqualTo(List.of(
				new UnreachableReference("AGENTS.md", "./gone.txt", UnreachableReference.Problem.MISSING),
				new UnreachableReference("CLAUDE.md", "docs/missing.md", UnreachableReference.Problem.MISSING),
				new UnreachableReference("CLAUDE.md", "../outside.md", UnreachableReference.Problem.OUTSIDE_PROJECT),
				new UnreachableReference("CLAUDE.md", "~/notes.md", UnreachableReference.Problem.OUTSIDE_PROJECT)));
		}
	}

	/**
	 * Verifies that a project file that a hook of the project's settings or a server of the project's {@code .mcp.json}
	 * runs is listed with the hook's event or the server's name, whether the command names it through
	 * {@code CLAUDE_PROJECT_DIR} or relative to the project; and that a program name, a missing file and a file that is
	 * already listed are left out.
	 *
	 * @throws IOException if the files cannot be written
	 */
	@Test
	public void derivesFilesThatHooksAndServersRun() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-commands-"));
			Path project = Files.createDirectories(root.resolve("project"));
			write(project.resolve(".claude/settings.json"), """
				{"hooks": {"SessionStart": [{"hooks": [{"type": "command", \
				"command": "\\"$CLAUDE_PROJECT_DIR\\"/scripts/inject.sh"}]}], \
				"UserPromptSubmit": [{"hooks": [{"type": "command", \
				"command": "python3 tools/prompt.py --quiet && node missing.js"}]}]}}""");
			write(project.resolve(".claude/settings.local.json"), """
				{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "${CLAUDE_PROJECT_DIR}/scripts/inject.sh"}, \
				{"type": "command", "command": "$HOME/stop.sh"}]}]}}""");
			write(project.resolve(".mcp.json"), """
				{"mcpServers": {"docs": {"command": "node", "args": ["servers/docs.js"]}, \
				"web": {"type": "http", "url": "https://example.com/mcp"}}}""");
			for (String file : List.of("scripts/inject.sh", "tools/prompt.py", "servers/docs.js"))
				write(project.resolve(file), "script");
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			ClaudeSessions sessions = ClaudeSessionsSecrets.access().newClaudeSessions(settings, (_, _, _) ->
			{
			});

			List<DerivedSource> expected = List.of(
				new DerivedSource("scripts/inject.sh", "The SessionStart hook of `.claude/settings.json` runs this file"),
				new DerivedSource("tools/prompt.py",
					"The UserPromptSubmit hook of `.claude/settings.json` runs this file"),
				new DerivedSource("servers/docs.js", "The MCP server `docs` of `.mcp.json` runs this file"));
			LoadedSources loaded = sessions.loadedSources(project, List.of());
			requireThat(loaded.trials(), "trials").isEqualTo(expected);
			requireThat(loaded.production(), "production").isEqualTo(expected);
		}
	}

	/**
	 * Verifies that the hooks that Claude Code trials register are those of the project's shared and local settings,
	 * of each plugin's {@code hooks/hooks.json}, of each hooks file that its manifest names and of each declaration
	 * that its manifest holds inline; that a settings file that is not valid JSON registers none; and that a hooks file
	 * that a project-built plugin's mapping copies from the project is named as the project's file.
	 *
	 * @throws IOException if the files cannot be written
	 */
	@Test
	public void listsHookRegistrations() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-hooks-"));
			Path project = Files.createDirectories(root.resolve("project"));
			write(project.resolve(".claude/settings.json"), """
				{"hooks": {"PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "check.sh"}]}]}}""");
			write(project.resolve(".claude/settings.local.json"), "{not json");
			write(project.resolve("plugin/hooks/hooks.json"), """
				{"hooks": {"UserPromptSubmit": [{"hooks": [{"type": "command", "command": "mapped.sh"}]}]}}""");

			Path tools = root.resolve("tools");
			write(tools.resolve(".claude-plugin/plugin.json"), """
				{"name": "tools", "hooks": ["./extra/hooks.json", {"Stop": [{"hooks": [{"type": "prompt"}]}]}]}""");
			write(tools.resolve("hooks/hooks.json"), """
				{"hooks": {"PostToolUse": [{"matcher": "Edit|Write", "hooks": [{"type": "command", "command": "a.sh"}, \
				{"type": "command", "command": "b.sh"}]}]}}""");
			write(tools.resolve("extra/hooks.json"), """
				{"hooks": {"SessionStart": [{"hooks": [{"type": "command", "command": "start.sh"}]}]}}""");
			Path cat = root.resolve("cat");
			write(cat.resolve(".claude-plugin/plugin.json"), "{\"name\": \"cat\"}");
			write(cat.resolve("hooks/hooks.json"), """
				{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "base.sh"}]}]}}""");

			List<PluginBase> plugins = List.of(
				new PluginBase(new TrialPlugin("tools@market", "b".repeat(64), new TrialPlugin.Installed(), List.of()),
					tools),
				new PluginBase(new TrialPlugin("cat", "a".repeat(64), new TrialPlugin.Build(List.of("build"),
					Path.of("out")), List.of(new TrialPlugin.Mapping(Path.of("plugin/hooks"), Path.of("hooks")))), cat));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			ClaudeSessions sessions = ClaudeSessionsSecrets.access().newClaudeSessions(settings, (_, _, _) ->
			{
			});

			requireThat(sessions.hookRegistrations(project, plugins), "registrations").isEqualTo(List.of(
				new HookRegistration("", ".claude/settings.json", "PreToolUse", "Bash", "check.sh"),
				new HookRegistration("tools@market", "hooks/hooks.json", "PostToolUse", "Edit|Write", "a.sh"),
				new HookRegistration("tools@market", "hooks/hooks.json", "PostToolUse", "Edit|Write", "b.sh"),
				new HookRegistration("tools@market", "extra/hooks.json", "SessionStart", "", "start.sh"),
				new HookRegistration("tools@market", ".claude-plugin/plugin.json", "Stop", "", "prompt"),
				new HookRegistration("", "plugin/hooks/hooks.json", "UserPromptSubmit", "", "mapped.sh")));
		}
	}

	/**
	 * Writes a file and creates its directory.
	 *
	 * @param file    the file
	 * @param content the file's content
	 * @throws IOException if the file cannot be written
	 */
	private static void write(Path file, String content) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}
}
