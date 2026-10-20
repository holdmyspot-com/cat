package com.holdmyspot.cat.codex.optimizer.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static com.holdmyspot.cat.common.optimizer.adapter.fakes.test.SessionRequestFixtures.partOf;
import static com.holdmyspot.cat.codex.optimizer.test.RecordedAppServer.recorded;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.optimizer.CodexSessions;
import com.holdmyspot.cat.codex.secrets.CodexSessionsSecrets;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.adapter.fakes.test.RoleRuleFixtures;
import com.holdmyspot.cat.common.optimizer.contract.ToolCall;
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
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness;

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
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link CodexSessions}' production decisions over recorded app-server streams, without starting a process.
 * <p>
 * The package gives the test access to the adapter's existing bridge seam. Recording provenance, substitutions,
 * and the limits of replay evidence are documented in {@code optimizer/sessions/README.md}.
 */
public final class CodexSessionsContractTest
{
	private static final Path TEMP = Path.of(System.getProperty("cat.session.temp"));
	private static final Duration LIMIT = Duration.ofMinutes(5);
	/**
	 * How long a replay may run before the test treats it as hung. Replays finish in well under a second; the margin
	 * keeps a loaded build machine from failing a correct adapter.
	 */
	private static final long HANG_SECONDS = 30;
	private static final List<RateLimitWindow> WINDOWS = List.of(
		new RateLimitWindow(0.45, Instant.ofEpochSecond(1_791_046_710L)));

	/**
	 * Creates the test.
	 */
	public CodexSessionsContractTest()
	{
	}

	/**
	 * Creates a port over an explicitly supplied bridge and test-owned configuration directory.
	 *
	 * @param root the directory holding configuration and session artifacts
	 * @param bridge the external app-server replacement
	 * @return the production adapter
	 */
	private static CodexSessions port(Path root, Bridge bridge)
	{
		return CodexSessionsSecrets.access().newCodexSessions(new RunnerSessions.Settings(root, root.resolve("runs"),
			List::of, "", false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer()),
			bridge);
	}

	/**
	 * Creates a request whose requested alias differs from the recorded model.
	 *
	 * @param root the test-owned source directory
	 * @param role the session's purpose
	 * @param prompts prompts sent to the adapter, in turn order
	 * @return the request
	 */
	private static SessionRequest request(Path root, SessionRole role, String... prompts)
	{
		return new SessionRequest(role, partOf(role), root, List.of(prompts), List.of(), LIMIT,
			new TrialProfile("requested-alias", "low"));
	}

	/**
	 * Waits for the adapter to classify the session.
	 *
	 * @param handle the session
	 * @return its result
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException if classification fails
	 * @throws TimeoutException if the replay does not finish
	 */
	private static SessionResult await(SessionHandle handle)
		throws InterruptedException, ExecutionException, TimeoutException
	{
		return handle.completion().toCompletableFuture().get(HANG_SECONDS, TimeUnit.SECONDS);
	}

	/**
	 * Verifies that the adapter interrupts an active turn through JSON-RPC, classifies the recorded terminal status
	 * as interrupted within the contract's bound, and retains every event after a successful bridge shutdown.
	 *
	 * @throws Exception if the contract check or transcript verification fails
	 */
	@Test(timeOut = 60_000)
	public void interruptEndsARunningTurnAsInterrupted() throws Exception
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "codex-contract-"));
			List<String> events = recorded("interrupt-turn-1.jsonl");
			RecordedAppServer server = new RecordedAppServer(List.of(events));

			new SessionPortContract(() -> new RecordedSubject(port(root, server), root, server)).
				interruptEndsARunningTurnAsInterrupted();

			requireThat(Files.readAllLines(server.transcript(), UTF_8), "transcript").isEqualTo(
				RecordedAppServer.rebindResponse(events, 3));
			List<JsonNode> interrupts = server.interrupts();
			requireThat(interrupts.size(), "interrupts.size").isEqualTo(1);
			JsonNode params = interrupts.getFirst().path("params");
			JsonNode terminal = JsonMapper.builder().build().readTree(events.getLast()).path("params");
			requireThat(params.path("threadId"), "threadId").isEqualTo(terminal.path("threadId"));
			requireThat(params.path("turnId"), "turnId").isEqualTo(terminal.path("turn").path("id"));
		}
	}

	/**
	 * Verifies that an interrupt after a completed recorded session preserves its result and sends no interrupt
	 * request to the app server.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(timeOut = 60_000)
	public void interruptAfterTheEndChangesNothing() throws Exception
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "codex-contract-"));
			RecordedAppServer server = new RecordedAppServer(List.of(recorded("trial-turn-1.jsonl")));

			new SessionPortContract(() -> new RecordedSubject(port(root, server), root, server)).
				interruptAfterTheEndChangesNothing();

			requireThat(server.interrupts().isEmpty(), "interrupts.isEmpty").isTrue();
		}
	}

	/**
	 * Verifies that the adapter completes a real two-turn stream, retains both turns in order, reports the final
	 * cumulative usage rather than adding repeated totals, selects the effects directory, and removes the runtime home.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException if classification fails
	 * @throws TimeoutException if the replay does not finish
	 */
	@Test
	public void completesWithEvidenceOfEveryTurn()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E20
		// Requirement: R-E13
		// Requirement: R-E16
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "codex-contract-"));
			Path rules = Files.createDirectories(root.resolve("rules/include/common"));
			Files.writeString(rules.resolve("contract-design.md"), "# Contract design\nDefine the contract.\n", UTF_8);
			List<String> first = recorded("deriver-turn-1.jsonl");
			List<String> second = recorded("deriver-turn-2.jsonl");
			CodexSessions sessions = port(root, new RecordedAppServer(List.of(first, second)));

			SessionResult result = await(sessions.start(request(root, SessionRole.CONTRACT_AUTHOR, "inspect",
				"propose")));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.responses().size(), "responses.size").isEqualTo(2);
			requireThat(completed.responses().getFirst(), "firstResponse").isEqualTo(List.of(
				"I’ll inspect the prompt sources and apply your feedback to the complete proposal " +
					"without changing any files.\n",
				"The inspection is complete, and the revised proposal is settled with only direct-factual-answers, " +
					"gpt-6.1-sol/low, and the requested motto exemption."));
			requireThat(completed.responses().getLast().size(), "secondResponse.size").isEqualTo(1);
			requireThat(completed.responses().getLast().getFirst(), "proposal").contains("\"requirements\": [");
			List<String> all = new ArrayList<>(RecordedAppServer.rebindResponse(first, 3));
			all.addAll(RecordedAppServer.rebindResponse(second, 4));
			requireThat(Files.readAllLines(completed.transcript(), UTF_8), "transcript").isEqualTo(all);
			requireThat(completed.usage(), "usage").isEqualTo(new TokenUsage(291_014, 232_960, 4_804, 1_490));
			requireThat(completed.effects(), "effects").isEqualTo(completed.transcript().getParent().getParent().
				resolve("project"));
			requireThat(completed.effects(), "effects").isDirectory();
			requireThat(Files.exists(completed.effects().resolveSibling("home")), "homeExists").isFalse();
			requireThat(completed.promptSources(), "promptSources").isNotBlank();
		}
	}

	/**
	 * Verifies that the observed model, reasoning effort, cumulative usage and normalized rate-limit utilization
	 * come from harness notifications rather than the requested alias.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException if classification fails
	 * @throws TimeoutException if the replay does not finish
	 */
	@Test
	public void reportsTheObservedModelUsageAndWindows()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E16
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "codex-contract-"));
			CodexSessions sessions = port(root, new RecordedAppServer(List.of(recorded("trial-turn-1.jsonl"))));

			SessionResult result = await(sessions.start(request(root, SessionRole.TRIAL, "answer")));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.model(), "model").isEqualTo("gpt-6.1-sol");
			requireThat(completed.reasoning(), "reasoning").isEqualTo("low");
			requireThat(completed.usage(), "usage").isEqualTo(new TokenUsage(62_269, 29_312, 113, 0));
			requireThat(completed.windows(), "windows").isEqualTo(WINDOWS);
		}
	}

	/**
	 * Verifies subscription classification from a configuration fixture rather than host credentials.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void classifiesTheHostLogin() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "codex-contract-"));
			Files.writeString(root.resolve("auth.json"), "{\"tokens\":{}}", UTF_8);
			CodexSessions sessions = port(root, (_, _, _) ->
			{
				throw new AssertionError("Login classification must not start a session");
			});

			requireThat(sessions.hostLogin(), "login").isEqualTo(HostLogin.SUBSCRIPTION);
		}
	}

	/**
	 * Verifies API-key classification without starting a session, then rejects an unsupported credential-key type.
	 * Fixture values are placeholders, never real credentials.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void reportsTheHostLoginWithoutASession() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "codex-contract-"));
			CodexSessions sessions = port(root, (_, _, _) ->
			{
				throw new AssertionError("Login classification must not start a session");
			});
			Files.writeString(root.resolve("auth.json"), "{\"OPENAI_API_KEY\":\"placeholder\"}", UTF_8);

			requireThat(sessions.hostLogin(), "apiKeyLogin").isEqualTo(HostLogin.API_KEY);
			Files.writeString(root.resolve("auth.json"), "{\"OPENAI_API_KEY\":{}}", UTF_8);
			requireThat(sessions.hostLogin(), "unsupportedKeyType").isEqualTo(HostLogin.UNKNOWN);
		}
	}

	/**
	 * Verifies that each role starts an independent session, with its own transcript and effects directory.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException if classification fails
	 * @throws TimeoutException if the replay does not finish
	 */
	@Test
	public void runsOneSessionPerRole()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E20
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "codex-contract-"));
			RoleRuleFixtures.write(root);
			// A triage session, like a judge session, answers with one JSON object, so it replays the judge's recording
			Map<SessionRole, List<String>> events = Map.of(SessionRole.TRIAL, recorded("trial-turn-1.jsonl"),
				SessionRole.JUDGE, recorded("judge-turn-1.jsonl"), SessionRole.TRIAGE, recorded("judge-turn-1.jsonl"),
				SessionRole.PROMPT_AUTHOR, recorded("author-turn-1.jsonl"), SessionRole.CONTRACT_AUTHOR,
				recorded("deriver-turn-1.jsonl"));
			Map<SessionRole, Integer> messages = Map.of(SessionRole.TRIAL, 1, SessionRole.JUDGE, 2,
				SessionRole.TRIAGE, 2, SessionRole.PROMPT_AUTHOR, 3, SessionRole.CONTRACT_AUTHOR, 2);
			AtomicInteger next = new AtomicInteger();
			CodexSessions sessions = port(root, (arguments, input, output) ->
				new RecordedAppServer(List.of(events.get(SessionRole.values()[next.getAndIncrement()]))).
					serve(arguments, input, output));
			Set<Path> transcripts = new HashSet<>();
			Set<Path> effects = new HashSet<>();
			for (SessionRole role : SessionRole.values())
			{
				SessionResult result = await(sessions.start(request(root, role, role.name())));

				requireThat(result, role + ".result").isInstanceOf(SessionResult.Completed.class);
				SessionResult.Completed completed = (SessionResult.Completed) result;
				requireThat(completed.responses().size(), "responses.size").isEqualTo(1);
				requireThat(completed.responses().getFirst().size(), "messages.size").
					isEqualTo(messages.get(role));
				requireThat(Files.readAllLines(completed.transcript(), UTF_8), "transcript").isEqualTo(
					RecordedAppServer.rebindResponse(events.get(role), 3));
				transcripts.add(completed.transcript());
				effects.add(completed.effects());
			}
			requireThat(next.get(), "sessionsStarted").isEqualTo(SessionRole.values().length);
			requireThat(transcripts.size(), "transcripts.size").isEqualTo(SessionRole.values().length);
			requireThat(effects.size(), "effects.size").isEqualTo(SessionRole.values().length);
		}
	}

	/**
	 * Verifies that the session archives a recorded child command and its root relationship, while excluding the
	 * child's command from root tool-call accounting. The recording shows a failed command attempt; replay does not
	 * create a child file or claim that the command succeeded.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException if classification fails
	 * @throws TimeoutException if the replay does not finish
	 */
	@Test
	public void recordsAChildOfTheSession()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "codex-contract-"));
			List<String> events = recorded("child-work.jsonl");
			CodexSessions sessions = port(root, (options, input, output) -> ScriptedHarness.serve(
				Path.of(ScriptedHarness.optionValue(options, "--run-root")), "codex",
				List.of(Turn.replies("done", events.toArray(String[]::new))), input, output));

			SessionResult result = await(sessions.start(request(root, SessionRole.TRIAL, "delegate")));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(Files.readAllLines(completed.transcript(), UTF_8), "transcript").isEqualTo(events);
			requireThat(completed.toolCalls(), "rootToolCalls").isEqualTo(List.of(
				new ToolCall("commandExecution", "/bin/bash -lc true", 1),
				new ToolCall("commandExecution", "/bin/bash -lc false", 1), new ToolCall("wait", null, null)));
		}
	}

	/**
	 * Supplies the production port and an in-memory app server to the shared interrupt checks.
	 *
	 * @param port the production adapter
	 * @param workspace the test-owned source directory
	 * @param server the recorded external boundary
	 */
	private record RecordedSubject(CodexSessions port, Path workspace, RecordedAppServer server) implements Subject
	{
		/**
		 * Creates a subject for the shared interrupt checks.
		 *
		 * @param port the production adapter
		 * @param workspace the test-owned source directory
		 * @param server the recorded external boundary
		 * @throws NullPointerException if any argument is null
		 */
		private RecordedSubject
		{
			requireThat(port, "port").isNotNull();
			requireThat(workspace, "workspace").isNotNull();
			requireThat(server, "server").isNotNull();
		}

		@Override
		public Set<HostLogin> acceptableLogins()
		{
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
			server.awaitTurnInProgress();
			requireThat(session.completion().toCompletableFuture().isDone(), "endedEarly").isFalse();
		}

		@Override
		public void finish(SessionHandle session)
		{
			// The app server supplies the complete trial recording in response to turn/start.
		}

		@Override
		public void close()
		{
			server.close();
		}
	}
}
