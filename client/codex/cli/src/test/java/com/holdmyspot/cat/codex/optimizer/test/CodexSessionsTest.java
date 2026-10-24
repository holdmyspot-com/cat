package com.holdmyspot.cat.codex.optimizer.test;

import com.holdmyspot.cat.common.JvmScope;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static com.holdmyspot.cat.common.optimizer.adapter.fakes.test.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.CodexSessionHarness;
import com.holdmyspot.cat.codex.CommandShell;
import com.holdmyspot.cat.codex.optimizer.CodexSessions;
import com.holdmyspot.cat.codex.secrets.CodexSessionsSecrets;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.PortablePaths;
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
import com.holdmyspot.cat.common.optimizer.port.HookInvocation;
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
 * Tests {@link CodexSessions} over the real runner bridge with a scripted harness.
 * <p>
 * Inline rejection and error events use the rate-limit and turn fields documented by codex-cli 0.147.0's
 * <a href="https://github.com/openai/codex/blob/rust-v0.147.0/codex-rs/app-server/README.md">app-server README</a>
 * (Rate limits, Turn events and Errors). Their values are representative parser inputs, not recorded provider outcomes.
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
	 * @param jvm the borrowed process scope
	 * @param runRoot the directory for run directories
	 * @param capacity the shared capacity
	 * @param clock the clock for time limits
	 * @param arguments receives the runner's options for each session
	 * @param script what the harness does, turn by turn
	 * @return the port
	 */
	private static CodexSessions port(JvmScope jvm, Path runRoot, HarnessCapacity capacity, Clock clock,
		List<List<String>> arguments, List<Turn> script)
	{
		return port(jvm, runRoot, capacity, clock, arguments, script, new CopyOnWriteArrayList<>());
	}

	/**
	 * Creates a port and observes the prompts that CAT sends through the bridge.
	 *
	 * @param jvm the borrowed process scope
	 * @param runRoot the directory for run directories
	 * @param capacity the shared capacity
	 * @param clock the clock for time limits
	 * @param arguments receives the runner's options for each session
	 * @param script what the harness does, turn by turn
	 * @param requests receives the decoded prompts in turn order
	 * @return the port
	 */
	private static CodexSessions port(JvmScope jvm, Path runRoot, HarnessCapacity capacity, Clock clock,
		List<List<String>> arguments, List<Turn> script, List<String> requests)
	{
		RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, runRoot, runRoot, List::of, "", false, Map.of(),
			runRoot, capacity, clock, Yielding.fileReplacer());
		return CodexSessionsSecrets.getCodexSessionsAccess().newCodexSessions(settings, (options, input, output) ->
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E20
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-role-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				Path artifact = plugin(RoleRuleFixtures.write(root.resolve("artifact")), "cat");
				RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, artifact, root.resolve("runs"), List::of,
					"", true,
					Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
				List<List<String>> arguments = new CopyOnWriteArrayList<>();
				CodexSessions sessions = CodexSessionsSecrets.getCodexSessionsAccess().
					newCodexSessions(settings, (options, input, output) ->
				{
					arguments.add(options);
					ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "codex",
						List.of(Turn.replies("ok")), input, output);
				});
				// CAT is recognized by its manifest's repository, whatever the user named it
				Path cat = plugin(root.resolve("plugins/workflow"), "workflow");
				Files.writeString(cat.resolve(".codex-plugin/plugin.json"), "{\"name\": \"workflow\", \"repository\": \"" +
					PluginInfo.CAT_REPOSITORY + "\"}\n");
				Path tools = plugin(root.resolve("plugins/tools"), "tools");
				List<Path> plugins = List.of();
				if (role == SessionRole.TRIAL)
					plugins = List.of(tools, cat);

				SessionResult result = await(sessions.start(new SessionRequest(role, partOf(role), source, List.of("brief"),
					List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT, TestWorkspace.NONE, plugins)));

				requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
				requireThat(arguments.size(), "arguments.size()").isEqualTo(1);
				List<String> options = arguments.getFirst();
				// Requirement: R-I9
				requireThat(options, "options").doesNotContain("--session-requests");
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
		Files.createDirectories(directory.resolve(".codex-plugin"));
		Files.writeString(directory.resolve(".codex-plugin/plugin.json"), "{\"name\": \"" + name + "\"}\n");
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E24
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-denied-reads-")).
					toRealPath();
				Path source = Files.createDirectories(root.resolve("source"));
				Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
				Path heldOut = Files.createDirectories(root.resolve("campaign/held_out"));
				Path runRoot = root.resolve("runs");
				Path heldOutRunRoot = root.resolve("runs-held_out");
				RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, artifact, runRoot, () -> List.of(heldOut),
					"",
					false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
				List<List<String>> arguments = new CopyOnWriteArrayList<>();
				List<Boolean> heldOutRunRootExisted = new CopyOnWriteArrayList<>();
				CodexSessions sessions = CodexSessionsSecrets.getCodexSessionsAccess().
					newCodexSessions(settings, (options, input, output) ->
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
	}

	/**
	 * Verifies that a contract author session receives its draft directory and the context file of the contract tools
	 * as the workspace files that the runner copies into the session's project, and that the runner denies the session
	 * reading only the held-out locations, so that the session may run the launcher and write the draft and the
	 * proposal file in its project.
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-contract-author-")).
				toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));
			Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
			Path heldOut = Files.createDirectories(root.resolve("campaign/held_out"));
			Path launcher = root.resolve("image/bin/prompt-optimizer");
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, artifact, root.resolve("runs"),
				() -> List.of(heldOut), "", false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(),
				Yielding.fileReplacer());
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			List<List<String>> received = new CopyOnWriteArrayList<>();
			CodexSessions sessions = CodexSessionsSecrets.getCodexSessionsAccess().
				newCodexSessions(settings, (options, input, output) ->
			{
				arguments.add(options);
				received.add(ContractAuthorFixtures.regularFiles(Path.of(ScriptedHarness.optionValue(options,
					"--workspace"))));
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "codex",
					List.of(Turn.replies("ok")), input, output);
			});

			SessionResult result = await(sessions.start(new SessionRequest(SessionRole.CONTRACT_AUTHOR,
				Part.DEVELOPMENT, source, List.of("brief"), List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT,
				ContractAuthorFixtures.files(JsonMapper.builder().build()), List.of())));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			List<String> options = arguments.getFirst();
			requireThat(ScriptedHarness.optionValue(options, "--project"), "project").isEqualTo(source.toString());
			requireThat(received, "received").isEqualTo(List.of(ContractAuthorFixtures.FILES));
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-session-denied-")).
				toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));
			Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
			JsonMapper json = JsonMapper.builder().build();
			String sessionId = "2c4e6a8b-1d3f-4a5c-9e7b-0f2d4c6e8a1b";
			String otherSessionId = "5a7c9e1b-3d2f-4a6b-8c0e-9f1d3b5a7c2e";
			SessionDirectory session = SessionDirectory.open(root, sessionId, CodexSessionHarness.HARNESS,
				root.resolve("configuration"), Instant.EPOCH, json);
			Path heldOut = Files.createDirectories(session.campaigns().resolve("c1/held_out"));
			Path otherHeldOut = Files.createDirectories(root.resolve(".cat/sessions/" + otherSessionId +
				"/optimizer/c2/held_out"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, artifact, session.runRoot("codex"),
				() -> List.of(heldOut, otherHeldOut), "", false, Map.of(), root, HarnessCapacity.unlimited(),
				Clock.systemUTC(), Yielding.fileReplacer());
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			CodexSessions sessions = CodexSessionsSecrets.getCodexSessionsAccess().
				newCodexSessions(settings, (options, input, output) ->
			{
				arguments.add(options);
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), "codex",
					List.of(Turn.replies("ok")), input, output);
			});

			SessionResult result = await(sessions.start(new SessionRequest(SessionRole.PROMPT_AUTHOR,
				Part.DEVELOPMENT, source, List.of("brief"), List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT)));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			List<String> options = arguments.getFirst();
			Path sessionDirectory = root.resolve(".cat/sessions/" + sessionId);
			requireThat(ScriptedHarness.optionValue(options, "--run-root"), "runRoot").
				isEqualTo(sessionDirectory.resolve("optimizer-runs/codex").toString());
			List<String> denied = new ArrayList<>();
			for (int i = 0; i < options.size() - 1; ++i)
			{
				if (options.get(i).equals("--deny-read"))
					denied.add(options.get(i + 1));
			}
			requireThat(denied, "denied").isEqualTo(List.of(
				sessionDirectory.resolve("optimizer/c1/held_out").toString(),
				root.resolve(".cat/sessions/" + otherSessionId + "/optimizer/c2/held_out").toString(),
				sessionDirectory.resolve("optimizer-runs/codex-held_out").toString()));
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			// Requirement: R-E16
			// Requirement: R-E6
			// Requirement: R-C12
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				List<String> events = new ArrayList<>(recorded("codex-children.jsonl"));
				events.addAll(recorded("codex-hooks.jsonl"));
				List<Turn> script = List.of(Turn.replies("first", events.toArray(String[]::new)),
					Turn.replies("second"));
				List<String> requests = new CopyOnWriteArrayList<>();
				CodexSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-C9
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				List<String> events = recorded("codex-tool-calls.jsonl");
				CodexSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
					new CopyOnWriteArrayList<>(), List.of(Turn.replies("done", events.toArray(String[]::new))));

				SessionResult result = await(sessions.start(request(source, List.of(), "run commands")));

				requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
				SessionResult.Completed completed = (SessionResult.Completed) result;
				requireThat(completed.toolCalls(), "toolCalls").isEqualTo(List.of(
					new ToolCall("commandExecution", "/bin/bash -lc true", 1),
					new ToolCall("commandExecution", "/bin/bash -lc false", 1), new ToolCall("wait", null, null)));
			}
		}
	}

	/**
	 * Creates a port whose runner must never be started.
	 *
	 * @param jvm the borrowed process scope
	 * @param root the directory for run directories and the host configuration
	 * @param environment the host environment
	 * @return the port
	 */
	private static CodexSessions loginPort(JvmScope jvm, Path root, Map<String, String> environment)
	{
		RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of, "", false,
			environment, root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
		return CodexSessionsSecrets.getCodexSessionsAccess().newCodexSessions(settings, (options, input, output) ->
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E7
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Files.writeString(root.resolve("auth.json"), "{\"tokens\":{}}", UTF_8);

				HostLogin login = loginPort(jvm, root, Map.of()).hostLogin();

				requireThat(login, "login").isEqualTo(HostLogin.SUBSCRIPTION);
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E7
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Files.writeString(root.resolve("auth.json"), "{\"tokens\":{}}", UTF_8);

				HostLogin login = loginPort(jvm, root, Map.of("CODEX_API_KEY", "")).hostLogin();

				requireThat(login, "login").isEqualTo(HostLogin.API_KEY);
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E7
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));

				HostLogin login = loginPort(jvm, root, Map.of()).hostLogin();

				requireThat(login, "login").isEqualTo(HostLogin.UNKNOWN);
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				List<List<String>> arguments = new CopyOnWriteArrayList<>();
				CodexSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
					arguments, List.of(Turn.replies("ok")));

				await(sessions.start(request(source, List.of(), "judge")));

				requireThat(arguments.size(), "arguments.size()").isEqualTo(1);
				List<String> options = arguments.get(0);
				requireThat(ScriptedHarness.optionValue(options, "--project"), "project").isEqualTo(source.toString());
				requireThat(options.contains("--keep-run"), "keepRun").isTrue();
			}
		}
	}

	/**
	 * Verifies that a trial asks the runner for session transcripts, so that the tool results its agents received can
	 * be read, and that a judge does not.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void trialsAskForSessionTranscripts()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I7
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				List<List<String>> arguments = new CopyOnWriteArrayList<>();
				CodexSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
					arguments, List.of(Turn.replies("ok")));

				await(sessions.start(request(source, List.of(), "trial")));
				await(sessions.start(new SessionRequest(SessionRole.JUDGE, Part.HELD_OUT, source, List.of("judge"),
					List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT)));

				requireThat(arguments.size(), "arguments.size()").isEqualTo(2);
				requireThat(arguments.get(0).contains("--session-transcripts"), "trial").isTrue();
				requireThat(arguments.get(1).contains("--session-transcripts"), "judge").isFalse();
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-C19
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				List<List<String>> arguments = new CopyOnWriteArrayList<>();
				CodexSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E15
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				CountDownLatch entered = new CountDownLatch(1);
				List<String> events = recorded("codex-children.jsonl");
				List<Turn> script = List.of(Turn.blocksUntilInterrupted(events.toArray(String[]::new)).announcing(entered),
					Turn.replies("never"));
				CodexSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
					new CopyOnWriteArrayList<>(), script);
				SessionHandle handle = sessions.start(request(source, List.of(), "one", "two"));
				requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();

				handle.interrupt();

				SessionResult result = await(handle);
				requireThat(result, "result").isInstanceOf(SessionResult.Interrupted.class);
				requireThat(result.windows().size(), "windows.size()").isEqualTo(1);
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E6
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				String rejected = "{\"method\":\"account/rateLimits/updated\",\"params\":{\"rateLimits\":" +
					"{\"primary\":{\"usedPercent\":100,\"windowDurationMins\":300,\"resetsAt\":1791000000}," +
					"\"secondary\":null,\"rateLimitReachedType\":\"primary\"}}}";
				List<String> requests = new CopyOnWriteArrayList<>();
				Instant resetsAt = Instant.ofEpochSecond(1_791_000_000L);
				HarnessCapacity capacity = HarnessCapacity.of(root.resolve("locks"), 1, () ->
				{
					throw new AssertionError("The session must not wait for capacity");
				}, Clock.fixed(resetsAt.minus(Duration.ofHours(1)), ZoneOffset.UTC));
				CodexSessions sessions = port(jvm, root.resolve("runs"), capacity, Clock.systemUTC(),
					new CopyOnWriteArrayList<>(),
					List.of(Turn.replies("limit", rejected), Turn.replies("never")), requests);

				SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

				requireThat(result, "result").isEqualTo(new SessionResult.RateLimited(new HarnessCapacity.Hold(resetsAt, 0),
					List.of(new RateLimitWindow(1.0, resetsAt))));
				requireThat(capacity.hold(), "hold").isEqualTo(new HarnessCapacity.Hold(resetsAt, 0));
				requireThat(requests, "requests").isEqualTo(List.of("one"));
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				String started = "{\"method\":\"thread/started\",\"params\":{\"thread\":{\"id\":\"root\"," +
					"\"parentThreadId\":null,\"model\":\"gpt-6-luna\",\"reasoningEffort\":\"medium\"}}}";
				String error = "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"root\",\"turn\":" +
					"{\"status\":\"failed\",\"error\":{\"message\":\"details\"}}}}";
				List<String> requests = new CopyOnWriteArrayList<>();
				CodexSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
					new CopyOnWriteArrayList<>(), List.of(Turn.replies("oops", started, error), Turn.replies("never")), requests);

				SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

				requireThat(result, "result").isEqualTo(new SessionResult.Failed(
					"Codex turn finished with status failed", List.of()));
				requireThat(requests, "requests").isEqualTo(List.of("one"));
			}
		}
	}

	/**
	 * Verifies that the instruction file that a Codex trial reads at the project root is {@code AGENTS.override.md}
	 * when it exists and {@code AGENTS.md} otherwise, and that a project with neither has none.
	 *
	 * @throws IOException if the files cannot be written
	 */
	@Test
	public void derivesAgentsInstructions() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-instructions-"));
			Path project = Files.createDirectories(root.resolve("project"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of, "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			CodexSessions sessions = CodexSessionsSecrets.getCodexSessionsAccess().newCodexSessions(settings, (_, _, _) ->
			{
			});

			requireThat(sessions.loadedSources(project, List.of()).trials(), "none").isEmpty();
			Files.writeString(project.resolve("AGENTS.md"), "Instructions");
			requireThat(paths(sessions.loadedSources(project, List.of()).trials()), "agents").
				isEqualTo(List.of("AGENTS.md"));
			Files.writeString(project.resolve("AGENTS.override.md"), "Override");
			requireThat(paths(sessions.loadedSources(project, List.of()).trials()), "override").
				isEqualTo(List.of("AGENTS.override.md"));
		}
	}

	/**
	 * Verifies that an empty override leaves the ordinary project instructions selected, and that two empty
	 * instruction files contribute no derived source.
	 *
	 * @throws IOException if the fixture cannot be written or scanned
	 */
	@Test
	public void ignoresEmptyAgentsInstructions() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-empty-instructions-"));
			Path project = Files.createDirectories(root.resolve("project"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of, "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			CodexSessions sessions = CodexSessionsSecrets.getCodexSessionsAccess().newCodexSessions(settings, (_, _, _) ->
			{
			});
			Files.writeString(project.resolve("AGENTS.override.md"), "");
			Files.writeString(project.resolve("AGENTS.md"), "Project instructions");

			requireThat(paths(sessions.loadedSources(project, List.of()).trials()), "ordinaryInstructions").
				isEqualTo(List.of("AGENTS.md"));

			Files.writeString(project.resolve("AGENTS.md"), "");

			requireThat(sessions.loadedSources(project, List.of()).trials(), "emptyInstructions").isEmpty();
		}
	}

	/**
	 * Returns the paths of derived sources.
	 *
	 * @param sources the sources
	 * @return their paths
	 */
	private static List<String> paths(List<DerivedSource> sources)
	{
		return sources.stream().map(DerivedSource::path).toList();
	}

	/**
	 * Verifies that a project file that a hook of the project's {@code .codex/hooks.json} or a server of the project's
	 * {@code .codex/config.toml} runs is listed with the hook's event or the server's name, and that a program name and
	 * a missing file are left out.
	 *
	 * @throws IOException if the files cannot be written
	 */
	@Test
	public void derivesFilesThatHooksAndServersRun() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-commands-"));
			Path project = Files.createDirectories(root.resolve("project"));
			write(project.resolve(".codex/hooks.json"), """
				{"hooks": {"SessionStart": [{"hooks": [{"type": "command", "command": "bash scripts/start.sh"}]}], \
				"Stop": [{"hooks": [{"type": "command", "command": "./missing.sh"}]}]}}""");
			write(project.resolve(".codex/config.toml"), """
				model = "gpt-5.5"

				[mcp_servers.docs]
				command = "python3"
				args = ["servers/docs.py", "--verbose"]
				""");
			for (String file : List.of("scripts/start.sh", "servers/docs.py"))
				write(project.resolve(file), "script");
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of, "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			CodexSessions sessions = CodexSessionsSecrets.getCodexSessionsAccess().newCodexSessions(settings, (_, _, _) ->
			{
			});

			List<DerivedSource> expected = List.of(
				new DerivedSource("scripts/start.sh", "The SessionStart hook of `.codex/hooks.json` runs this file"),
				new DerivedSource("servers/docs.py", "The MCP server `docs` of `.codex/config.toml` runs this file"));
			LoadedSources loaded = sessions.loadedSources(project, List.of());
			requireThat(loaded.trials(), "trials").isEqualTo(expected);
			requireThat(loaded.production(), "production").isEqualTo(expected);
		}
	}

	/**
	 * Verifies that the user's sessions, but not trials, run each hook of the user's {@code hooks.json}, each plugin
	 * that the user's configuration enables and the trial plugins do not hold, and each MCP server that the user's
	 * configuration does not disable.
	 *
	 * @throws IOException if the files cannot be written
	 */
	@Test
	public void listsUserConfigurationThatTrialsLack() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-user-configuration-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path configuration = root.resolve("codex");
			write(configuration.resolve("hooks.json"), """
				{"hooks": {"SessionStart": [{"hooks": [{"type": "command", "command": "~/start.sh"}]}]}}""");
			write(configuration.resolve("config.toml"), """
				[mcp_servers.docs]
				command = "node"

				[mcp_servers.docs.tools.search]
				approval_mode = "approve"

				[mcp_servers.web]
				url = "https://example.com/mcp"

				[mcp_servers.off]
				command = "off"
				enabled = false

				[plugins."tools@market"]
				enabled = true

				[plugins."extra@market"]
				enabled = true

				[plugins."off@market"]
				enabled = false
				""");
			List<PluginBase> plugins = List.of(new PluginBase(new TrialPlugin("tools@market", "b".repeat(64),
				new TrialPlugin.Installed(), List.of(), null), root.resolve("tools")));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of, "",
				false, Map.of(), configuration, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			CodexSessions sessions = CodexSessionsSecrets.getCodexSessionsAccess().newCodexSessions(settings, (_, _, _) ->
			{
			});

			String hooksFile = PortablePaths.text(configuration.resolve("hooks.json"));
			String configurationFile = PortablePaths.text(configuration.resolve("config.toml"));
			requireThat(sessions.loadedSources(project, plugins).homeOnly(), "homeOnly").isEqualTo(List.of(
				"the SessionStart hook `~/start.sh` of `" + hooksFile + "`",
				"the plugin `extra@market`",
				"the MCP server `docs` of `" + configurationFile + "`",
				"the MCP server `web` of `" + configurationFile + "`"));
		}
	}

	/**
	 * Verifies that the hooks that run before the model acts are the command hooks of {@code .codex/hooks.json} that
	 * run when a session starts or when the user submits a prompt; that each runs in Codex's command shell, with
	 * {@code commandWindows} on Windows; and that each receives the empty harness home in {@code CODEX_HOME}.
	 *
	 * @throws IOException if the files cannot be written
	 */
	@Test
	public void listsEarlyHooks() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-early-hooks-"));
			Path project = Files.createDirectories(root.resolve("project"));
			write(project.resolve(".codex/hooks.json"), """
				{"hooks": {"SessionStart": [{"matcher": "^(startup|resume)$", "hooks": [{"type": "command", \
				"command": "start.sh", "commandWindows": "start.cmd"}]}], \
				"Stop": [{"hooks": [{"type": "command", "command": "stop.sh"}]}]}}""");
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of, "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			CodexSessions sessions = CodexSessionsSecrets.getCodexSessionsAccess().newCodexSessions(settings, (_, _, _) ->
			{
			});
			Path home = root.resolve("home");

			List<HookInvocation> hooks = sessions.earlyHooks(project, List.of(), Optional.of(home), "Hello");

			requireThat(hooks.size(), "hooks.size()").isEqualTo(1);
			HookInvocation start = hooks.getFirst();
			requireThat(start.name(), "name").isEqualTo("the SessionStart hook `start.sh` of `.codex/hooks.json`");
			List<String> command;
			if (CommandShell.current() == CommandShell.POWERSHELL)
				command = CommandShell.POWERSHELL.processArguments("start.cmd", Map.of());
			else
				command = List.of("bash", "-c", "start.sh");
			requireThat(start.command(), "command").isEqualTo(command);
			requireThat(start.environment().get("CODEX_HOME"), "CODEX_HOME").
				isEqualTo(home.resolve(".codex").toString());
		}
	}

	/**
	 * Verifies that the hooks that Codex trials register are those of the project's {@code .codex/hooks.json}, of each
	 * hooks file that a plugin's manifest names and of each declaration that its manifest holds inline, or of the
	 * plugin's {@code hooks/hooks.json} when its manifest declares no hooks; that a hooks file that is not valid JSON
	 * registers none; and that a hooks file that a project-built plugin's mapping copies from the project is named as
	 * the project's file.
	 *
	 * @throws IOException if the files cannot be written
	 */
	@Test
	public void listsHookRegistrations() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-hooks-"));
			Path project = Files.createDirectories(root.resolve("project"));
			write(project.resolve(".codex/hooks.json"), """
				{"hooks": {"PreToolUse": [{"matcher": "shell", "hooks": [{"type": "command", "command": "check.sh"}]}]}}""");
			write(project.resolve("plugin/hooks/hooks.json"), """
				{"hooks": {"UserPromptSubmit": [{"hooks": [{"type": "command", "command": "mapped.sh"}]}]}}""");

			Path tools = root.resolve("tools");
			write(tools.resolve(".codex-plugin/plugin.json"), """
				{"name": "tools", "hooks": ["./extra/hooks.json", "./broken.json", \
				{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "stop.sh"}]}]}}]}""");
			write(tools.resolve("hooks/hooks.json"), """
				{"hooks": {"PostToolUse": [{"hooks": [{"type": "command", "command": "unused.sh"}]}]}}""");
			write(tools.resolve("extra/hooks.json"), """
				{"hooks": {"SessionStart": [{"matcher": "startup", "hooks": [{"type": "command", "command": "a.sh"}, \
				{"type": "command", "command": "b.sh"}]}]}}""");
			write(tools.resolve("broken.json"), "{not json");
			Path cat = root.resolve("cat");
			write(cat.resolve(".codex-plugin/plugin.json"), "{\"name\": \"cat\"}");
			write(cat.resolve("hooks/hooks.json"), """
				{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "base.sh"}]}]}}""");

			List<PluginBase> plugins = List.of(
				new PluginBase(new TrialPlugin("tools@market", "b".repeat(64), new TrialPlugin.Installed(), List.of(), null),
					tools),
				new PluginBase(new TrialPlugin("cat", "a".repeat(64), new TrialPlugin.Build(List.of("build"),
					Path.of("out")), List.of(new TrialPlugin.Mapping(Path.of("plugin/hooks"), Path.of("hooks"))), null), cat));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of, "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			CodexSessions sessions = CodexSessionsSecrets.getCodexSessionsAccess().newCodexSessions(settings, (_, _, _) ->
			{
			});

			requireThat(sessions.hookRegistrations(project, plugins), "registrations").isEqualTo(List.of(
				new HookRegistration("", ".codex/hooks.json", "PreToolUse", "shell", "check.sh"),
				new HookRegistration("tools@market", "extra/hooks.json", "SessionStart", "startup", "a.sh"),
				new HookRegistration("tools@market", "extra/hooks.json", "SessionStart", "startup", "b.sh"),
				new HookRegistration("tools@market", ".codex-plugin/plugin.json", "Stop", "", "stop.sh"),
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
