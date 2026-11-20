package com.holdmyspot.cat.common.optimizer.adapter.test;

import com.holdmyspot.cat.common.JvmScope;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

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
import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspace;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.WorkspaceFile;
import com.holdmyspot.cat.common.optimizer.port.FollowUp;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TurnObserver;
import com.holdmyspot.cat.common.optimizer.proposal.DraftDirectory;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.LaunchRefusedException;
import com.holdmyspot.cat.common.runner.LiveNestedSession;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.secrets.RunnerSessionsSecrets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
	/**
	 * The model and reasoning effort of a session whose profile the test does not examine.
	 */
	private static final TrialProfile SESSION_PROFILE = new TrialProfile("model-a", "low");
	private static final Duration LIMIT = Duration.ofMinutes(5);
	private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
	/**
	 * How often the driver of a session that reaches its time limit checks the test's clock. It is short so that the
	 * driver sees the advanced clock at once instead of after the production interval.
	 */
	private static final Duration TIME_LIMIT_POLL = Duration.ofMillis(1);

	/**
	 * Verifies that reading malformed present configuration reports an I/O failure.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsInvalidConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempFile("cat-invalid-configuration-", ".json"));
			Files.writeString(file, "{not json");

			RunnerSessions.readConfigurationFile(file, JsonMapper.builder().build());
		}
	}

	/**
	 * Verifies that only absent files and one JSON object are accepted as configuration.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void readsOptionalJsonObjects() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-configuration-objects-"));
			Path absent = root.resolve("absent.json");
			JsonMapper json = JsonMapper.builder().build();
			requireThat(RunnerSessions.readConfigurationFile(absent, json).isEmpty(), "absent").isTrue();

			Path object = root.resolve("object.json");
			Files.writeString(object, "{\"unknown\":true}");
			JsonNode parsed = RunnerSessions.readConfigurationFile(object, json).orElseThrow();
			requireThat(parsed.path("unknown").asBoolean(), "unknownField").isTrue();

			for (String invalid : List.of("", " \n", "null", "1", "true", "\"text\"", "[]"))
			{
				Files.writeString(object, invalid);
				IOException failure = null;
				try
				{
					RunnerSessions.readConfigurationFile(object, json);
				}
				catch (IOException e)
				{
					failure = e;
				}
				requireThat(failure, "failure").isNotNull();
				requireThat(failure.getMessage(), "message").contains(object.toAbsolutePath().normalize().toString());
				requireThat(failure.getMessage(), "message").contains("line 1, column 1");
				requireThat(failure.getCause(), "parserCause").isNull();
			}

			for (String invalid : List.of("{not json", "{} {}"))
			{
				Files.writeString(object, invalid);
				IOException failure = null;
				try
				{
					RunnerSessions.readConfigurationFile(object, json);
				}
				catch (IOException e)
				{
					failure = e;
				}
				requireThat(failure, "failure").isNotNull();
				requireThat(failure.getMessage(), "message").contains(object.toAbsolutePath().normalize().toString());
				requireThat(failure.getMessage(), "message").contains("line ");
				requireThat(failure.getMessage(), "message").contains(", column ");
				requireThat(failure.getCause(), "parserCause").isInstanceOf(JacksonException.class);
			}

			Path directory = Files.createDirectory(root.resolve("directory"));
			IOException nonregular = null;
			try
			{
				RunnerSessions.readConfigurationFile(directory, json);
			}
			catch (IOException e)
			{
				nonregular = e;
			}
			requireThat(nonregular, "nonregularFailure").isNotNull();
			requireThat(nonregular.getMessage(), "message").contains(directory.toAbsolutePath().normalize().toString());

			Path link = root.resolve("object-link.json");
			Files.writeString(object, "{}");
			Files.createSymbolicLink(link, object.getFileName());
			requireThat(RunnerSessions.readConfigurationFile(link, json).isPresent(), "validLink").isTrue();
			Path dangling = root.resolve("dangling.json");
			Files.createSymbolicLink(dangling, Path.of("missing.json"));
			IOException danglingFailure = null;
			try
			{
				RunnerSessions.readConfigurationFile(dangling, json);
			}
			catch (IOException e)
			{
				danglingFailure = e;
			}
			requireThat(danglingFailure, "danglingFailure").isNotNull();
			requireThat(danglingFailure.getCause(), "danglingCause").isInstanceOf(IOException.class);

			Files.writeString(object, "{}");
			requireThat(RunnerSessions.readConfigurationFile(object, json, 2).isPresent(), "boundedSuccess").isTrue();
			IOException oversized = null;
			try
			{
				RunnerSessions.readConfigurationFile(object, json, 1);
			}
			catch (IOException e)
			{
				oversized = e;
			}
			requireThat(oversized, "oversizedFailure").isNotNull();
			requireThat(oversized.getMessage(), "message").contains(object.toAbsolutePath().normalize().toString());
		}
	}

	/**
	 * Verifies that the null device laid over an absent configuration path reads as an absent file, while an
	 * empty regular file is still a parse error.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void readsNullDeviceAsAbsentConfiguration() throws IOException
	{
		JsonMapper json = JsonMapper.builder().build();
		requireThat(RunnerSessions.readConfigurationFile(Path.of("/dev/null"), json).isEmpty(), "nullDevice").
			isTrue();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path empty = Files.createTempFile(cleanup.add(Files.createTempDirectory("cat-null-device-")), "empty",
				".json");
			IOException failure = null;
			try
			{
				RunnerSessions.readConfigurationFile(empty, json);
			}
			catch (IOException e)
			{
				failure = e;
			}
			requireThat(failure, "emptyFileFailure").isNotNull();
		}
	}

	/**
	 * Verifies that unreadable configuration fails with its path and the underlying filesystem exception.
	 *
	 * @throws IOException if the fixture cannot be written or permissions cannot be restored
	 */
	@Test
	public void rejectsUnreadableConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempFile("cat-unreadable-configuration-", ".json"));
			Files.writeString(file, "{}");
			Set<PosixFilePermission> originalPermissions;
			try
			{
				originalPermissions = Files.getPosixFilePermissions(file);
			}
			catch (UnsupportedOperationException e)
			{
				throw new SkipException("The filesystem does not support POSIX permissions", e);
			}

			boolean permissionsRemoved = false;
			try
			{
				try
				{
					Files.setPosixFilePermissions(file, Set.of());
					permissionsRemoved = true;
				}
				catch (UnsupportedOperationException e)
				{
					throw new SkipException("The filesystem does not support changing POSIX permissions", e);
				}

				IOException failure = null;
				try
				{
					RunnerSessions.readConfigurationFile(file, JsonMapper.builder().build());
				}
				catch (IOException e)
				{
					failure = e;
				}
				if (failure == null)
					throw new SkipException("The process can read a file with all POSIX permissions removed");
				requireThat(failure.getMessage(), "message").contains(file.toAbsolutePath().normalize().toString());
				requireThat(failure.getCause(), "filesystemCause").isInstanceOf(IOException.class);
			}
			finally
			{
				if (permissionsRemoved)
					Files.setPosixFilePermissions(file, originalPermissions);
			}
		}
	}

	/**
	 * Creates the test.
	 */
	public RunnerSessionsTest()
	{
	}

	/**
	 * Creates a port whose runner plays a script.
	 *
	 * @param jvm the borrowed process scope
	 * @param resultsFolder the directory for run directories
	 * @param capacity the shared capacity
	 * @param clock the clock for time limits
	 * @param arguments receives the runner's options for each session
	 * @param script what the harness does, turn by turn
	 * @return the port
	 */
	private static TestSessions port(JvmScope jvm, Path resultsFolder, HarnessCapacity capacity, Clock clock,
		List<List<String>> arguments, List<Turn> script)
	{
		return port(jvm, resultsFolder, resultsFolder, capacity, clock, arguments, script);
	}

	/**
	 * Creates a port whose runner plays a script.
	 *
	 * @param jvm the borrowed process scope
	 * @param artifact the installed artifact that supplies the roles' rules
	 * @param resultsFolder the directory for run directories
	 * @param capacity the shared capacity
	 * @param clock the clock for time limits
	 * @param arguments receives the runner's options for each session
	 * @param script what the harness does, turn by turn
	 * @return the port
	 */
	private static TestSessions port(JvmScope jvm, Path artifact, Path resultsFolder, HarnessCapacity capacity,
		Clock clock, List<List<String>> arguments, List<Turn> script)
	{
		RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, artifact, resultsFolder, List::of, false,
			Map.of(), resultsFolder, capacity, clock, Yielding.fileReplacer());
		return new TestSessions(settings, (options, input, output) ->
		{
			arguments.add(options);
			ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--results")), TestSessions.HARNESS,
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
		return request(SESSION_PROFILE, source, readOnly, turns);
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
	 * Starts a session whose driver checks the clock every {@link #TIME_LIMIT_POLL}.
	 *
	 * @param sessions the port that starts the session
	 * @param request  what the session does
	 * @return the session
	 */
	private static SessionHandle startPollingOften(TestSessions sessions, SessionRequest request)
	{
		return RunnerSessionsSecrets.getRunnerSessionsAccess().start(sessions, request, TIME_LIMIT_POLL);
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
	 * Verifies that the port declares the results folder and the held-out results folder as the directories that hold
	 * its runs.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void declaresResultsFolders() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E11
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path resultsFolder = root.resolve("runs").normalize();
				TestSessions sessions = port(jvm, resultsFolder, HarnessCapacity.unlimited(), Clock.systemUTC(),
					new CopyOnWriteArrayList<>(), List.of());
				Path heldOutResultsFolder = root.resolve("runs-held_out").normalize();
				requireThat(sessions.resultsFolders(), "resultsFolders").isEqualTo(List.of(resultsFolder,
					heldOutResultsFolder));
			}
		}
	}

	/**
	 * Verifies that CAT passes a session's model and reasoning effort to the runner, including the effort that selects
	 * no reasoning, so that no session runs under the harness's default.
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E17
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				List<List<String>> arguments = new CopyOnWriteArrayList<>();
				TestSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
					arguments, List.of(Turn.replies("ok")));

				await(sessions.start(request(new TrialProfile("model-a", "high"), source, List.of(), "reasoning")));
				await(sessions.start(request(new TrialProfile("model-b", TrialProfile.NO_REASONING), source, List.of(),
					"noReasoning")));

				requireThat(arguments.size(), "arguments.size()").isEqualTo(2);
				List<String> reasoning = arguments.get(0);
				requireThat(ScriptedHarness.optionValue(reasoning, "--model"), "reasoningModel").isEqualTo("model-a");
				requireThat(ScriptedHarness.optionValue(reasoning, "--effort"), "reasoningEffort").isEqualTo("high");
				List<String> noReasoning = arguments.get(1);
				requireThat(ScriptedHarness.optionValue(noReasoning, "--model"), "noReasoningModel").isEqualTo("model-b");
				requireThat(ScriptedHarness.optionValue(noReasoning, "--effort"), "noReasoningEffort").
					isEqualTo(TrialProfile.NO_REASONING);
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-W10
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				List<List<String>> arguments = new CopyOnWriteArrayList<>();
				TestSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
					arguments, List.of(Turn.replies("ok")));

				for (boolean controlSource : List.of(true, false))
				{
					await(sessions.start(new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, source, List.of("go"),
						List.of(), LIMIT, SESSION_PROFILE, TestWorkspace.NONE, List.of(), Optional.empty(),
						controlSource)));
				}

				requireThat(arguments.get(0), "control").contains("--control-source");
				requireThat(arguments.get(1), "formal").doesNotContain("--control-source");
			}
		}
	}

	/**
	 * Verifies that a session whose request asks for extra permissions starts its runner with a file that lists them,
	 * that a session that asks for none passes no such option, and that the file is gone when the session ends.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void passesExtraPermissionsFile()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-I11
		try (TestJvmScope jvm = new TestJvmScope())
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				Path resultsFolder = root.resolve("runs");
				List<List<String>> arguments = new CopyOnWriteArrayList<>();
				List<ExtraPermissions> files = new CopyOnWriteArrayList<>();
				RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, resultsFolder, resultsFolder, List::of,
					false, Map.of(), resultsFolder, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
				TestSessions sessions = new TestSessions(settings, (options, input, output) ->
				{
					arguments.add(options);
					if (options.contains(ExtraPermissions.OPTION))
					{
						files.add(ExtraPermissions.read(JsonMapper.builder().build(),
							Path.of(ScriptedHarness.optionValue(options, ExtraPermissions.OPTION))));
					}
					ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--results")),
						TestSessions.HARNESS, List.of(Turn.replies("ok")), input, output);
				});
				ExtraPermissions extra = new ExtraPermissions(List.of(root.resolve("cache").toAbsolutePath()),
					Set.of("registry.example.com"));

				await(sessions.start(request(SESSION_PROFILE, source, List.of(), "plain")));
				await(sessions.start(request(SESSION_PROFILE, source, List.of(), "extra").withExtraPermissions(extra)));

				requireThat(arguments.get(0), "plain").doesNotContain(ExtraPermissions.OPTION);
				requireThat(arguments.get(1), "extra").contains(ExtraPermissions.OPTION);
				requireThat(files, "files").isEqualTo(List.of(extra));
				Path file = Path.of(ScriptedHarness.optionValue(arguments.get(1), ExtraPermissions.OPTION));
				requireThat(Files.exists(file), "fileKept").isFalse();
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-C21
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				TestValue.Generated lines = new TestValue.Generated("log_lines", 1, 11, Map.of("count", 3L));
				TestWorkspace files = new TestWorkspace(Map.of("logs/build.log",
					new WorkspaceFile("logs/build.log", "{{before}}\nBuild finished on {{expected_outcome}}.\n")),
					Map.of("before", lines), "2025-03-04");
				List<String> received = new CopyOnWriteArrayList<>();
				RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of,
					false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
				TestSessions sessions = new TestSessions(settings, (options, input, output) ->
				{
					Path workspace = Path.of(ScriptedHarness.optionValue(options, "--workspace"));
					received.add(Files.readString(workspace.resolve("logs/build.log")));
					ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--results")), TestSessions.HARNESS,
						List.of(Turn.replies("ok")), input, output);
				});

				await(sessions.start(new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, source, List.of("go"),
					List.of(), LIMIT, SESSION_PROFILE, files, List.of())));

				StringWriter generated = new StringWriter();
				lines.write(generated);
				requireThat(received, "received").isEqualTo(List.of(generated + "\nBuild finished on 2025-03-04.\n"));
			}
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			Path artifact = manifest(root.resolve("artifact"), "cat", PluginInfo.CAT_REPOSITORY);
			// CAT is recognized by its manifest's repository, whatever the user named it, and a plugin that takes
			// CAT's name without its repository is not CAT
			Path catCopy = manifest(root.resolve("plugins/workflow"), "workflow", PluginInfo.CAT_REPOSITORY);
			Path otherCopy = manifest(root.resolve("plugins/cat"), "cat", "https://example.com/cat");
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, artifact, root.resolve("runs"), List::of,
				true, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				arguments.add(options);
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--results")), TestSessions.HARNESS,
					List.of(Turn.replies("ok")), input, output);
			});

			for (List<Path> plugins : List.of(List.of(otherCopy, catCopy), List.of(otherCopy), List.<Path>of()))
			{
				await(sessions.start(new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, source, List.of("go"),
					List.of(), LIMIT, SESSION_PROFILE, TestWorkspace.NONE, plugins)));
			}
			await(sessions.start(request(SessionRole.JUDGE, SESSION_PROFILE, source, List.of(), "go")));

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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-")).
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
			TestSessions sessions = new TestSessions(new RunnerSessions.Settings(jvm, artifact, root.resolve("runs"),
				List::of, false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(),
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
	 * Returns held-out locations below or above the directory that a listed plugin rule would grant.
	 *
	 * @return held-out paths relative to the fixture root
	 */
	@DataProvider
	public static Object[][] overlappingHeldOutLocations()
	{
		return new Object[][]
		{
			{Path.of("plugins/base/rules/common/held-out")},
			{Path.of("plugins")}
		};
	}

	/**
	 * Verifies that source discovery refuses a plugin rule whose containing directory would also grant access to
	 * held-out evidence, or is itself inside a held-out directory.
	 *
	 * @param location the held-out path relative to the fixture root
	 * @throws IOException if the listed directory overlaps held-out evidence or a fixture cannot be read or written
	 */
	@Test(dataProvider = "overlappingHeldOutLocations", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*overlaps held-out.*")
	public void refusesSourceGrantOverHeldOut(Path location) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-source-denial-")).
				toRealPath();
			Path base = manifest(root.resolve("plugins/base"), "cat", PluginInfo.CAT_REPOSITORY);
			Path project = Files.createDirectories(root.resolve("project"));
			Path heldOut = Files.createDirectories(root.resolve(location));
			write(base.resolve("rules/common/default.md"), "Plugin guidance");
			write(heldOut.resolve("evidence.txt"), "Held-out evidence");
			TrialPlugin plugin = new TrialPlugin("cat@market", "0".repeat(64), new TrialPlugin.Installed(),
				List.of(), null);
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"),
				() -> List.of(heldOut), false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(),
				Yielding.fileReplacer());
			TestSessions sessions = new TestSessions(settings, (_, _, _) ->
			{
			});

			sessions.loadedSources(project, List.of(new PluginBase(plugin, base)));
		}
	}

	/**
	 * Verifies loading a trial plugin's overlaid rules leaves the process temporary directory empty.
	 *
	 * @throws IOException if fixture files or loaded sources cannot be read or written
	 */
	@Test(timeOut = 120_000)
	public void loadingOverlayLeavesNoTemporaryFiles() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "overlay-location-"));
			Path temporary = root.resolve("process-temporary");
			Files.createDirectories(temporary);
			try (TestJvmScope process = new TestJvmScope(temporary))
			{
				Path artifact = manifest(root.resolve("artifact"), "cat", PluginInfo.CAT_REPOSITORY);
				Path base = manifest(root.resolve("base"), "cat", PluginInfo.CAT_REPOSITORY);
				Path project = Files.createDirectories(root.resolve("project"));
				write(base.resolve("rules/common/default.md"), "Guidance");
				TrialPlugin plugin = new TrialPlugin("cat", "0".repeat(64), new TrialPlugin.Build(List.of("build"),
					Path.of("out")), List.of(), null);
				RunnerSessions.Settings settings = new RunnerSessions.Settings(process, artifact, root.resolve("runs"),
					List::of, false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(),
					Yielding.fileReplacer());
				TestSessions sessions = new TestSessions(settings, (_, _, _) ->
				{
				});

				List<DerivedSource> trialSources = sessions.loadedSources(project,
					List.of(new PluginBase(plugin, base))).trials();
				requireThat(trialSources, "trialSources").contains(new DerivedSource(
					base.resolve("rules/common/default.md").toString(),
					"CAT injects this rule into every session"));

				requireThat(temporary, "temporary").isDirectory();
				try (Stream<Path> children = Files.list(temporary))
				{
					requireThat(children.toList(), "children").isEmpty();
				}
			}
		}
	}

	/**
	 * Verifies rule overlay loading rejects an unusable process temporary root instead of using another directory.
	 *
	 * @throws IOException if fixture setup or overlay creation fails
	 */
	@Test(expectedExceptions = FileAlreadyExistsException.class)
	public void rejectsUnusableOverlayTemporaryRoot() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "overlay-root-"));
			Path temporary = Files.writeString(root.resolve("process-temporary"), "a file cannot hold temporary children");
			try (TestJvmScope process = new TestJvmScope(temporary))
			{
				Path artifact = manifest(root.resolve("artifact"), "cat", PluginInfo.CAT_REPOSITORY);
				Path base = manifest(root.resolve("base"), "cat", PluginInfo.CAT_REPOSITORY);
				Path project = Files.createDirectories(root.resolve("project"));
				write(base.resolve("rules/common/default.md"), "Guidance");
				TrialPlugin plugin = new TrialPlugin("cat", "0".repeat(64), new TrialPlugin.Build(List.of("build"),
					Path.of("out")), List.of(), null);
				RunnerSessions.Settings settings = new RunnerSessions.Settings(process, artifact, root.resolve("runs"),
					List::of, false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(),
					Yielding.fileReplacer());
				TestSessions sessions = new TestSessions(settings, (_, _, _) ->
				{
				});

				sessions.loadedSources(project, List.of(new PluginBase(plugin, base)));
			}
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-")).
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
				TestSessions sessions = new TestSessions(new RunnerSessions.Settings(jvm, artifact, root.resolve("runs"),
					List::of, installCat, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(),
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of,
				false,
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(),
					root.resolve("runs"), Clock.systemUTC(),
					Yielding.fileReplacer());
				NestedSession nestedSession = manager.create(TestSessions.HARNESS);
				manager.initialize(nestedSession, root, root, "scripted", List.of("scripted"));
				output.write("{\"type\":\"started\"}\n{\"type\":\"session_id\",\"session_id\":\"" +
					nestedSession.id() + "\"}\n");
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of,
				false,
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			List<String> sent = new CopyOnWriteArrayList<>();
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(),
					root.resolve("runs"), Clock.systemUTC(),
					Yielding.fileReplacer());
				NestedSession nestedSession = manager.create(TestSessions.HARNESS);
				manager.initialize(nestedSession, root, root, "scripted", List.of("scripted"));
				output.write("{\"type\":\"started\"}\n{\"type\":\"session_id\",\"session_id\":\"" +
					nestedSession.id() + "\"}\n");
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
	 * Verifies that CAT sends the interrupt request as a record that holds only its type, which is all that a request
	 * served through a run's runner may send, and classifies the supplied terminal completion as Interrupted. This
	 * observes CAT's outgoing request, not native harness timing.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void sendsInterruptRecordHoldingOnlyItsType()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E15
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				Path runs = root.resolve("runs");
				List<String> requests = new CopyOnWriteArrayList<>();
				CountDownLatch turnReceived = new CountDownLatch(1);
				RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, runs, List::of, false, Map.of(),
					root,
					HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
				TestSessions sessions = new TestSessions(settings, (options, input, output) ->
				{
					NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), runs,
						Clock.systemUTC(), Yielding.fileReplacer());
					NestedSession nestedSession = manager.create(TestSessions.HARNESS);
					manager.initialize(nestedSession, root, root, "scripted", List.of("scripted"));
					output.write("{\"type\":\"started\"}\n{\"type\":\"session_id\",\"session_id\":\"" +
						nestedSession.id() + "\"}\n");
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
				requireThat(requests, "requests").contains("{\"type\":\"interrupt\"}");
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E8
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				HarnessCapacity capacity = HarnessCapacity.of(jvm.fileLocks(),
					root.resolve("locks"), 1, () ->
				{
				}, Clock.systemUTC());
				List<List<String>> arguments = new CopyOnWriteArrayList<>();
				TestSessions sessions = port(jvm, root.resolve("runs"), capacity, Clock.systemUTC(), arguments,
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E8
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				HarnessCapacity capacity = HarnessCapacity.of(jvm.fileLocks(),
					root.resolve("locks"), 1, () ->
				{
				}, Clock.systemUTC());
				CountDownLatch entered = new CountDownLatch(1);
				TestSessions sessions = port(jvm, root.resolve("runs"), capacity, Clock.systemUTC(),
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				TestSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
					new CopyOnWriteArrayList<>(), List.of(Turn.replies("first"), Turn.fails(), Turn.replies("third")));

				SessionResult result = await(sessions.start(request(source, List.of(), "one", "two", "three")));

				requireThat(result, "result").isInstanceOf(SessionResult.Failed.class);
				requireThat(((SessionResult.Failed) result).reason(), "reason").contains("Turn 2");
			}
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			TestSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), Clock.systemUTC(),
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of,
					false,
					Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
				TestSessions sessions = new TestSessions(settings, (options, input, output) ->
				{
					throw new IOException("artifact is invalid");
				});

				SessionResult result = await(sessions.start(request(source, List.of(), "one")));

				requireThat(result, "result").isEqualTo(new SessionResult.Failed("artifact is invalid", List.of()));
			}
		}
	}

	/**
	 * Verifies that a session that is refused before it starts ends as a launch refusal that carries the refusal's
	 * message, not as a failure that the optimizer retries.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void classifiesRefusalAsLaunchRefused()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of,
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			String refusal = "No request server serves this session. Start the request server.";
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				throw new LaunchRefusedException(refusal);
			});

			SessionResult result = await(sessions.start(request(source, List.of(), "one")));

			requireThat(result, "result").isEqualTo(new SessionResult.LaunchRefused(refusal, List.of()));
		}
	}

	/**
	 * Verifies an observer failure stops the bridge after the completed first turn and preserves the observer's error.
	 *
	 * @throws IOException if a fixture cannot be created
	 * @throws InterruptedException if the test or bridge shutdown is interrupted
	 * @throws TimeoutException if the bridge does not return
	 */
	@Test(timeOut = 120_000)
	public void closesRunnerOnObserverFailure() throws IOException, InterruptedException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-observer-failure-"));
			Path source = Files.createDirectories(root.resolve("source"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of,
				false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			List<String> received = new CopyOnWriteArrayList<>();
			AtomicReference<Thread> bridgeThread = new AtomicReference<>();
			CountDownLatch bridgeReturned = new CountDownLatch(1);
			List<Turn> script = List.of(Turn.replies("one"), Turn.replies("two"));
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				bridgeThread.set(Thread.currentThread());
				try
				{
					ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--results")),
						TestSessions.HARNESS, script, input, output, received);
				}
				finally
				{
					bridgeReturned.countDown();
				}
			});
			IOException observerFailure = new IOException("retained evidence could not be read");
			TurnObserver observer = (_, _) ->
			{
				throw observerFailure;
			};
			SessionHandle handle = sessions.start(request(source, List.of(), "one", "two").withTurnObserver(observer));
			try
			{
				try
				{
					await(handle);
					throw new AssertionError("The observer failure was not reported");
				}
				catch (ExecutionException e)
				{
					requireThat(e.getCause() == observerFailure, "sameCause").isTrue();
				}
				requireThat(bridgeReturned.await(5, TimeUnit.SECONDS), "bridgeReturned").isTrue();
				requireThat(received, "receivedTurns").isEqualTo(List.of("one"));
			}
			finally
			{
				Thread runner = bridgeThread.get();
				if (runner != null && runner.isAlive())
				{
					runner.interrupt();
					runner.join(5_000);
				}
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				ManualClock clock = new ManualClock(START);
				CountDownLatch entered = new CountDownLatch(1);
				List<Turn> script = List.of(Turn.replies("first"),
					Turn.blocksUntilInterrupted(TestSessions.OUTPUT_EVENT).announcing(entered));
				TestSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), clock,
					new CopyOnWriteArrayList<>(), script);
				SessionHandle handle = startPollingOften(sessions, request(source, List.of(), "one", "two", "three"));
				requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();

				clock.advance(LIMIT.plusSeconds(1));

				SessionResult result = await(handle);
				requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
				requireThat(((SessionResult.Completed) result).responses(), "responses").isEqualTo(List.of(List.of("first")));
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				ManualClock clock = new ManualClock(START);
				CountDownLatch entered = new CountDownLatch(1);
				List<Turn> script = List.of(Turn.replies("first"), Turn.blocksUntilInterrupted().announcing(entered));
				Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
				TestSessions sessions = port(jvm, artifact, root.resolve("runs"), HarnessCapacity.unlimited(), clock,
					new CopyOnWriteArrayList<>(), script);
				SessionHandle handle = startPollingOften(sessions, request(role, SESSION_PROFILE, source, List.of(),
					"one", "two", "three"));
				requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();

				clock.advance(LIMIT.plusSeconds(1));

				SessionResult result = await(handle);
				requireThat(result, "result").isEqualTo(new SessionResult.Failed("The " + word +
					" session reached its 5-minute time limit", List.of()));
			}
		}
	}

	/**
	 * Verifies a contract author's partial files stay in its retained runner project after CAT times out and prunes the
	 * terminal runtime.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session cannot determine a result
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(timeOut = 120_000)
	public void authorTimeoutKeepsPartialDraft()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-author-timeout-"));
			Path source = Files.createDirectories(root.resolve("source"));
			Files.writeString(source.resolve("README.md"), "original source\n");
			Path draft = Path.of(DraftDirectory.name(source), ".cat/tests/target/test");
			String scenarioPath = draft.resolve("scenario.md").toString().replace('\\', '/');
			TestWorkspace workspaceFiles = new TestWorkspace(Map.of(scenarioPath,
				new WorkspaceFile(scenarioPath, "seeded scenario\n")), Map.of(), "timeout retention");
			Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
			ManualClock clock = new ManualClock(START);
			CountDownLatch partialDraftWritten = new CountDownLatch(1);
			AtomicReference<NestedSession> ownedSession = new AtomicReference<>();
			AtomicReference<Path> authorProject = new AtomicReference<>();
			AtomicReference<Path> workspaceInput = new AtomicReference<>();
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, artifact, root.resolve("runs"), List::of,
				false, Map.of(), root, HarnessCapacity.unlimited(), clock, Yielding.fileReplacer());
			TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			{
				Path resultsFolder = Path.of(ScriptedHarness.optionValue(options, "--results"));
				Path project = Path.of(ScriptedHarness.optionValue(options, "--project"));
				Path workspace = Path.of(ScriptedHarness.optionValue(options, "--workspace"));
				NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), resultsFolder, clock,
					Yielding.fileReplacer());
				NestedSession nestedSession = manager.create(TestSessions.HARNESS);
				manager.initialize(nestedSession, artifact, project, "scripted", options);
				manager.copyProject(project, nestedSession);
				manager.copyWorkspace(workspace, nestedSession);
				ownedSession.set(nestedSession);
				authorProject.set(nestedSession.directory().resolve("project"));
				workspaceInput.set(workspace);

				ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted().withCheckpoint(_ ->
				{
					Path testDirectory = authorProject.get().resolve(draft);
					Files.createDirectories(testDirectory);
					Files.writeString(testDirectory.resolve("scenario.md"), "partial author scenario\n");
					Files.writeString(testDirectory.resolve("proposal.md"), "partial author proposal\n");
					partialDraftWritten.countDown();
				})), new ArrayList<>());
				HarnessRunnerBridge.serve(() -> new LiveNestedSession(nestedSession, process, new TestHarnessRunnerScope()),
					manager, input, output);
			});
			SessionRequest request = new SessionRequest(SessionRole.CONTRACT_AUTHOR, Part.DEVELOPMENT, source,
				List.of("write a draft"), List.of(), LIMIT, SESSION_PROFILE, workspaceFiles);
			SessionHandle handle = startPollingOften(sessions, request);
			requireThat(partialDraftWritten.await(60, TimeUnit.SECONDS), "partialDraftWritten").isTrue();

			clock.advance(LIMIT.plusSeconds(1));

			SessionResult result = await(handle);
			requireThat(result, "result").isEqualTo(new SessionResult.Failed(
				"The contract author session reached its 5-minute time limit", List.of()));
			NestedSession nestedSession = ownedSession.get();
			requireThat(nestedSession, "ownedSession").isNotNull();
			requireThat(Files.isDirectory(nestedSession.directory()), "retainedSession").isTrue();
			Path project = authorProject.get();
			requireThat(Files.readString(project.resolve(draft).resolve("scenario.md")), "seededScenario").isEqualTo(
				"partial author scenario\n");
			requireThat(Files.readString(project.resolve(draft).resolve("proposal.md")), "partialProposal").isEqualTo(
				"partial author proposal\n");
			requireThat(Files.readString(source.resolve("README.md")), "source").isEqualTo("original source\n");
			requireThat(Files.exists(source.resolve(draft).resolve("proposal.md")), "sourceProposal").isFalse();
			requireThat(Files.exists(workspaceInput.get()), "temporaryWorkspace").isFalse();
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
			Path source = Files.createDirectories(root.resolve("source"));
			ManualClock clock = new ManualClock(START);
			CountDownLatch entered = new CountDownLatch(1);
			List<Turn> script = new ArrayList<>(earlier);
			script.add(Turn.blocksUntilInterrupted(lastEvents).announcing(entered));
			TestSessions sessions = port(jvm, root.resolve("runs"), HarnessCapacity.unlimited(), clock,
				new CopyOnWriteArrayList<>(), script);
			String[] turns = new String[script.size()];
			Arrays.fill(turns, "turn");
			SessionHandle handle = startPollingOften(sessions, request(source, List.of(), turns));
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
	 * @param jvm the borrowed process scope
	 * @param resultsFolder the directory for run directories
	 * @param capacity the shared capacity
	 * @param clock the clock for time limits
	 * @param script what the harness does, turn by turn
	 * @param requests receives each turn request that reaches the harness
	 * @return the port
	 */
	private static TestSessions recordingPort(JvmScope jvm, Path resultsFolder, HarnessCapacity capacity, Clock clock,
		List<Turn> script,
		List<String> requests)
	{
		RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, resultsFolder, resultsFolder, List::of, false,
			Map.of(), resultsFolder, capacity, clock, Yielding.fileReplacer());
		return new TestSessions(settings, (options, input, output) ->
			ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--results")), TestSessions.HARNESS,
				script, input, output, requests));
	}

	/**
	 * Creates a capacity pool over a test-owned directory whose holds follow a test-controlled clock, and that fails
	 * the test if a session waits for it.
	 *
	 * @param jvm the process scope that owns file-lock coordination
	 * @param directory the directory that holds the slot and hold files
	 * @param clock the clock that decides whether a hold lasts
	 * @return the pool
	 */
	private static HarnessCapacity heldBy(JvmScope jvm, Path directory, ManualClock clock)
	{
		return HarnessCapacity.of(jvm.fileLocks(), directory, 1, () ->
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E6
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				ManualClock clock = new ManualClock(START);
				HarnessCapacity capacity = heldBy(jvm, root.resolve("locks"), clock);
				List<String> requests = new CopyOnWriteArrayList<>();
				TestSessions sessions = recordingPort(jvm, root.resolve("runs"), capacity, clock,
					List.of(Turn.replies("refused", TestSessions.REFUSED_EVENT), Turn.replies("never")), requests);

				SessionResult result = await(sessions.start(request(source, List.of(), "one", "two")));

				HarnessCapacity.Hold hold = new HarnessCapacity.Hold(START.plus(Duration.ofMinutes(1)), 1);
				requireThat(result, "result").isEqualTo(new SessionResult.Refused(hold, false, List.of()));
				requireThat(capacity.hold(), "hold").isEqualTo(hold);
				requireThat(requests.size(), "requests.size()").isEqualTo(1);
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E6
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				ManualClock clock = new ManualClock(START);
				HarnessCapacity capacity = heldBy(jvm, root.resolve("locks"), clock);
				// Six refusals reach the 30-minute hold
				for (int refused = 0; refused < 6; ++refused)
					clock.set(capacity.refuse().hold().endsAt());
				Instant now = clock.instant();
				TestSessions sessions = recordingPort(jvm, root.resolve("runs"), capacity, clock,
					List.of(Turn.replies("refused", TestSessions.REFUSED_EVENT)), new CopyOnWriteArrayList<>());

				SessionResult result = await(sessions.start(request(source, List.of(), "one")));

				requireThat(result, "result").isEqualTo(new SessionResult.Refused(
					new HarnessCapacity.Hold(now.plus(Duration.ofMinutes(30)), 6), true, List.of()));
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E6
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				ManualClock clock = new ManualClock(START);
				HarnessCapacity capacity = heldBy(jvm, root.resolve("locks"), clock);
				TestSessions sessions = recordingPort(jvm, root.resolve("runs"), capacity, clock,
					List.of(Turn.replies("limit", TestSessions.REJECTED_EVENT)), new CopyOnWriteArrayList<>());

				SessionResult result = await(sessions.start(request(source, List.of(), "one")));

				HarnessCapacity.Hold hold = new HarnessCapacity.Hold(START.plus(Duration.ofMinutes(1)), 1);
				requireThat(result, "result").isEqualTo(new SessionResult.RateLimited(hold, List.of()));
				requireThat(capacity.hold(), "hold").isEqualTo(hold);
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E6
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-"));
				Path source = Files.createDirectories(root.resolve("source"));
				ManualClock clock = new ManualClock(START.minus(Duration.ofMinutes(10)));
				HarnessCapacity capacity = heldBy(jvm, root.resolve("locks"), clock);
				HarnessCapacity.Hold ended = capacity.reject(null);
				clock.set(START);
				TestSessions sessions = recordingPort(jvm, root.resolve("runs"), capacity, clock, List.of(Turn.replies("done")),
					new CopyOnWriteArrayList<>());

				SessionResult result = await(sessions.start(request(source, List.of(), "one")));

				requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
				requireThat(capacity.hold(), "hold").isEqualTo(new HarnessCapacity.Hold(ended.endsAt(), 0));
			}
		}
	}
}
