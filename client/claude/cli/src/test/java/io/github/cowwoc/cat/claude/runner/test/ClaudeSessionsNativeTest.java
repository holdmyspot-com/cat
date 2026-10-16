package io.github.cowwoc.cat.claude.runner.test;

import static io.github.cowwoc.cat.common.optimizer.adapter.fakes.test.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import io.github.cowwoc.cat.claude.optimizer.ClaudeSessions;
import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.contract.test.SessionPortContract.Subject;
import io.github.cowwoc.cat.common.optimizer.port.contract.test.SessionPortContract;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeSessions} against a real Claude process, using model {@code haiku} to limit cost.
 * <p>
 * The tests run only in the {@code native} group, with {@code -Dcat.native.harness=claude}. They live in the runner
 * test package because they read the host's credential location, which optimizer sources must not do.
 */
public final class ClaudeSessionsNativeTest
{
	private static final Duration LIMIT = Duration.ofMinutes(10);
	/**
	 * The cheapest profile, which leaves the reasoning effort at the harness's default.
	 */
	private static final TrialProfile PROFILE = new TrialProfile("haiku", "");
	private static final long WAIT_MINUTES = 12;

	/**
	 * Creates the test fixture.
	 */
	public ClaudeSessionsNativeTest()
	{
	}

	/**
	 * Skips the test unless the build selected this harness.
	 *
	 * @throws SkipException if the build did not select Claude
	 */
	private static void requireHarness()
	{
		if (!"claude".equals(System.getProperty("cat.native.harness")))
			throw new SkipException("Run with -Pnative -Dcat.native.harness=claude");
	}

	/**
	 * Returns the process environment, as a child process would inherit it.
	 *
	 * @return the environment
	 */
	private static Map<String, String> environment()
	{
		return Map.copyOf(new ProcessBuilder().environment());
	}

	/**
	 * Returns the host's Claude configuration directory, as the claude executable locates it.
	 *
	 * @return the directory
	 * @throws IOException if the environment does not name the user's home directory
	 */
	private static Path configurationDirectory() throws IOException
	{
		Map<String, String> environment = environment();
		String configured = environment.get("CLAUDE_CONFIG_DIR");
		if (configured != null && !configured.isBlank())
			return Path.of(configured);
		return Platform.current().homeDirectory(environment).resolve(".claude");
	}

	/**
	 * Opens the real adapter with fixtures under the CAT work temp root.
	 *
	 * @return the subject
	 * @throws IOException if the fixtures cannot be created
	 */
	private static HarnessSubject open() throws IOException
	{
		requireHarness();
		String workTemp = System.getProperty("cat.work.temp");
		if (workTemp == null || workTemp.isBlank())
			throw new IllegalStateException("The build must pass the CAT work temp root as cat.work.temp");
		Path workTempRoot = Path.of(workTemp).toAbsolutePath().normalize();
		Files.createDirectories(workTempRoot);
		TemporaryDirectories cleanup = new TemporaryDirectories();
		Path fixture = cleanup.add(Files.createTempDirectory(workTempRoot, "native-sessions-"));
		Path artifact = createArtifact(fixture.resolve("artifact"));
		Path source = Files.createDirectories(fixture.resolve("source"));
		Files.writeString(source.resolve("README.md"), "fixture\n", UTF_8);
		Path runRoot = fixture.resolve("runs");
		ClaudeSessions sessions = new ClaudeSessions(new RunnerSessions.Settings(artifact, runRoot, List::of, "", false,
			environment(), configurationDirectory(), HarnessCapacity.unlimited(), Clock.systemUTC()));
		return new HarnessSubject(cleanup, sessions, source, runRoot);
	}

	/**
	 * Creates the smallest directory that the runner accepts as a Claude artifact.
	 *
	 * @param artifact the directory to create
	 * @return {@code artifact}
	 * @throws IOException if the filesystem operation fails
	 */
	private static Path createArtifact(Path artifact) throws IOException
	{
		Files.createDirectories(artifact.resolve(".claude-plugin"));
		Files.createDirectories(artifact.resolve("client/bin"));
		Files.writeString(artifact.resolve(".claude-plugin/plugin.json"), "{}\n", UTF_8);
		Files.writeString(artifact.resolve("client/VERSION"), PluginInfo.CAT_VERSION + "\n", UTF_8);
		for (String launcher : List.of("plugin-info", "session-start"))
		{
			Path file = artifact.resolve("client/bin").resolve(launcher);
			Files.writeString(file, "#!/bin/sh\nexit 0\n", UTF_8);
			Files.setPosixFilePermissions(file, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
		}
		return artifact;
	}

	/**
	 * Creates the contract over the real adapter.
	 *
	 * @return the contract
	 */
	private static SessionPortContract contract()
	{
		return new SessionPortContract(ClaudeSessionsNativeTest::open);
	}

	/**
	 * Waits for a session to end.
	 *
	 * @param session the session
	 * @return its result
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException   if the session's completion failed
	 * @throws TimeoutException     if the session did not end in time
	 */
	private static SessionResult await(SessionHandle session)
		throws InterruptedException, ExecutionException, TimeoutException
	{
		return session.completion().toCompletableFuture().get(WAIT_MINUTES, TimeUnit.MINUTES);
	}

	/**
	 * Verifies that a real session completes with evidence of every turn.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(groups = "native", timeOut = 900_000)
	public void completesWithEvidenceOfEveryTurn() throws Exception
	{
		// Requirement: R-E13
		// Requirement: R-E16
		// Requirement: R-E6
		contract().completesWithEvidenceOfEveryTurn();
	}

	/**
	 * Verifies that a real judge leaves read-only evidence unchanged.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(groups = "native", timeOut = 900_000)
	public void judgeLeavesReadOnlyEvidenceUnchanged() throws Exception
	{
		// Requirement: R-E13
		contract().judgeLeavesReadOnlyEvidenceUnchanged();
	}

	/**
	 * Verifies that an interrupt during a real turn ends the session as interrupted.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(groups = "native", timeOut = 900_000)
	public void interruptEndsARunningTurnAsInterrupted() throws Exception
	{
		// Requirement: R-E15
		contract().interruptEndsARunningTurnAsInterrupted();
	}

	/**
	 * Verifies that an interrupt after a real session ended changes nothing.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(groups = "native", timeOut = 900_000)
	public void interruptAfterTheEndChangesNothing() throws Exception
	{
		// Requirement: R-E15
		contract().interruptAfterTheEndChangesNothing();
	}

	/**
	 * Verifies that the model comes from harness events rather than from the alias that CAT passed, and that the usage
	 * and rate-limit windows are read from the same events.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(groups = "native", timeOut = 900_000)
	public void reportsTheObservedModelUsageAndWindows() throws Exception
	{
		// Requirement: R-E16
		// Requirement: R-E6
		try (HarnessSubject subject = open())
		{
			SessionHandle session = subject.port().start(new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT,
				subject.workspace(), List.of(subject.quickPrompt()), List.of(), LIMIT, PROFILE));

			SessionResult result = await(session);

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.model(), "model").startsWith("claude-").contains("haiku");
			long inputTokens = completed.usage().input() + completed.usage().cachedInput();
			requireThat(inputTokens, "usage.input").isGreaterThan(0L);
			for (RateLimitWindow window : completed.windows())
			{
				requireThat(window.utilization(), "utilization").isBetween(0.0, true, 1.0, true);
			}
		}
	}

	/**
	 * Verifies that the port classifies the login of the host that runs the test, before any session starts, from key
	 * names and types only.
	 *
	 * @throws Exception if the credentials cannot be inspected
	 */
	@Test(groups = "native", timeOut = 900_000)
	public void classifiesTheHostLogin() throws Exception
	{
		// Requirement: R-E7
		try (HarnessSubject subject = open())
		{
			HostLogin login = subject.port().hostLogin();

			requireThat(login, "login").isNotEqualTo(HostLogin.UNKNOWN);
		}
	}

	/**
	 * Verifies the port's host-login contract against the real adapter.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(groups = "native", timeOut = 900_000)
	public void reportsTheHostLoginWithoutASession() throws Exception
	{
		// Requirement: R-E7
		contract().reportsTheHostLoginWithoutASession();
	}

	/**
	 * Verifies that each role runs as a session of its own, through the runner.
	 *
	 * @throws Exception if a session fails
	 */
	@Test(groups = "native", timeOut = 1_800_000)
	public void runsOneSessionPerRole() throws Exception
	{
		// Requirement: R-E13
		try (HarnessSubject subject = open())
		{
			for (SessionRole role : SessionRole.values())
			{
				SessionHandle session = subject.port().start(new SessionRequest(role, partOf(role), subject.workspace(),
					List.of(subject.quickPrompt()), List.of(), LIMIT, PROFILE));

				SessionResult result = await(session);

				requireThat(result, role + ".result").isInstanceOf(SessionResult.Completed.class);
				int responses = ((SessionResult.Completed) result).responses().size();
				requireThat(responses, role + ".responses.size()").isEqualTo(1);
			}
		}
	}

	/**
	 * Verifies that a session whose root turn starts a background child waits for the child, so that the child's
	 * work and the follow-up turn belong to the same session.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(groups = "native", timeOut = 1_800_000)
	public void waitsForABackgroundChild() throws Exception
	{
		// Requirement: R-E14
		try (HarnessSubject subject = open())
		{
			String prompt = "Use the Agent tool with run_in_background set to true to start exactly one subagent. " +
				"The subagent's only job is to create the file child.txt in the current directory, containing the " +
				"text child-done, and then reply done. Do not create the file yourself. After you start the " +
				"subagent, reply with the single word started.";
			SessionHandle session = subject.port().start(new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT,
				subject.workspace(), List.of(prompt), List.of(), LIMIT, PROFILE));

			SessionResult result = await(session);

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			String transcript = Files.readString(completed.transcript(), UTF_8);
			requireThat(transcript, "transcript").contains("\"task_started\"").contains("\"task_notification\"");
			Path child = completed.effects().resolve("child.txt");
			requireThat(Files.isRegularFile(child), "child.txt.exists").isTrue();
			requireThat(Files.readString(child, UTF_8), "child.txt").contains("child-done");
		}
	}

	/**
	 * Verifies that an interrupt that arrives while the session waits for a background child ends the session as
	 * interrupted, instead of leaving it waiting for the child.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(groups = "native", timeOut = 1_800_000)
	public void interruptWhileWaitingForAChild() throws Exception
	{
		// Requirement: R-E15
		// Requirement: R-E14
		try (HarnessSubject subject = open())
		{
			String prompt = "Use the Agent tool with run_in_background set to true to start exactly one subagent. " +
				"The subagent's only job is to run `sleep 240` with the Bash tool and then reply done. After you " +
				"start the subagent, reply with the single word started.";
			SessionHandle session = subject.port().start(new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT,
				subject.workspace(), List.of(prompt), List.of(), LIMIT, PROFILE));
			long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
			// The root reply ends first, so the session is by then only waiting for the child.
			while (!subject.transcriptContains("\"task_started\"") || !subject.transcriptContains("\"type\":\"result\""))
			{
				requireThat(System.nanoTime() < deadline, "childStartedInTime").isTrue();
				requireThat(session.completion().toCompletableFuture().isDone(), "endedEarly").isFalse();
				LockSupport.parkNanos(Duration.ofMillis(500).toNanos());
			}

			session.interrupt();
			SessionResult result = await(session);

			requireThat(result, "result").isInstanceOf(SessionResult.Interrupted.class);
		}
	}

	/**
	 * The real adapter with its fixtures.
	 */
	private static final class HarnessSubject implements Subject
	{
		private final TemporaryDirectories cleanup;
		private final SessionPort port;
		private final Path workspace;
		private final Path runRoot;

		/**
		 * Creates a subject.
		 *
		 * @param cleanup   deletes the fixtures
		 * @param port      the adapter
		 * @param workspace the source directory
		 * @param runRoot   the directory of run directories
		 */
		private HarnessSubject(TemporaryDirectories cleanup, SessionPort port, Path workspace, Path runRoot)
		{
			this.cleanup = cleanup;
			this.port = port;
			this.workspace = workspace;
			this.runRoot = runRoot;
		}

		@Override
		public SessionPort port()
		{
			return port;
		}

		@Override
		public Path workspace()
		{
			return workspace;
		}

		@Override
		public Set<HostLogin> acceptableLogins()
		{
			return Set.of(HostLogin.SUBSCRIPTION, HostLogin.API_KEY);
		}

		@Override
		public String quickPrompt()
		{
			return "Reply with the single word ok. Do not use any tool.";
		}

		@Override
		public String longPrompt()
		{
			return "Use the Bash tool to run `sleep 240`, and then reply with the single word done.";
		}

		@Override
		public void awaitTurnInProgress(SessionHandle session) throws IOException, InterruptedException
		{
			long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
			while (!transcriptContains("tool_use"))
			{
				requireThat(System.nanoTime() < deadline, "turnStartedInTime").isTrue();
				requireThat(session.completion().toCompletableFuture().isDone(), "endedEarly").isFalse();
				LockSupport.parkNanos(Duration.ofMillis(500).toNanos());
				if (Thread.interrupted())
					throw new InterruptedException();
			}
		}

		@Override
		public void finish(SessionHandle session)
		{
			// A real harness ends by itself.
		}

		/**
		 * Indicates whether any session's transcript contains the text.
		 *
		 * @param text the text
		 * @return {@code true} if one does
		 * @throws IOException if a transcript cannot be read
		 */
		private boolean transcriptContains(String text) throws IOException
		{
			Path claude = runRoot.resolve("claude");
			if (!Files.isDirectory(claude))
				return false;
			try (Stream<Path> files = Files.walk(claude, 3))
			{
				for (Path file : files.filter(f -> f.getFileName().toString().equals("transcript.jsonl")).toList())
				{
					if (Files.readString(file, UTF_8).contains(text))
						return true;
				}
			}
			return false;
		}

		@Override
		public void close()
		{
			try
			{
				cleanup.close();
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		}
	}
}
