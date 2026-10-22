package com.holdmyspot.cat.common.optimizer.adapter.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static com.holdmyspot.cat.common.optimizer.adapter.fakes.test.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.adapter.fakes.test.RoleRuleFixtures;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspace;
import com.holdmyspot.cat.common.optimizer.contract.WorkspaceFile;
import com.holdmyspot.cat.common.optimizer.port.FollowUp;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringWriter;
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
import java.util.stream.Collectors;

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
	private static final Path TEMP = Path.of(System.getProperty("cat.session.temp"));
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
			Map.of(), runRoot, capacity, clock, Yielding.fileReplacer());
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
		try
		{
			return handle.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
		}
		catch (TimeoutException e)
		{
			// Only the Windows CI runner has been seen to hang here, so the report shows where every thread waited
			TimeoutException timeout = new TimeoutException("The session did not end within 60 seconds. Threads:\n" +
				describeThreads());
			timeout.initCause(e);
			throw timeout;
		}
	}

	/**
	 * Returns the name, state and stack trace of every live thread.
	 *
	 * @return the description
	 */
	private static String describeThreads()
	{
		StringBuilder description = new StringBuilder();
		for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet())
		{
			description.append('"').append(entry.getKey().getName()).append("\" ").
				append(entry.getKey().getState()).append('\n');
			for (StackTraceElement frame : entry.getValue())
				description.append("\tat ").append(frame).append('\n');
		}
		return description.toString();
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
	 * Verifies that a trial that runs on the control source passes the harness's control-source options and that
	 * another trial does not.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void passesControlSourceOptions()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-W10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			TestSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				arguments, List.of(Turn.replies("ok")));

			for (boolean controlSource : List.of(true, false))
			{
				await(sessions.start(new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, source, List.of("go"),
					List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT, TestWorkspace.NONE, List.of(), Optional.empty(),
					controlSource)));
			}

			requireThat(arguments.get(0), "control").contains("--control-source");
			requireThat(arguments.get(1), "formal").doesNotContain("--control-source");
		}
	}

	/**
	 * Verifies that a trial's runner receives, while it runs, a workspace directory that holds the trial's fixture
	 * with the test case's generated value written out in place of its placeholder.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void passesGeneratedFixtureToRunner()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-C21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			TestValue.Generated lines = new TestValue.Generated("log_lines", 1, 11, Map.of("count", 3L));
			TestWorkspace files = new TestWorkspace(Map.of("logs/build.log",
				new WorkspaceFile("logs/build.log", "{{before}}\nBuild finished on {{expected_outcome}}.\n")),
				Map.of("before", lines), "2025-03-04");
			List<String> received = new CopyOnWriteArrayList<>();
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "",
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				Path workspace = Path.of(ScriptedHarness.optionValue(options, "--workspace"));
				received.add(Files.readString(workspace.resolve("logs/build.log")));
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), TestSessions.HARNESS,
					List.of(Turn.replies("ok")), input, output);
			});

			await(sessions.start(new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, source, List.of("go"),
				List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT, files, List.of())));

			StringWriter generated = new StringWriter();
			lines.write(generated);
			requireThat(received, "received").isEqualTo(List.of(generated + "\nBuild finished on 2025-03-04.\n"));
		}
	}

	/**
	 * Verifies that a trial installs exactly its trial plugins: the first one that is CAT as CAT, and each other one as
	 * a plugin; that a trial without trial plugins installs none, not even CAT, whatever the settings say; and that a
	 * session in another role installs none.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	// Requirement: R-W12
	@Test(timeOut = 120_000)
	public void installsTrialPlugins()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			Path artifact = manifest(root.resolve("artifact"), "cat", PluginInfo.CAT_REPOSITORY);
			// CAT is recognized by its manifest's repository, whatever the user named it, and a plugin that takes
			// CAT's name without its repository is not CAT
			Path catCopy = manifest(root.resolve("plugins/workflow"), "workflow", PluginInfo.CAT_REPOSITORY);
			Path otherCopy = manifest(root.resolve("plugins/cat"), "cat", "https://example.com/cat");
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			RunnerSessions.Settings settings = new RunnerSessions.Settings(artifact, root.resolve("runs"), List::of, "",
				true, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				arguments.add(options);
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), TestSessions.HARNESS,
					List.of(Turn.replies("ok")), input, output);
			});

			for (List<Path> plugins : List.of(List.of(otherCopy, catCopy), List.of(otherCopy), List.<Path>of()))
			{
				await(sessions.start(new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, source, List.of("go"),
					List.of(), LIMIT, TrialProfile.HARNESS_DEFAULT, TestWorkspace.NONE, plugins)));
			}
			await(sessions.start(request(SessionRole.JUDGE, TrialProfile.HARNESS_DEFAULT, source, List.of(), "go")));

			List<String> withCat = arguments.get(0);
			requireThat(ScriptedHarness.optionValue(withCat, "--artifact"), "withCatArtifact").
				isEqualTo(catCopy.toString());
			requireThat(ScriptedHarness.optionValue(withCat, "--plugin"), "withCatPlugin").
				isEqualTo(otherCopy.toString());
			requireThat(withCat, "withCat").contains("--install-cat");
			List<String> alone = arguments.get(1);
			requireThat(ScriptedHarness.optionValue(alone, "--plugin"), "alonePlugin").isEqualTo(otherCopy.toString());
			requireThat(alone, "alone").doesNotContain("--install-cat");
			for (int index = 2; index < 4; ++index)
			{
				List<String> none = arguments.get(index);
				requireThat(none, "none").doesNotContain("--install-cat").doesNotContain("--plugin");
			}
		}
	}

	/**
	 * Verifies that the files that trials load from a CAT trial plugin built from the project are the harness's
	 * instruction file and every file that CAT's rules give the trial's agent from the plugin's base, the user's rules
	 * and the project's rules. A base file that a mapping copies from the project is named as the project's file, and
	 * other files outside the project by their absolute paths.
	 *
	 * @throws IOException if the files cannot be written or read
	 */
	@Test
	public void loadsSourcesOfBuiltCat() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-")).
				toRealPath();
			Path artifact = manifest(root.resolve("artifact"), "cat", PluginInfo.CAT_REPOSITORY);
			Path base = manifest(root.resolve("base"), "cat", PluginInfo.CAT_REPOSITORY);
			Path project = Files.createDirectories(root.resolve("project"));
			Path home = Files.createDirectories(root.resolve("home"));
			write(base.resolve("rules/common/default.md"), "Load `../include/common/lazy.md`.");
			write(base.resolve("rules/include/common/lazy.md"), "Lazy guidance");
			write(project.resolve("rules/include/common/lazy.md"), "Lazy guidance");
			write(project.resolve(TestSessions.INSTRUCTIONS), "Project instructions");
			write(project.resolve(".cat/rules/common/project.md"), "Project rule");
			write(home.resolve(".cat/rules/common/personal.md"), "Personal rule");
			TrialPlugin plugin = new TrialPlugin("cat", "0".repeat(64), new TrialPlugin.Build(List.of("build"),
				Path.of("out")), List.of(new TrialPlugin.Mapping(Path.of("rules/include"), Path.of("rules/include"))), null);
			TestSessions sessions = new TestSessions(new RunnerSessions.Settings(artifact, root.resolve("runs"),
				List::of, "", false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(),
				Yielding.fileReplacer(), Optional.of(home.resolve(".cat/rules"))), (_, _, _) ->
			{
			});

			List<DerivedSource> sources = sessions.loadedSources(project, List.of(new PluginBase(plugin, base))).
				trials();

			String defaultRule = base.resolve("rules/common/default.md").toString();
			requireThat(sources, "sources").isEqualTo(List.of(
				new DerivedSource(TestSessions.INSTRUCTIONS, TestSessions.INSTRUCTIONS_RULE),
				new DerivedSource(defaultRule, "CAT injects this rule into every session"),
				new DerivedSource(home.resolve(".cat/rules/common/personal.md").toString(),
					"CAT injects this rule into every session"),
				new DerivedSource(".cat/rules/common/project.md", "CAT injects this rule into every session"),
				new DerivedSource("rules/include/common/lazy.md", "`" + defaultRule + "` tells the agent to load " +
					"this file")));
		}
	}

	/**
	 * Verifies that CAT's rules from the installed artifact load in production exactly when the settings install CAT,
	 * that they never load in trials without a CAT trial plugin, and that they are then listed as loading in
	 * production only.
	 *
	 * @throws IOException if the files cannot be written or read
	 */
	@Test
	public void loadsInstalledCatInProductionOnly() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-")).
				toRealPath();
			Path artifact = manifest(root.resolve("artifact"), "cat", PluginInfo.CAT_REPOSITORY);
			Path project = Files.createDirectories(root.resolve("project"));
			Path docs = manifest(root.resolve("docs"), "docs", "https://example.com/docs");
			Path rule = artifact.resolve("rules/common/default.md");
			write(rule, "Artifact rule");
			DerivedSource artifactRule = new DerivedSource(rule.toString(), "CAT injects this rule into every session");
			TrialPlugin plugin = new TrialPlugin("docs@market", "0".repeat(64), new TrialPlugin.Installed(),
				List.of(), null);

			for (boolean installCat : List.of(true, false))
			{
				TestSessions sessions = new TestSessions(new RunnerSessions.Settings(artifact, root.resolve("runs"),
					List::of, "", installCat, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(),
					Yielding.fileReplacer()),
					(_, _, _) ->
				{
				});
				LoadedSources loaded = sessions.loadedSources(project, List.of(new PluginBase(plugin, docs)));

				List<DerivedSource> production = List.of();
				List<String> productionOnly = List.of();
				if (installCat)
				{
					production = List.of(artifactRule);
					productionOnly = List.of(rule.toString());
				}
				requireThat(loaded.production(), "production").isEqualTo(production);
				requireThat(loaded.trials(), "trials").isEmpty();
				requireThat(loaded.productionOnly(), "productionOnly").isEqualTo(productionOnly);
			}
		}
	}

	/**
	 * Writes a file, creating its directory.
	 *
	 * @param file the file
	 * @param content its content
	 * @throws IOException if the file cannot be written
	 */
	private static void write(Path file, String content) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	/**
	 * Creates a plugin directory whose Codex manifest has a name and a repository.
	 *
	 * @param directory  the plugin directory
	 * @param name       the manifest's name
	 * @param repository the manifest's repository
	 * @return {@code directory}
	 * @throws IOException if the manifest cannot be written
	 */
	private static Path manifest(Path directory, String name, String repository) throws IOException
	{
		Path manifest = Files.createDirectories(directory.resolve(".codex-plugin")).resolve("plugin.json");
		Files.writeString(manifest, "{\"name\": \"" + name + "\", \"repository\": \"" + repository + "\"}");
		return directory;
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
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC(),
					Yielding.fileReplacer());
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
	 * Verifies that a session sends the turn that its follow-up chooses from the replies to the listed turns, and that
	 * the result includes the reply to that turn.
	 *
	 * @throws IOException if the run directory cannot be created
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void sendsFollowUpTurn() throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "", false,
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			List<String> sent = new CopyOnWriteArrayList<>();
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC(),
					Yielding.fileReplacer());
				HarnessRun run = manager.create(TestSessions.HARNESS);
				manager.initialize(run, root, root, "scripted", List.of("scripted"));
				output.write("{\"type\":\"started\",\"run_id\":\"" + run.id() + "\"}\n");
				BufferedReader requests = new BufferedReader(input);
				for (int turn = 0; turn < 3; ++turn)
				{
					sent.add(requests.readLine());
					output.write("{\"type\":\"turn_completed\",\"assistant_response\":\"reply-" + turn + "\"," +
						"\"assistant_messages\":[\"reply-" + turn + "\"]}\n");
				}
				requests.readLine();
			});
			SessionRequest listed = request(root, List.of(), "one", "two");
			FollowUp followUp = completed -> Optional.of("after " + completed.responses().getLast().getLast());
			SessionRequest request = new SessionRequest(listed.role(), listed.part(), listed.source(), listed.turns(),
				listed.readOnly(), listed.timeLimit(), listed.profile(), listed.files(), listed.plugins(),
				Optional.of(followUp));

			SessionResult result = await(sessions.start(request));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			requireThat(((SessionResult.Completed) result).responses(), "responses").isEqualTo(
				List.of(List.of("reply-0"), List.of("reply-1"), List.of("reply-2")));
			requireThat(sent.getLast(), "followUpTurn").contains("after reply-1");
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
				HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				HarnessRunManager manager = new HarnessRunManager(runs, Clock.systemUTC(), Yielding.fileReplacer());
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
			}, Clock.systemUTC());
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
			}, Clock.systemUTC());
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
	 * and identifies the failed turn.
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
	 * Verifies that CAT classifies a turn that the harness rejects as an infrastructure failure as soon as the turn
	 * fails, naming the turn and the harness's reason, rather than waiting for the session's time limit.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void classifiesRejectedTurnAsFailure()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			TestSessions sessions = port(root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
				new CopyOnWriteArrayList<>(), List.of(Turn.replies("first"), Turn.rejected()));

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

			requireThat(result, "result").isEqualTo(new SessionResult.Failed(
				"Turn 2 failed: The harness rejected the turn", List.of()));
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
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
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
				{SessionRole.CONTRACT_AUTHOR, "contract author"},
				{SessionRole.JUDGE, "judge"},
				{SessionRole.TRIAGE, "triage"},
				{SessionRole.PROMPT_AUTHOR, "prompt author"}
			};
	}

	/**
	 * Verifies that a contract author, judge, triage or prompt author session that reaches its time limit fails with a
	 * reason that names the session and the limit, instead of completing with the replies that it gave before the
	 * limit.
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

	/**
	 * Creates a port whose runner plays a script and records the turn requests that reach the harness.
	 *
	 * @param runRoot the directory for run directories
	 * @param capacity the shared capacity
	 * @param clock the clock for time limits
	 * @param script what the harness does, turn by turn
	 * @param requests receives each turn request that reaches the harness
	 * @return the port
	 */
	private static TestSessions recordingPort(Path runRoot, HarnessCapacity capacity, Clock clock, List<Turn> script,
		List<String> requests)
	{
		RunnerSessions.Settings settings = new RunnerSessions.Settings(runRoot, runRoot, List::of, "", false,
			Map.of(), runRoot, capacity, clock, Yielding.fileReplacer());
		return new TestSessions(settings, (options, input, output) ->
			ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), TestSessions.HARNESS,
				script, input, output, requests));
	}

	/**
	 * Creates a capacity pool over a test-owned directory whose holds follow a test-controlled clock, and that fails
	 * the test if a session waits for it.
	 *
	 * @param directory the directory that holds the slot and hold files
	 * @param clock the clock that decides whether a hold lasts
	 * @return the pool
	 */
	private static HarnessCapacity heldBy(Path directory, ManualClock clock)
	{
		return HarnessCapacity.of(directory, 1, () ->
		{
			throw new AssertionError("The session must not wait for capacity");
		}, clock);
	}

	/**
	 * Verifies that a session whose provider refused a request records the refusal in the harness's shared hold,
	 * reports the session as refused rather than rate-limited, and sends no further turn.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void refusedSessionHoldsTheHarness()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			ManualClock clock = new ManualClock(START);
			HarnessCapacity capacity = heldBy(root.resolve("locks"), clock);
			List<String> requests = new CopyOnWriteArrayList<>();
			TestSessions sessions = recordingPort(root.resolve("runs"), capacity, clock,
				List.of(Turn.replies("refused", TestSessions.REFUSED_EVENT), Turn.replies("never")), requests);

			SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

			HarnessCapacity.Hold hold = new HarnessCapacity.Hold(START.plus(Duration.ofMinutes(1)), 1);
			requireThat(result, "result").isEqualTo(new SessionResult.Refused(hold, false, List.of()));
			requireThat(capacity.hold(), "hold").isEqualTo(hold);
			requireThat(requests.size(), "requests.size()").isEqualTo(1);
		}
	}

	/**
	 * Verifies that a refused session reports when its refusal followed the end of a 30-minute hold.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void refusalAfterLongestHoldIsReported()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			ManualClock clock = new ManualClock(START);
			HarnessCapacity capacity = heldBy(root.resolve("locks"), clock);
			// Six refusals reach the 30-minute hold
			for (int refused = 0; refused < 6; ++refused)
				clock.set(capacity.refuse().hold().endsAt());
			Instant now = clock.instant();
			TestSessions sessions = recordingPort(root.resolve("runs"), capacity, clock,
				List.of(Turn.replies("refused", TestSessions.REFUSED_EVENT)), new CopyOnWriteArrayList<>());

			SessionResult result = await(sessions.start(request(source, List.of(), "one")));

			requireThat(result, "result").isEqualTo(new SessionResult.Refused(
				new HarnessCapacity.Hold(now.plus(Duration.ofMinutes(30)), 6), true, List.of()));
		}
	}

	/**
	 * Verifies that a rate-limit rejection without a reset time records the next hold length in the harness's shared
	 * hold and reports the session as rate-limited.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void rejectionWithoutResetTimeHoldsTheHarness()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			ManualClock clock = new ManualClock(START);
			HarnessCapacity capacity = heldBy(root.resolve("locks"), clock);
			TestSessions sessions = recordingPort(root.resolve("runs"), capacity, clock,
				List.of(Turn.replies("limit", TestSessions.REJECTED_EVENT)), new CopyOnWriteArrayList<>());

			SessionResult result = await(sessions.start(request(source, List.of(), "one")));

			HarnessCapacity.Hold hold = new HarnessCapacity.Hold(START.plus(Duration.ofMinutes(1)), 1);
			requireThat(result, "result").isEqualTo(new SessionResult.RateLimited(hold, List.of()));
			requireThat(capacity.hold(), "hold").isEqualTo(hold);
		}
	}

	/**
	 * Verifies that a turn that completes resets the harness's next hold length to 1 minute.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void completedTurnResetsHoldLength()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			ManualClock clock = new ManualClock(START.minus(Duration.ofMinutes(10)));
			HarnessCapacity capacity = heldBy(root.resolve("locks"), clock);
			HarnessCapacity.Hold ended = capacity.reject(null);
			clock.set(START);
			TestSessions sessions = recordingPort(root.resolve("runs"), capacity, clock, List.of(Turn.replies("done")),
				new CopyOnWriteArrayList<>());

			SessionResult result = await(sessions.start(request(source, List.of(), "one")));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			requireThat(capacity.hold(), "hold").isEqualTo(new HarnessCapacity.Hold(ended.endsAt(), 0));
		}
	}

	/**
	 * Verifies that a trial's result names the host tools that its agents ran, each with its version, and the outside
	 * sources that they used, and that another session's result names neither.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void trialNamesHostToolsAndSources()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-M8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			Map<String, String> environment = Map.of("PATH", Path.of(System.getProperty("java.home"), "bin").
				toString());
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root.resolve("runs"), List::of, "",
				false, environment, root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), TestSessions.HARNESS,
					List.of(Turn.replies("done", TestSessions.COMMAND_EVENT, TestSessions.MCP_EVENT)), input, output));

			SessionResult.Completed trial = (SessionResult.Completed) await(sessions.start(request(source, List.of(),
				"go")));
			SessionResult.Completed judge = (SessionResult.Completed) await(sessions.start(request(SessionRole.JUDGE,
				TrialProfile.HARNESS_DEFAULT, source, List.of(), "go")));

			String version = Runtime.version().version().stream().map(String::valueOf).
				collect(Collectors.joining("."));
			requireThat(trial.hostTools(), "hostTools").isEqualTo(List.of("java " + version));
			requireThat(trial.outsideSources(), "outsideSources").isEqualTo(List.of("github (MCP server)"));
			requireThat(judge.hostTools(), "judge.hostTools").isNull();
			requireThat(judge.outsideSources(), "judge.outsideSources").isNull();
		}
	}
}
