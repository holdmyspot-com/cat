package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import io.github.cowwoc.cat.codex.optimizer.CodexSessions;
import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.contract.SessionPortContract;
import io.github.cowwoc.cat.common.optimizer.port.contract.SessionPortContract.Subject;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexSessions} against a real Codex process.
 * <p>
 * The tests run only in the {@code native} group, with {@code -Dcat.native.harness=codex}. They live in the runner
 * test package because they read the host's credential location, which optimizer sources must not do.
 */
public final class CodexSessionsNativeTest
{
	private static final Duration LIMIT = Duration.ofMinutes(10);
	private static final long WAIT_MINUTES = 12;
	private static final Pattern THREAD_ID = Pattern.compile("\"threadId\":\"([^\"]+)\"");

	/**
	 * Creates the test fixture.
	 */
	public CodexSessionsNativeTest()
	{
	}

	/**
	 * Skips the test unless the build selected this harness.
	 *
	 * @throws SkipException if the build did not select Codex
	 */
	private static void requireHarness()
	{
		if (!"codex".equals(System.getProperty("cat.native.harness")))
			throw new SkipException("Run with -Pnative -Dcat.native.harness=codex");
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
	 * Returns the host's Codex configuration directory, as the codex executable locates it.
	 *
	 * @return the directory
	 */
	private static Path configurationDirectory()
	{
		Map<String, String> environment = environment();
		String configured = environment.get("CODEX_HOME");
		if (configured != null && !configured.isBlank())
			return Path.of(configured);
		return Path.of(environment.get("HOME")).resolve(".codex");
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
		CodexSessions sessions = new CodexSessions(new CodexSessions.Settings(artifact, runRoot, "", "",
			false, environment(), configurationDirectory(), HarnessCapacity.unlimited(), Clock.systemUTC()));
		return new HarnessSubject(cleanup, sessions, source, runRoot);
	}

	/**
	 * Creates the smallest directory that the runner accepts as a Codex artifact.
	 *
	 * @param artifact the directory to create
	 * @return {@code artifact}
	 * @throws IOException if the filesystem operation fails
	 */
	private static Path createArtifact(Path artifact) throws IOException
	{
		Files.createDirectories(artifact.resolve(".codex-plugin"));
		Files.createDirectories(artifact.resolve("client/bin"));
		Files.writeString(artifact.resolve(".codex-plugin/plugin.json"), "{}\n", UTF_8);
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
		return new SessionPortContract(CodexSessionsNativeTest::open);
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
			SessionHandle session = subject.port().start(new SessionRequest(SessionRole.TRIAL, subject.workspace(),
				List.of(subject.quickPrompt()), List.of(), LIMIT));

			SessionResult result = await(session);

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.model(), "model").isNotBlank();
			requireThat(completed.reasoning(), "reasoning").isNotBlank();
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
				SessionHandle session = subject.port().start(new SessionRequest(role, subject.workspace(),
					List.of(subject.quickPrompt()), List.of(), LIMIT));

				SessionResult result = await(session);

				requireThat(result, role + ".result").isInstanceOf(SessionResult.Completed.class);
				int responses = ((SessionResult.Completed) result).responses().size();
				requireThat(responses, role + ".responses.size()").isEqualTo(1);
			}
		}
	}

	/**
	 * Verifies that a session whose root turn delegates to a child records the child's thread and work as part
	 * of the same session.
	 *
	 * @throws Exception if the session fails
	 */
	@Test(groups = "native", timeOut = 1_800_000)
	public void recordsAChildOfTheSession() throws Exception
	{
		// Requirement: R-E14
		try (HarnessSubject subject = open())
		{
			String prompt = "Do not create any file yourself. Delegate with your child-agent (sub-agent) tool: " +
				"start exactly one child agent whose only job is to create the file child.txt in the current " +
				"directory, containing the text child-done, and then reply done. Wait for the child agent to " +
				"finish, then reply with the single word finished.";
			SessionHandle session = subject.port().start(new SessionRequest(SessionRole.TRIAL, subject.workspace(),
				List.of(prompt), List.of(), LIMIT));

			SessionResult result = await(session);

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			String transcript = Files.readString(completed.transcript(), UTF_8);
			Set<String> threads = new HashSet<>();
			Matcher matcher = THREAD_ID.matcher(transcript);
			while (matcher.find())
				threads.add(matcher.group(1));
			requireThat(threads.size(), "threads.size()").isGreaterThanOrEqualTo(2);
			Path child = completed.effects().resolve("child.txt");
			requireThat(Files.isRegularFile(child), "child.txt.exists").isTrue();
			requireThat(Files.readString(child, UTF_8), "child.txt").contains("child-done");
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
			return "Reply with the single word ok. Do not run any command.";
		}

		@Override
		public String longPrompt()
		{
			return "Run `sleep 240` in the shell, and then reply with the single word done.";
		}

		@Override
		public void awaitTurnInProgress(SessionHandle session) throws IOException, InterruptedException
		{
			long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
			while (!transcriptContains("commandExecution"))
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
			Path harness = runRoot.resolve("codex");
			if (!Files.isDirectory(harness))
				return false;
			try (Stream<Path> files = Files.walk(harness, 3))
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
