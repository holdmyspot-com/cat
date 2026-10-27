package com.holdmyspot.cat.claude.optimizer.test;

import com.holdmyspot.cat.common.JvmScope;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static com.holdmyspot.cat.claude.optimizer.test.RecordedClaude.recorded;
import static com.holdmyspot.cat.common.optimizer.adapter.fakes.test.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.optimizer.ClaudeSessions;
import com.holdmyspot.cat.claude.secrets.ClaudeSessionsSecrets;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.adapter.fakes.test.RoleRuleFixtures;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;
import com.holdmyspot.cat.common.optimizer.port.contract.test.SessionPortContract.Subject;
import com.holdmyspot.cat.common.optimizer.port.contract.test.SessionPortContract;
import com.holdmyspot.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests {@link ClaudeSessions}' production decisions over recorded Claude {@code stream-json} streams, without
 * starting a process.
 * <p>
 * The package gives the test access to the adapter's existing bridge seam. Recording provenance, substitutions,
 * and the limits of replay evidence are documented in {@code optimizer/sessions/README.md}.
 */
public final class ClaudeSessionsContractTest
{
	private static final Duration LIMIT = Duration.ofMinutes(5);
	/**
	 * How long a replay may run before the test treats it as hung. Replays finish in well under a second; the margin
	 * keeps a loaded build machine from failing a correct adapter.
	 */
	private static final long HANG_SECONDS = 30;

	/**
	 * Creates the test.
	 */
	public ClaudeSessionsContractTest()
	{
	}

	/**
	 * Creates a port over an explicitly supplied bridge and test-owned configuration directory.
	 *
	 * @param jvm the borrowed process scope
	 * @param root   the directory holding configuration and session artifacts
	 * @param bridge the replacement for the runner's Claude process
	 * @return the production adapter
	 */
	private static ClaudeSessions port(JvmScope jvm, Path root, Bridge bridge)
	{
		return ClaudeSessionsSecrets.getClaudeSessionsAccess().
			newClaudeSessions(new RunnerSessions.Settings(jvm, root, root.resolve("runs"),
			List::of, "", false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer()),
			bridge);
	}

	/**
	 * Creates a request whose requested alias differs from the recorded model.
	 *
	 * @param root    the test-owned source directory
	 * @param role    the session's purpose
	 * @param prompts prompts sent to the adapter, in turn order
	 * @return the request
	 */
	private static SessionRequest request(Path root, SessionRole role, String... prompts)
	{
		return new SessionRequest(role, partOf(role), root, List.of(prompts), List.of(), LIMIT,
			new TrialProfile("haiku", ""));
	}

	/**
	 * Waits for the adapter to classify the session.
	 *
	 * @param handle the session
	 * @return its result
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException   if classification fails
	 * @throws TimeoutException     if the replay does not finish
	 */
	private static SessionResult await(SessionHandle handle)
		throws InterruptedException, ExecutionException, TimeoutException
	{
		return handle.completion().toCompletableFuture().get(HANG_SECONDS, TimeUnit.SECONDS);
	}

	/**
	 * Creates the contract over the real adapter, replaying the recorded turns once per session.
	 *
	 * @param jvm the borrowed process scope, open until the contract check completes
	 * @param turns the recordings, one per turn
	 * @return the contract
	 */
	private static SessionPortContract contract(JvmScope jvm, List<List<String>> turns)
	{
		return new SessionPortContract(() ->
		{
			Path root = Files.createTempDirectory(jvm.temporaryDirectory(), "claude-contract-");
			TemporaryDirectories cleanup = new TemporaryDirectories();
			cleanup.add(root);
			RecordedClaude claude = new RecordedClaude(turns);
			return new RecordedSubject(cleanup, port(jvm, root, claude), root, claude);
		});
	}

	/**
	 * Verifies the shared contract check that a completed two-turn session reports evidence of every turn.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(timeOut = 60_000)
	public void completesWithEvidenceOfEveryTurnContract() throws Exception
	{
		// Requirement: R-E13
		// Requirement: R-E16
		// Requirement: R-E6
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contract(jvm, List.of(recorded("two-turn-trial-turn-1.jsonl"), recorded("two-turn-trial-turn-2.jsonl"))).
				completesWithEvidenceOfEveryTurn();
		}
	}

	/**
	 * Verifies the shared contract check that a judge leaves its read-only evidence unchanged.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(timeOut = 60_000)
	public void judgeLeavesReadOnlyEvidenceUnchanged() throws Exception
	{
		// Requirement: R-E13
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contract(jvm, List.of(recorded("judge-turn-1.jsonl"))).judgeLeavesReadOnlyEvidenceUnchanged();
		}
	}

	/**
	 * Verifies the shared contract check that an interrupt ends a running turn as interrupted, over a turn that Claude
	 * ended in answer to CAT's interrupt request, and that the adapter wrote exactly one interrupt request to Claude.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(timeOut = 60_000)
	public void interruptEndsARunningTurnAsInterrupted() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E15
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "claude-contract-"));
				RecordedClaude claude = new RecordedClaude(List.of(recorded("interrupted-judge-turn-1.jsonl")));

				new SessionPortContract(() -> new RecordedSubject(new TemporaryDirectories(), port(jvm, root, claude), root,
					claude)).interruptEndsARunningTurnAsInterrupted();

				List<JsonNode> requests = claude.controlRequests();
				requireThat(requests.size(), "controlRequests.size").isEqualTo(1);
				requireThat(requests.getFirst().path("request").path("subtype").asString(""), "subtype").
					isEqualTo("interrupt");
			}
		}
	}

	/**
	 * Verifies that an interrupt after a completed recorded session preserves its result and writes no interrupt
	 * request to Claude.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(timeOut = 60_000)
	public void interruptAfterTheEndChangesNothing() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E15
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "claude-contract-"));
				RecordedClaude claude = new RecordedClaude(List.of(recorded("trial-turn-1.jsonl")));

				new SessionPortContract(() -> new RecordedSubject(new TemporaryDirectories(), port(jvm, root, claude), root,
					claude)).interruptAfterTheEndChangesNothing();

				requireThat(claude.controlRequests().isEmpty(), "controlRequests.isEmpty").isTrue();
			}
		}
	}

	/**
	 * Verifies the shared contract check that the port classifies the host's login without starting a session.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(timeOut = 60_000)
	public void reportsTheHostLoginWithoutASession() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E7
			new SessionPortContract(() ->
			{
				Path root = Files.createTempDirectory(jvm.temporaryDirectory(), "claude-contract-");
				TemporaryDirectories cleanup = new TemporaryDirectories();
				cleanup.add(root);
				RecordedClaude claude = new RecordedClaude(List.of());
				return new RecordedSubject(cleanup, port(jvm, root, (_, _, _) ->
				{
					throw new AssertionError("Login classification must not start a session");
				}), root, claude);
			}).reportsTheHostLoginWithoutASession();
		}
	}

	/**
	 * Verifies that the adapter completes a real two-turn stream, takes each turn's reply from that turn's events,
	 * retains both turns in order, reports the final cumulative usage rather than adding the turns' totals, selects
	 * the effects directory, and removes the runtime home.
	 *
	 * @throws IOException          if a fixture cannot be created or read
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException   if classification fails
	 * @throws TimeoutException     if the replay does not finish
	 */
	@Test(timeOut = 60_000)
	public void completesWithEvidenceOfEveryTurn()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			// Requirement: R-E16
			// Requirement: R-E6
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "claude-contract-"));
				List<String> first = recorded("two-turn-trial-turn-1.jsonl");
				List<String> second = recorded("two-turn-trial-turn-2.jsonl");
				ClaudeSessions sessions = port(jvm, root, new RecordedClaude(List.of(first, second)));

				SessionResult result = await(sessions.start(request(root, SessionRole.TRIAL, "element", "compound")));

				requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
				SessionResult.Completed completed = (SessionResult.Completed) result;
				requireThat(completed.responses(), "responses").isEqualTo(List.of(
					List.of("Hydrogen (H) has atomic number 1: its nucleus contains one proton.\n\nShip small, ship often."),
					List.of("Hydrogen and oxygen form water (H₂O).\n\nShip small, ship often.")));
				List<String> all = new ArrayList<>(first);
				all.addAll(second);
				requireThat(Files.readAllLines(completed.transcript(), UTF_8), "transcript").isEqualTo(all);
				requireThat(completed.usage(), "usage").isEqualTo(new TokenUsage(119_179, 97_940, 627, 337));
				requireThat(completed.effects(), "effects").isEqualTo(completed.transcript().getParent().getParent().
					resolve("project"));
				requireThat(completed.effects(), "effects").isDirectory();
				requireThat(Files.exists(completed.effects().resolveSibling("home")), "homeExists").isFalse();
				requireThat(completed.promptSources(), "promptSources").isNotBlank();
			}
		}
	}

	/**
	 * Verifies that the observed model, cumulative usage and rate-limit utilization come from Claude's records
	 * rather than from the requested alias.
	 *
	 * @throws IOException          if a fixture cannot be created or read
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException   if classification fails
	 * @throws TimeoutException     if the replay does not finish
	 */
	@Test(timeOut = 60_000)
	public void reportsTheObservedModelUsageAndWindows()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E16
			// Requirement: R-E6
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "claude-contract-"));
				ClaudeSessions sessions = port(jvm, root, new RecordedClaude(List.of(recorded("trial-turn-1.jsonl"))));

				SessionResult result = await(sessions.start(request(root, SessionRole.TRIAL, "answer")));

				requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
				SessionResult.Completed completed = (SessionResult.Completed) result;
				requireThat(completed.model(), "model").isEqualTo("claude-haiku-5-5");
				requireThat(completed.usage(), "usage").isEqualTo(new TokenUsage(86_786, 66_468, 544, 242));
				requireThat(completed.windows(), "windows").isEqualTo(List.of(
					new RateLimitWindow(0.79, Instant.ofEpochSecond(1_792_473_600L)),
					new RateLimitWindow(0.34, Instant.ofEpochSecond(1_792_879_200L))));
			}
		}
	}

	/**
	 * Verifies that each role starts an independent session, with its own transcript and effects directory, and
	 * replies with the root session's messages from its recorded role session.
	 * One recorded reply per role exercises every role's request and response handling through the session adapter.
	 * The in-memory harness bridge replays these replies without launching Claude or making a model request.
	 *
	 * @throws IOException          if a fixture cannot be created or read
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException   if classification fails
	 * @throws TimeoutException     if the replay does not finish
	 */
	@Test(timeOut = 60_000)
	public void runsOneSessionPerRole()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "claude-contract-"));
				RoleRuleFixtures.write(root);
				Map<SessionRole, List<String>> events = Map.of(SessionRole.TRIAL, recorded("trial-turn-1.jsonl"),
					SessionRole.JUDGE, recorded("judge-turn-1.jsonl"), SessionRole.TRIAGE, recorded("triage-turn-1.jsonl"),
					SessionRole.APPROACH_RANKER, recorded("triage-turn-1.jsonl"),
					SessionRole.PROMPT_AUTHOR, recorded("prompt-author-turn-1.jsonl"), SessionRole.CONTRACT_AUTHOR,
					recorded("contract-author-turn-1.jsonl"));
				// The contract author's recording replies with an interim message before its tool calls and then its result
				Map<SessionRole, Integer> messages = Map.of(SessionRole.TRIAL, 1, SessionRole.JUDGE, 1,
					SessionRole.TRIAGE, 1, SessionRole.APPROACH_RANKER, 1, SessionRole.PROMPT_AUTHOR, 1,
					SessionRole.CONTRACT_AUTHOR, 2);
				AtomicInteger next = new AtomicInteger();
				ClaudeSessions sessions = port(jvm, root, (arguments, input, output) ->
					new RecordedClaude(List.of(events.get(SessionRole.values()[next.getAndIncrement()]))).
						serve(arguments, input, output));
				Set<Path> transcripts = new HashSet<>();
				Set<Path> effects = new HashSet<>();
				for (SessionRole role : SessionRole.values())
				{
					SessionResult result = await(sessions.start(request(root, role, role.name())));

					requireThat(result, role + ".result").isInstanceOf(SessionResult.Completed.class);
					SessionResult.Completed completed = (SessionResult.Completed) result;
					requireThat(completed.responses().size(), role + ".responses.size").isEqualTo(1);
					requireThat(completed.responses().getFirst().size(), role + ".messages.size").
						isEqualTo(messages.get(role));
					requireThat(Files.readAllLines(completed.transcript(), UTF_8), role + ".transcript").
						isEqualTo(events.get(role));
					transcripts.add(completed.transcript());
					effects.add(completed.effects());
				}
				requireThat(next.get(), "sessionsStarted").isEqualTo(SessionRole.values().length);
				requireThat(transcripts.size(), "transcripts.size").isEqualTo(SessionRole.values().length);
				requireThat(effects.size(), "effects.size").isEqualTo(SessionRole.values().length);
			}
		}
	}

	/**
	 * Supplies the production port and an in-memory Claude process to the shared contract checks.
	 *
	 * @param cleanup   deletes the subject's fixtures
	 * @param port      the production adapter
	 * @param workspace the test-owned source directory
	 * @param claude    the recorded Claude process
	 */
	private record RecordedSubject(TemporaryDirectories cleanup, ClaudeSessions port, Path workspace,
		RecordedClaude claude) implements Subject
	{
		/**
		 * Creates a subject for the shared contract checks.
		 *
		 * @param cleanup   deletes the subject's fixtures
		 * @param port      the production adapter
		 * @param workspace the test-owned source directory
		 * @param claude    the recorded Claude process
		 * @throws NullPointerException if any argument is null
		 */
		private RecordedSubject
		{
			requireThat(cleanup, "cleanup").isNotNull();
			requireThat(port, "port").isNotNull();
			requireThat(workspace, "workspace").isNotNull();
			requireThat(claude, "claude").isNotNull();
		}

		@Override
		public Set<HostLogin> acceptableLogins()
		{
			// The test-owned configuration directory holds no credential file and the environment selects no API key
			return Set.of(HostLogin.UNKNOWN);
		}

		@Override
		public String quickPrompt()
		{
			return "Reply with the single word ok.";
		}

		@Override
		public String longPrompt()
		{
			return "Keep working until interrupted.";
		}

		@Override
		public void awaitTurnInProgress(SessionHandle session) throws InterruptedException
		{
			claude.awaitTurnStarted();
			requireThat(session.completion().toCompletableFuture().isDone(), "endedEarly").isFalse();
		}

		@Override
		public void finish(SessionHandle session)
		{
			// The recorded process supplies each complete turn in response to its user message.
		}

		@Override
		public void close()
		{
			claude.close();
			try
			{
				cleanup.close();
			}
			catch (IOException e)
			{
				throw new AssertionError(e);
			}
		}
	}
}
