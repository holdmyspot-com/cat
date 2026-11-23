package com.holdmyspot.cat.codex.optimizer.test;

import com.holdmyspot.cat.common.JvmScope;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

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
import com.holdmyspot.cat.common.optimizer.execution.contract.test.DriverInterruptReplayContract;
import com.holdmyspot.cat.common.optimizer.adapter.fakes.test.RoleRuleFixtures;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.ToolCall;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.execution.Judgment;
import com.holdmyspot.cat.common.optimizer.execution.prompt.JudgePrompt;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.testng.annotations.DataProvider;
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
	private static final Duration LIMIT = Duration.ofMinutes(5);
	/**
	 * How long a replay may run before the test treats it as hung. Replays finish in well under a second; the margin
	 * keeps a loaded build machine from failing a correct adapter.
	 */
	private static final long HANG_SECONDS = 30;
	private static final List<RateLimitWindow> WINDOWS = List.of(
		new RateLimitWindow(0.0, Instant.ofEpochSecond(1_794_955_338L)),
		new RateLimitWindow(1.0, Instant.ofEpochSecond(1_795_143_402L)));

	/**
	 * Creates the test.
	 */
	public CodexSessionsContractTest()
	{
	}

	/**
	 * Returns the optimizer roles with a captured two-turn case-number session.
	 *
	 * @return each session role and its recording filename prefix
	 */
	@DataProvider
	public static Object[][] twoTurnRoleRecordings()
	{
		return new Object[][]
		{
			{SessionRole.JUDGE, "two-turn-judge"},
			{SessionRole.TRIAGE, "two-turn-triage"},
			{SessionRole.APPROACH_RANKER, "two-turn-approach-ranker"},
			{SessionRole.PROMPT_AUTHOR, "two-turn-prompt-author"}
		};
	}

	/**
	 * Creates a port over an explicitly supplied bridge and test-owned configuration directory.
	 *
	 * @param jvm the borrowed process scope
	 * @param root the directory holding configuration and session artifacts
	 * @param bridge the external app-server replacement
	 * @return the production adapter
	 */
	private static CodexSessions port(JvmScope jvm, Path root, Bridge bridge)
	{
		return CodexSessionsSecrets.getCodexSessionsAccess().
			newCodexSessions(new RunnerSessions.Settings(jvm, root, root.resolve("results"),
			List::of, false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer()),
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
	 * Creates the contract over the real adapter, replaying the recorded turns once per session.
	 *
	 * @param jvm   the borrowed process scope, open until the contract check completes
	 * @param turns the recordings, one per turn
	 * @return the contract
	 */
	private static SessionPortContract contract(JvmScope jvm, List<List<String>> turns)
	{
		return new SessionPortContract(() ->
		{
			Path root = Files.createTempDirectory(jvm.temporaryDirectory(), "codex-contract-");
			TemporaryDirectories cleanup = new TemporaryDirectories();
			cleanup.add(root);
			RecordedAppServer server = new RecordedAppServer(turns);
			return new RecordedSubject(cleanup, port(jvm, root, server), root, server);
		});
	}

	/**
	 * Creates the contract over the real adapter, with a bridge that no check may reach.
	 *
	 * @param jvm the borrowed process scope, open until the contract check completes
	 * @return the contract
	 */
	private static SessionPortContract contractWithoutSessions(JvmScope jvm)
	{
		return new SessionPortContract(() ->
		{
			Path root = Files.createTempDirectory(jvm.temporaryDirectory(), "codex-contract-");
			TemporaryDirectories cleanup = new TemporaryDirectories();
			cleanup.add(root);
			return new RecordedSubject(cleanup, port(jvm, root, (_, _, _) ->
			{
				throw new AssertionError("The check must not start a session");
			}), root, new RecordedAppServer(List.of()));
		});
	}

	/**
	 * Verifies the shared contract check that a completed two-turn session reports evidence of every turn.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test
	public void completesWithEvidenceOfEveryTurnContract() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			// Requirement: R-E16
			// Requirement: R-E6
			contract(jvm, List.of(recorded("two-turn-trial-turn-1.jsonl"), recorded("two-turn-trial-turn-2.jsonl"))).
				completesWithEvidenceOfEveryTurn();
		}
	}

	/**
	 * Runs the shared check that a scripted judge session completes and leaves
	 * the local evidence fixture bytes unchanged.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test
	public void judgeKeepsLocalEvidence() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			contract(jvm, List.of(recorded("judge-turn-1.jsonl"))).judgeKeepsLocalEvidence();
		}
	}

	/**
	 * Replays the native file trial and its judge, checking the staged evidence against the recorded write and verdict.
	 *
	 * @throws Exception if the recording cannot be replayed or the evidence cannot be read
	 */
	@Test
	public void replaysE5FileTrialAndJudge() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope();
		     TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-e5-file-replay-"));
			SessionResult.Completed trial = replayE5(jvm, request(root, SessionRole.TRIAL, "Replay the E5 file trial."),
				"e5-file-trial-turn-1.jsonl");
			byte[] capturedAnswer;
			try (InputStream stream = getClass().getResourceAsStream("/optimizer/sessions/e5-answer.md"))
			{
				requireThat(stream, "answerResource").isNotNull();
				capturedAnswer = stream.readAllBytes();
			}
			String answer = new String(capturedAnswer, UTF_8);
			requireThat(recordedAnswerWrites(trial.transcript()), "recordedAnswerWrites").isEqualTo(List.of(answer));
			String reply = trial.responses().getLast().getLast();
			requireThat(reply.contains(answer.strip()), "answerInChat").isFalse();
			Path output = trial.effects().resolve("answer.md");
			Files.write(output, capturedAnswer);
			SessionRequest request = new SessionRequest(SessionRole.JUDGE, partOf(SessionRole.JUDGE), root,
				List.of(reply), List.of(output), LIMIT, new TrialProfile("requested-alias", "low"));
			SessionResult.Completed judge = replayE5(jvm, request, "e5-file-judge-turn-1.jsonl");
			String addedLines = answer.lines().map(line -> "+" + line).collect(Collectors.joining("\n", "", "\n"));
			requireThat(recordedJudgeInput(judge.transcript()), "fileJudgeInput").contains("+++ b/answer.md").
				contains(addedLines).contains(reply);
			requireThat(Files.readAllBytes(output), "answerAfterJudge").isEqualTo(capturedAnswer);
			assertE5Verdict(judge, root, "file_explains_question", "write_acknowledged", "one_chat_sentence");
		}
	}

	/**
	 * Replays the native text trial and its judge and checks the production parser's recorded decisions.
	 *
	 * @throws Exception if the recording cannot be replayed or the evidence cannot be read
	 */
	@Test
	public void replaysE5TextTrialAndJudge() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope();
		     TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-e5-text-replay-"));
			SessionResult.Completed trial = replayE5(jvm, request(root, SessionRole.TRIAL, "Replay the E5 text trial."),
				"e5-text-trial-turn-1.jsonl");
			requireThat(recordedAnswerWrites(trial.transcript()), "textAnswerWrites").isEmpty();
			String reply = trial.responses().getLast().getLast();
			SessionResult.Completed judge = replayE5(jvm, request(root, SessionRole.JUDGE, reply),
				"e5-text-judge-turn-1.jsonl");
			requireThat(recordedJudgeInput(judge.transcript()), "textJudgeInput").contains(reply);
			assertE5Verdict(judge, root, "chat_answers_question", "one_chat_sentence");
		}
	}

	/**
	 * Replays one recorded turn through the production adapter.
	 *
	 * @param jvm the borrowed process scope
	 * @param request the replay's session request
	 * @param recording the event resource
	 * @return the completed session
	 * @throws Exception if the recording cannot be replayed
	 */
	private static SessionResult.Completed replayE5(JvmScope jvm, SessionRequest request, String recording)
		throws Exception
	{
		RecordedAppServer server = new RecordedAppServer(List.of(recorded(recording)));
		try
		{
			Bridge bridge = (arguments, input, output) ->
			{
				if (!request.readOnly().isEmpty())
				{
					requireThat(ScriptedHarness.optionValue(arguments, "--read-only-evidence"), "readOnlyEvidence").
						isEqualTo(request.readOnly().getFirst().toString());
				}
				server.serve(arguments, input, output);
			};
			SessionResult result = await(port(jvm, request.source(), bridge).start(request));
			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			return (SessionResult.Completed) result;
		}
		finally
		{
			server.close();
		}
	}

	/**
	 * Reads the contents of completed writes adding the trial's answer file.
	 *
	 * @param transcript the adapter's native transcript
	 * @return the recorded contents in event order
	 * @throws IOException if the transcript cannot be read
	 */
	private static List<String> recordedAnswerWrites(Path transcript) throws IOException
	{
		JsonMapper json = JsonMapper.builder().build();
		try (Stream<String> lines = Files.lines(transcript, UTF_8))
		{
			return lines.map(json::readTree).filter(event -> event.path("method").asString("").equals("item/completed")).
				map(event -> event.path("params").path("item")).
				filter(item -> item.path("type").asString("").equals("fileChange") &&
					item.path("status").asString("").equals("completed")).
				flatMap(item -> item.path("changes").valueStream()).
				filter(change -> change.path("path").asString("").endsWith("/answer.md") &&
					change.path("kind").path("type").asString("").equals("add")).
				map(change -> change.path("diff").asString()).toList();
		}
	}

	/**
	 * Returns the user input delivered to the recorded judge.
	 *
	 * @param transcript the adapter's native transcript
	 * @return the recorded prompt
	 * @throws IOException if the transcript cannot be read
	 */
	private static String recordedJudgeInput(Path transcript) throws IOException
	{
		JsonMapper json = JsonMapper.builder().build();
		try (Stream<String> lines = Files.lines(transcript, UTF_8))
		{
			return lines.map(json::readTree).filter(event -> event.path("method").asString("").equals("item/completed")).
				map(event -> event.path("params").path("item")).
				filter(item -> item.path("type").asString("").equals("userMessage")).
				flatMap(item -> item.path("content").valueStream()).
				map(part -> part.path("text").asString("")).collect(Collectors.joining("\n"));
		}
	}

	/**
	 * Checks the recorded all-pass decision through the production judge parser.
	 *
	 * @param judge the replayed judge
	 * @param root the test-owned workspace
	 * @param assertions the recorded assertion identifiers preceding the expected outcome
	 */
	private static void assertE5Verdict(SessionResult.Completed judge, Path root, String... assertions)
	{
		List<JudgedAssertion> rubric = new ArrayList<>();
		for (String id : assertions)
		{
			Assertion assertion = new Assertion(id, AssertionKind.EFFECT, null, OnToolFailure.FAIL,
				"The recorded E5 assertion is satisfied.");
			Requirement requirement = new Requirement(id, assertion.statement(), Requirement.GOAL, null,
				List.of("unsatisfied"));
			rubric.add(new JudgedAssertion(assertion, requirement));
		}
		rubric.add(new JudgedAssertion(Assertion.expectedOutcome(AssertionKind.EFFECT), null));
		Judgment result = new JudgePrompt(JsonMapper.builder().build(), root.resolve("judge")).
			judgment(judge, rubric).orElseThrow();
		requireThat(result.failures(), "failedAssertions").isEmpty();
		for (JudgedAssertion assertion : rubric)
			requireThat(result.rationale(), "judgeRationale").contains("- `" + assertion.assertion().id() + "`: pass.");
	}

	/**
	 * Verifies the shared contract check that the port classifies the host's login without starting a session.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test
	public void hostLoginIsReportedContract() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E7
			contractWithoutSessions(jvm).reportsTheHostLoginWithoutASession();
		}
	}

	/**
	 * Verifies the shared contract check that the port reports that Codex reports exit statuses.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test
	public void reportsExitStatusAsTheHarnessDoes() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E7
			contractWithoutSessions(jvm).reportsExitStatusAsTheHarnessDoes();
		}
	}

	/**
	 * Verifies the shared contract check that the port identifies its versions and results folders.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test
	public void identifiesItsVersionsAndResultsFolders() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E7
			contractWithoutSessions(jvm).identifiesItsVersionsAndResultsFolders();
		}
	}

	/**
	 * Verifies the shared contract check that an empty host loads no prompt sources, registers no hooks and has no
	 * installed plugins.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test
	public void reportsNothingForAnEmptyHost() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E7
			contractWithoutSessions(jvm).reportsNothingForAnEmptyHost();
		}
	}

	/**
	 * Verifies the shared contract check that the evidence read from a completed recording is the same on every read.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test
	public void readsTheEvidenceOfACompletedTranscript() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			contract(jvm, List.of(recorded("trial-turn-1.jsonl"))).readsTheEvidenceOfACompletedTranscript();
		}
	}

	/**
	 * Verifies the shared contract check that an interrupted session carries the window that Codex reported.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test
	public void interruptKeepsTheWindowsReportedBeforeIt() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E10
			contract(jvm, List.of(recorded("interrupt-turn-1.jsonl"))).interruptKeepsTheWindowsReportedBeforeIt();
		}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E10
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-contract-"));
				List<String> events = recorded("interrupted-trial-turn-1.jsonl");
				RecordedAppServer server = new RecordedAppServer(List.of(events));

				new SessionPortContract(() -> new RecordedSubject(new TemporaryDirectories(), port(jvm, root, server), root,
					server)).
					interruptEndsARunningTurnAsInterrupted();

				requireThat(Files.readAllLines(server.transcript(), UTF_8), "transcript").isEqualTo(
					server.turnOutput());
				List<JsonNode> interrupts = server.interrupts();
				requireThat(interrupts.size(), "interrupts.size").isEqualTo(1);
				JsonNode params = interrupts.getFirst().path("params");
				// The replay names the thread after its nested session, so the terminal event is read as the adapter received
				// it
				JsonNode terminal = JsonMapper.builder().build().readTree(server.turnOutput().getLast()).path("params");
				requireThat(params.path("threadId"), "threadId").isEqualTo(terminal.path("threadId"));
				requireThat(params.path("turnId"), "turnId").isEqualTo(terminal.path("turn").path("id"));
			}
		}
	}

	/**
	 * Replays a recorded adapter interruption through a real driver, then reopens and resumes the same campaign.
	 * The replay gate waits for the recorded turn to start before the driver requests interruption.
	 *
	 * @throws IOException          if replay fixtures or campaign state cannot be read or written
	 * @throws InterruptedException if the test thread is interrupted
	 * @throws ExecutionException   if the adapter cannot classify the recorded turn
	 * @throws TimeoutException     if the adapter does not classify the recorded turn within 30 seconds
	 */
	@Test(timeOut = 60_000)
	public void recordedInterruptResumesTrials()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E10
			Path root = Files.createTempDirectory(jvm.temporaryDirectory(), "codex-driver-interrupt-replay-");
			RecordedAppServer server = new RecordedAppServer(List.of(recorded("interrupt-turn-1.jsonl")));
			try
			{
				DriverInterruptReplayContract.replaysInterruptedAttempt(port(jvm, root, server), server::awaitTurnInProgress);
			}
			finally
			{
				server.close();
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E10
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-contract-"));
				RecordedAppServer server = new RecordedAppServer(List.of(recorded("trial-turn-1.jsonl")));

				new SessionPortContract(() -> new RecordedSubject(new TemporaryDirectories(), port(jvm, root, server), root,
					server)).
					interruptAfterTheEndChangesNothing();

				requireThat(server.interrupts().isEmpty(), "interrupts.isEmpty").isTrue();
			}
		}
	}

	/**
	 * Verifies that the adapter completes a real two-turn stream, retains both turns in order, reports the final
	 * cumulative usage rather than adding repeated totals, selects the effects directory, and removes the runtime home.
	 * Both complete recorded streams pass through the in-memory app-server adapter and its persisted transcript;
	 * processing and validating that evidence can exceed one second in the parallel build. No process starts.
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E20
			// Requirement: R-E13
			// Requirement: R-E16
			// Requirement: R-E6
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-contract-"));
				Path rules = Files.createDirectories(root.resolve("rules/include/common"));
				Files.writeString(rules.resolve("contract-design.md"), "# Contract design\nDefine the contract.\n", UTF_8);
				List<String> first = recorded("deriver-turn-1.jsonl");
				List<String> second = recorded("deriver-turn-2.jsonl");
				RecordedAppServer server = new RecordedAppServer(List.of(first, second));
				CodexSessions sessions = port(jvm, root, server);

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
				requireThat(Files.readAllLines(completed.transcript(), UTF_8), "transcript").
					isEqualTo(server.turnOutput());
				requireThat(completed.usage(), "usage").isEqualTo(new TokenUsage(291_014, 232_960, 4_804, 1_490));
				requireThat(completed.effects(), "effects").isEqualTo(completed.transcript().getParent().getParent().
					resolve("project"));
				requireThat(completed.effects(), "effects").isDirectory();
				requireThat(Files.exists(completed.effects().resolveSibling("home")), "homeExists").isFalse();
				requireThat(completed.promptSources(), "promptSources").isNotBlank();
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E16
			// Requirement: R-E6
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-contract-"));
				CodexSessions sessions = port(jvm, root, new RecordedAppServer(List.of(recorded("trial-turn-1.jsonl"))));

				SessionResult result = await(sessions.start(request(root, SessionRole.TRIAL, "answer")));

				requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
				SessionResult.Completed completed = (SessionResult.Completed) result;
				requireThat(completed.model(), "model").isEqualTo("gpt-6-luna");
				requireThat(completed.reasoning(), "reasoning").isEqualTo("low");
				requireThat(completed.usage(), "usage").isEqualTo(new TokenUsage(13_977, 10_112, 46, 0));
				requireThat(completed.windows(), "windows").isEqualTo(WINDOWS);
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E7
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-contract-"));
				Files.writeString(root.resolve("auth.json"), "{\"tokens\":{}}", UTF_8);
				CodexSessions sessions = port(jvm, root, (_, _, _) ->
				{
					throw new AssertionError("Login classification must not start a session");
				});

				requireThat(sessions.hostLogin(), "login").isEqualTo(HostLogin.SUBSCRIPTION);
			}
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E7
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-contract-"));
				CodexSessions sessions = port(jvm, root, (_, _, _) ->
				{
					throw new AssertionError("Login classification must not start a session");
				});
				Files.writeString(root.resolve("auth.json"), "{\"OPENAI_API_KEY\":\"placeholder\"}", UTF_8);

				requireThat(sessions.hostLogin(), "apiKeyLogin").isEqualTo(HostLogin.API_KEY);
				Files.writeString(root.resolve("auth.json"), "{\"OPENAI_API_KEY\":{}}", UTF_8);
				requireThat(sessions.hostLogin(), "unsupportedKeyType").isEqualTo(HostLogin.UNKNOWN);
			}
		}
	}

	/**
	 * Verifies that each role starts an independent session, with its own transcript and effects directory.
	 * Every role needs a separate replay and persisted transcript to prove isolation across all roles; creating and
	 * validating those session resources can exceed one second in the parallel build. No process starts.
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E20
			// Requirement: R-E13
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-contract-"));
				RoleRuleFixtures.write(root);
				Map<SessionRole, List<String>> events = Map.of(SessionRole.TRIAL, recorded("trial-turn-1.jsonl"),
					SessionRole.JUDGE, recorded("judge-turn-1.jsonl"), SessionRole.TRIAGE, recorded("triage-turn-1.jsonl"),
					SessionRole.APPROACH_RANKER, recorded("approach-ranker-turn-1.jsonl"),
					SessionRole.PROMPT_AUTHOR, recorded("author-turn-1.jsonl"), SessionRole.CONTRACT_AUTHOR,
					recorded("deriver-turn-1.jsonl"));
				Map<SessionRole, Integer> messages = Map.of(SessionRole.TRIAL, 1, SessionRole.JUDGE, 1,
					SessionRole.TRIAGE, 1, SessionRole.APPROACH_RANKER, 1, SessionRole.PROMPT_AUTHOR, 3,
					SessionRole.CONTRACT_AUTHOR, 2);
				List<RecordedAppServer> servers = Arrays.stream(SessionRole.values()).
					map(role -> new RecordedAppServer(List.of(events.get(role)))).toList();
				AtomicInteger next = new AtomicInteger();
				CodexSessions sessions = port(jvm, root, (arguments, input, output) ->
					servers.get(next.getAndIncrement()).serve(arguments, input, output));
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
						servers.get(role.ordinal()).turnOutput());
					transcripts.add(completed.transcript());
					effects.add(completed.effects());
				}
				requireThat(transcripts.size(), "transcripts.size").isEqualTo(SessionRole.values().length);
				requireThat(effects.size(), "effects.size").isEqualTo(SessionRole.values().length);
			}
		}
	}

	/**
	 * Verifies that the adapter returns both replies in turn order and archives the complete replay in one
	 * transcript for each optimizer role.
	 *
	 * @param role          the role that ran the recorded session
	 * @param recording     the filename prefix for the role's two turns
	 * @throws IOException if a fixture cannot be written or read
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException if classification fails
	 * @throws TimeoutException if the replay does not finish
	 */
	@Test(dataProvider = "twoTurnRoleRecordings")
	public void replaysBothTurnsOfEachRole(SessionRole role, String recording)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		// Requirement: R-E16
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-contract-"));
			RoleRuleFixtures.write(root);
			RecordedAppServer server = new RecordedAppServer(
				List.of(recorded(recording + "-turn-1.jsonl"), recorded(recording + "-turn-2.jsonl")));
			CodexSessions sessions = port(jvm, root, server);

			SessionResult result = await(sessions.start(request(root, role,
				"Please note case number 308 for my next question.",
				"What case number did I give you? Reply with just the number.")));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.responses(), "responses").isEqualTo(List.of(
				List.of("Noted: case number 308."), List.of("308")));
			requireThat(Files.readAllLines(completed.transcript(), UTF_8), "transcript").isEqualTo(server.turnOutput());
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E14
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "codex-contract-"));
				List<String> events = recorded("child-work.jsonl");
				CodexSessions sessions = port(jvm, root, (options, input, output) -> ScriptedHarness.serve(
					Path.of(ScriptedHarness.optionValue(options, "--results")), "codex",
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
	}

	/**
	 * Supplies the production port and an in-memory app server to the shared contract checks.
	 *
	 * @param cleanup deletes the subject's fixtures
	 * @param port the production adapter
	 * @param workspace the test-owned source directory
	 * @param server the recorded external boundary
	 */
	private record RecordedSubject(TemporaryDirectories cleanup, CodexSessions port, Path workspace,
		RecordedAppServer server) implements Subject
	{
		/**
		 * Creates a subject for the shared contract checks.
		 *
		 * @param cleanup deletes the subject's fixtures
		 * @param port the production adapter
		 * @param workspace the test-owned source directory
		 * @param server the recorded external boundary
		 * @throws NullPointerException if any argument is null
		 */
		private RecordedSubject
		{
			requireThat(cleanup, "cleanup").isNotNull();
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
		public boolean reportsExitStatus()
		{
			return true;
		}

		@Override
		public Path pluginWithoutHooks() throws IOException
		{
			Path plugin = Files.createDirectories(workspace.resolve("plugin/.codex-plugin"));
			Files.writeString(plugin.resolve("plugin.json"), "{\"name\":\"sample\"}", UTF_8);
			return plugin.getParent();
		}

		@Override
		public List<RateLimitWindow> windowsReportedBeforeInterrupt()
		{
			return List.of(new RateLimitWindow(0.49, Instant.ofEpochSecond(1_791_046_710L)));
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
