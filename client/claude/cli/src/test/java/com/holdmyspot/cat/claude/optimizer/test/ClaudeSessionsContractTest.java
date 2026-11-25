package com.holdmyspot.cat.claude.optimizer.test;

import com.holdmyspot.cat.claude.runner.test.SourceHomes;
import com.holdmyspot.cat.common.JvmScope;

import com.holdmyspot.cat.common.Platform;
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
import com.holdmyspot.cat.common.optimizer.execution.contract.test.DriverInterruptReplayContract;
import com.holdmyspot.cat.common.optimizer.adapter.fakes.test.RoleRuleFixtures;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspace;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;
import com.holdmyspot.cat.common.optimizer.execution.Judgment;
import com.holdmyspot.cat.common.optimizer.execution.prompt.JudgePrompt;
import com.holdmyspot.cat.common.optimizer.port.contract.test.SessionPortContract.Subject;
import com.holdmyspot.cat.common.optimizer.port.contract.test.SessionPortContract;
import com.holdmyspot.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.FilterReader;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
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
		// The host's managed settings are not read, so that the host cannot change what the contract finds
		return ClaudeSessionsSecrets.getClaudeSessionsAccess().
			newClaudeSessions(new RunnerSessions.Settings(jvm, root, root.resolve("results"),
			List::of, false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer()),
			bridge, List.of());
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
			new TrialProfile("haiku", "low"));
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
			contract(jvm, List.of(recorded("r-i1-trial-turn-1.jsonl"), recorded("r-i1-trial-turn-2.jsonl"))).
				completesWithEvidenceOfEveryTurn();
		}
	}

	/**
	 * Runs the shared check that a scripted judge session completes and leaves
	 * the local evidence fixture bytes unchanged.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(timeOut = 60_000)
	public void judgeKeepsLocalEvidence() throws Exception
	{
		// Requirement: R-E13
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contract(jvm, List.of(recorded("judge-turn-1.jsonl"))).judgeKeepsLocalEvidence();
		}
	}

	/**
	 * Replays the native E5 file trial and judge, supplies the captured file as read-only evidence, and applies the
	 * production judge parser to the recorded assertion set.
	 *
	 * @throws IOException          if a recording or test-owned file cannot be read or written
	 * @throws InterruptedException if the test thread is interrupted
	 * @throws ExecutionException   if Claude's recorded turn cannot be classified
	 * @throws TimeoutException     if the recorded turn does not complete
	 */
	@Test(timeOut = 60_000)
	public void replaysE5FileTrialAndJudge()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "claude-e5-file-replay-"));
				AtomicReference<List<String>> trialArguments = new AtomicReference<>();
				AtomicReference<String> trialInput = new AtomicReference<>();
				SessionResult.Completed trial = replay(jvm, root, SessionRole.TRIAL, "e5-file-trial-turn-1.jsonl",
					List.of(), "Replay the recorded E5 file trial.", trialArguments, trialInput);
				byte[] answer = e5Answer();
				String trialReply = trialText(trial);
				requireThat(trialReply, "trialReply").isNotBlank();
				List<String> writes = writesToAnswer(trial.transcript());
				requireThat(writes.size(), "answerWrites").isEqualTo(1);
				requireThat(writes.getFirst(), "recordedAnswer").isEqualTo(new String(answer, UTF_8));

				Path answerFile = trial.effects().resolve("answer.md");
				Files.write(answerFile, answer);
				byte[] evidenceBeforeJudge = Files.readAllBytes(answerFile);
				AtomicReference<List<String>> judgeArguments = new AtomicReference<>();
				AtomicReference<String> judgeInput = new AtomicReference<>();
				SessionResult.Completed judge = replay(jvm, root, SessionRole.JUDGE, "e5-file-judge-turn-1.jsonl",
					List.of(answerFile), trialReply, judgeArguments, judgeInput);

				requireThat(judgeArguments.get(), "judgeArguments").contains("--read-only-evidence").contains(
					answerFile.toString());
				requireThat(inputContains(judgeInput.get(), trialReply), "judgeReceivesTrialReply").isTrue();
				requireThat(Files.readAllBytes(answerFile), "answerAfterJudge").isEqualTo(evidenceBeforeJudge);
				Judgment decision = judgePrompt(root).judgment(judge, fileJudgeAssertions()).orElseThrow();
				requireThat(decision.failures().size(), "fileJudgeFailureCount").isEqualTo(2);
				requireThat(decision.rationale(), "fileJudgeRationale").
					contains("- `acknowledgement_one_short_sentence`: fail (acknowledgement_too_long).").
					contains("- `chat_reply_not_the_answer`: fail (answer_given_in_chat_instead_of_file).").
					contains("- `file_explains_question`: pass.").
					contains("- `expected_outcome`: pass.");
			}
		}
	}

	/**
	 * Replays the native E5 text trial and judge, then applies the production judge parser to the recorded assertion set.
	 *
	 * @throws IOException          if a recording cannot be read
	 * @throws InterruptedException if the test thread is interrupted
	 * @throws ExecutionException   if Claude's recorded turn cannot be classified
	 * @throws TimeoutException     if the recorded turn does not complete
	 */
	@Test(timeOut = 60_000)
	public void replaysE5TextTrialAndJudge()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "claude-e5-text-replay-"));
				AtomicReference<List<String>> trialArguments = new AtomicReference<>();
				AtomicReference<String> trialInput = new AtomicReference<>();
				SessionResult.Completed trial = replay(jvm, root, SessionRole.TRIAL, "e5-text-trial-turn-1.jsonl",
					List.of(), "Replay the recorded E5 text trial.", trialArguments, trialInput);
				String trialReply = trialText(trial);
				requireThat(trialReply, "trialReply").isNotBlank();
				AtomicReference<List<String>> judgeArguments = new AtomicReference<>();
				AtomicReference<String> judgeInput = new AtomicReference<>();
				SessionResult.Completed judge = replay(jvm, root, SessionRole.JUDGE, "e5-text-judge-turn-1.jsonl",
					List.of(), trialReply, judgeArguments, judgeInput);

				requireThat(inputContains(judgeInput.get(), trialReply), "judgeReceivesTrialReply").isTrue();
				Judgment decision = judgePrompt(root).judgment(judge, textJudgeAssertions()).orElseThrow();
				requireThat(decision.failures().size(), "textJudgeFailureCount").isEqualTo(1);
				requireThat(decision.rationale(), "textJudgeRationale").
					contains("- `one_sentence`: fail (more_than_one_sentence).").
					contains("- `answers_accurately`: pass.").
					contains("- `expected_outcome`: pass.");
			}
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
				RecordedClaude claude = new RecordedClaude(List.of(recorded("interrupted-session-turn-1.jsonl")));

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
			// Requirement: R-E15
			Path root = Files.createTempDirectory(jvm.temporaryDirectory(), "claude-driver-interrupt-replay-");
			RecordedClaude claude = new RecordedClaude(List.of(recorded("interrupted-judge-turn-1.jsonl")));
			try
			{
				DriverInterruptReplayContract.replaysInterruptedAttempt(port(jvm, root, claude), claude::awaitTurnStarted);
			}
			finally
			{
				claude.close();
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
				Path home = SourceHomes.create(root, "home");
				SourceHomes.catCredential(home, SourceHomes.SETUP_TOKEN);
				ClaudeSessions port = ClaudeSessionsSecrets.getClaudeSessionsAccess().newClaudeSessions(
					new RunnerSessions.Settings(jvm, root, root.resolve("results"), List::of, false,
						Map.of(Platform.current().homeVariable(), home.toString()), root, HarnessCapacity.unlimited(),
						Clock.systemUTC(), Yielding.fileReplacer()), (_, _, _) ->
					{
						throw new AssertionError("Login classification must not start a session");
					}, List.of());
				return new RecordedSubject(cleanup, port, root, claude);
			}).reportsTheHostLoginWithoutASession();
		}
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
			Path root = Files.createTempDirectory(jvm.temporaryDirectory(), "claude-contract-");
			TemporaryDirectories cleanup = new TemporaryDirectories();
			cleanup.add(root);
			RecordedClaude claude = new RecordedClaude(List.of());
			return new RecordedSubject(cleanup, port(jvm, root, (_, _, _) ->
			{
				throw new AssertionError("The check must not start a session");
			}), root, claude);
		});
	}

	/**
	 * Verifies the shared contract check that the port reports that Claude Code does not report exit statuses.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(timeOut = 60_000)
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
	@Test(timeOut = 60_000)
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
	@Test(timeOut = 60_000)
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
	@Test(timeOut = 60_000)
	public void readsTheEvidenceOfACompletedTranscript() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E13
			contract(jvm, List.of(recorded("trial-turn-1.jsonl"))).readsTheEvidenceOfACompletedTranscript();
		}
	}

	/**
	 * Verifies the shared contract check that an interrupted session carries the windows that Claude reported.
	 *
	 * @throws Exception if the contract check fails
	 */
	@Test(timeOut = 60_000)
	public void interruptKeepsTheWindowsReportedBeforeIt() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-E15
			contract(jvm, List.of(recorded("interrupted-judge-turn-1.jsonl"))).
				interruptKeepsTheWindowsReportedBeforeIt();
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
				requireThat(Files.readAllLines(completed.transcript(), UTF_8), "transcript").isEqualTo(
					RecordedClaude.asServed(all, completed.transcript()));
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
	 * Returns the optimizer roles with a recorded two-turn case-number session.
	 *
	 * @return each session role, its recording filename prefix and the reply that the recording gives to the first
	 *         turn
	 */
	@DataProvider
	public static Object[][] twoTurnRoleRecordings()
	{
		return new Object[][]
		{
			{SessionRole.JUDGE, "two-turn-judge", "Noted: case number 308. I'm ready for your next question."},
			{SessionRole.TRIAGE, "two-turn-triage",
				"Noted: case number 308. I'll keep it in mind for your next question. What would you like to ask?"},
			{SessionRole.APPROACH_RANKER, "two-turn-approach-ranker", "Noted: case number 308. What's your question?"},
			{SessionRole.PROMPT_AUTHOR, "two-turn-prompt-author", "Noted: case number 308. Ready for your next question."}
		};
	}

	/**
	 * Verifies that the adapter returns both replies in turn order and archives the complete replay in one
	 * transcript for each optimizer role.
	 *
	 * @param role       the role that ran the recorded session
	 * @param recording  the filename prefix for the role's two turns
	 * @param firstReply the reply that the recording gives to the first turn
	 * @throws IOException          if a fixture cannot be created or read
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException   if classification fails
	 * @throws TimeoutException     if the replay does not finish
	 */
	@Test(dataProvider = "twoTurnRoleRecordings", timeOut = 60_000)
	public void replaysBothTurnsOfEachRole(SessionRole role, String recording, String firstReply)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		// Requirement: R-E16
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "claude-contract-"));
			RoleRuleFixtures.write(root);
			List<String> first = recorded(recording + "-turn-1.jsonl");
			List<String> second = recorded(recording + "-turn-2.jsonl");
			ClaudeSessions sessions = port(jvm, root, new RecordedClaude(List.of(first, second)));

			SessionResult result = await(sessions.start(request(root, role,
				"Please note case number 308 for my next question.",
				"What case number did I give you? Reply with just the number.")));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.responses(), "responses").isEqualTo(List.of(List.of(firstReply), List.of("308")));
			List<String> all = new ArrayList<>(first);
			all.addAll(second);
			requireThat(Files.readAllLines(completed.transcript(), UTF_8), "transcript").isEqualTo(
				RecordedClaude.asServed(all, completed.transcript()));
		}
	}

	/**
	 * Verifies that a contract author's correction follows the initial reply in the same session transcript.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException if classification fails
	 * @throws TimeoutException if the replay does not finish
	 */
	@Test(timeOut = 60_000)
	public void recordsContractAuthorCorrection()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "claude-contract-"));
			RoleRuleFixtures.write(root);
			List<String> first = recorded("contract-author-correction-turn-1.jsonl");
			List<String> second = recorded("contract-author-correction-turn-2.jsonl");
			ClaudeSessions sessions = port(jvm, root, new RecordedClaude(List.of(first, second)));
			SessionRole role = SessionRole.CONTRACT_AUTHOR;
			SessionRequest proposal = new SessionRequest(role, partOf(role), root, List.of("Revise the proposal."),
				List.of(), LIMIT, new TrialProfile("haiku", "low"), TestWorkspace.NONE, List.of(), Optional.of(initial ->
				{
					requireThat(initial.responses().size(), "initialTurns").isEqualTo(1);
					requireThat(initial.responses().getFirst().getLast(), "initialReply").
						contains("I revised the proposal using resolution 1");
					return Optional.of("Correct the two refused links.");
				}));

			SessionResult result = await(sessions.start(proposal));

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.responses().size(), "completedTurns").isEqualTo(2);
			requireThat(completed.responses().getLast().getLast(), "correctionReply").contains("I fixed both refused links");
			List<String> all = new ArrayList<>(first);
			all.addAll(second);
			requireThat(Files.readAllLines(completed.transcript(), UTF_8), "transcript").isEqualTo(
				RecordedClaude.asServed(all, completed.transcript()));
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
					SessionRole.APPROACH_RANKER, recorded("approach-ranker-turn-1.jsonl"),
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
						isEqualTo(RecordedClaude.asServed(events.get(role), completed.transcript()));
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
	 * Starts a captured turn through ClaudeSessions and records the arguments it passes to the harness bridge.
	 *
	 * @param jvm the test-owned process scope
	 * @param root the test-owned source and results directory
	 * @param role the recorded session's role
	 * @param recording the event resource to replay
	 * @param readOnly the evidence files supplied to the session
	 * @param turn the prompt supplied for this turn
	 * @param observedArguments receives the arguments passed to the bridge
	 * @param observedInput receives the protocol request passed to the bridge
	 * @return the completed session
	 * @throws IOException if the event resource cannot be read
	 * @throws InterruptedException if the test thread is interrupted
	 * @throws ExecutionException if the adapter cannot classify the recorded turn
	 * @throws TimeoutException if the recorded turn does not complete
	 */
	private static SessionResult.Completed replay(JvmScope jvm, Path root, SessionRole role, String recording,
		List<Path> readOnly, String turn, AtomicReference<List<String>> observedArguments,
		AtomicReference<String> observedInput)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		TrialProfile profile;
		if (role == SessionRole.JUDGE)
			profile = new TrialProfile("opus", "high");
		else
			profile = new TrialProfile("haiku", "low");
		Bridge bridge = (arguments, input, output) ->
		{
			observedArguments.set(List.copyOf(arguments));
			StringWriter request = new StringWriter();
			Reader recordingInput = new FilterReader(input)
			{
				@Override
				public int read(char[] characters, int offset, int length) throws IOException
				{
					int count = super.read(characters, offset, length);
					if (count > 0)
						request.write(characters, offset, count);
					return count;
				}

				@Override
				public int read() throws IOException
				{
					int character = super.read();
					if (character >= 0)
						request.write(character);
					return character;
				}
			};
			new RecordedClaude(List.of(recorded(recording))).serve(arguments, recordingInput, output);
			observedInput.set(request.toString());
		};
		SessionRequest request = new SessionRequest(role, partOf(role), root, List.of(turn),
			readOnly, LIMIT, profile);
		SessionResult result = await(port(jvm, root, bridge).start(request));
		requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
		return (SessionResult.Completed) result;
	}

	/**
	 * Returns all text replies from a completed trial in turn order.
	 *
	 * @param trial the completed recorded trial
	 * @return its assistant messages in turn order
	 */
	private static String trialText(SessionResult.Completed trial)
	{
		return trial.responses().stream().flatMap(List::stream).collect(java.util.stream.Collectors.joining("\n"));
	}

	/**
	 * Checks whether the bridge's captured JSONL request contains the trial reply in one of its text fields.
	 *
	 * @param request the protocol request read by the harness bridge
	 * @param text the assistant reply sent in the judge turn
	 * @return {@code true} when a request field contains the full reply
	 * @throws IOException if the bridge request is malformed
	 */
	private static boolean inputContains(String request, String text) throws IOException
	{
		JsonMapper json = JsonMapper.builder().build();
		try (BufferedReader reader = new BufferedReader(new StringReader(request)))
		{
			for (String line = reader.readLine(); line != null; line = reader.readLine())
			{
				if (containsText(json.readTree(line), text))
					return true;
			}
		}
		return false;
	}

	/**
	 * Finds the trial reply in a JSON value supplied to the bridge.
	 *
	 * @param value a protocol value
	 * @param text the complete trial reply
	 * @return {@code true} when a string value contains the reply
	 */
	private static boolean containsText(JsonNode value, String text)
	{
		if (value.isString())
			return value.asString("").contains(text);
		if (value.isArray())
		{
			for (JsonNode child : value)
			{
				if (containsText(child, text))
					return true;
			}
		}
		if (value.isObject())
		{
			for (String field : value.propertyNames())
			{
				if (containsText(value.path(field), text))
					return true;
			}
		}
		return false;
	}

	/**
	 * Returns the E5 answer file stored with the Claude session recordings.
	 *
	 * @return the exact captured file bytes
	 * @throws IOException if the resource cannot be read
	 */
	private static byte[] e5Answer() throws IOException
	{
		try (InputStream stream = ClaudeSessionsContractTest.class.getResourceAsStream(
			"/optimizer/sessions/e5-answer.md"))
		{
			requireThat(stream, "answerResource").isNotNull();
			return stream.readAllBytes();
		}
	}

	/**
	 * Finds the contents passed to Claude's recorded Write tool for {@code answer.md}.
	 *
	 * @param transcript the adapter-owned session transcript
	 * @return the captured contents of each write to {@code answer.md}
	 * @throws IOException if the transcript cannot be read
	 */
	private static List<String> writesToAnswer(Path transcript) throws IOException
	{
		JsonMapper json = JsonMapper.builder().build();
		List<String> writes = new ArrayList<>();
		try (BufferedReader reader = Files.newBufferedReader(transcript, UTF_8))
		{
			for (String line = reader.readLine(); line != null; line = reader.readLine())
			{
				JsonNode event = json.readTree(line);
				if (!event.path("type").asString("").equals("assistant"))
					continue;
				for (JsonNode block : event.path("message").path("content"))
				{
					if (!block.path("type").asString("").equals("tool_use") ||
						!block.path("name").asString("").equals("Write"))
						continue;
					JsonNode input = block.path("input");
					Path writtenFile = Path.of(input.path("file_path").asString(""));
					if (writtenFile.getFileName().toString().equals("answer.md"))
						writes.add(input.path("content").asString(""));
				}
			}
		}
		return writes;
	}

	/**
	 * Creates the production judge parser for the replayed session.
	 *
	 * @param root the test-owned directory for judge state
	 * @return the production parser
	 */
	private static JudgePrompt judgePrompt(Path root)
	{
		return new JudgePrompt(JsonMapper.builder().build(), root.resolve("judge"));
	}

	/**
	 * Returns the E5 file trial's exact recorded assertion IDs and accepted failure behaviors.
	 *
	 * @return the assertions the recorded judge decided
	 */
	private static List<JudgedAssertion> fileJudgeAssertions()
	{
		return List.of(
			judged("file_explains_question", AssertionKind.EFFECT, "inaccurate_file_explanation"),
			judged("acknowledgement_one_short_sentence", AssertionKind.GUARD, "acknowledgement_too_long"),
			judged("chat_reply_not_the_answer", AssertionKind.GUARD, "answer_given_in_chat_instead_of_file"),
			judged(TestCase.EXPECTED_OUTCOME, AssertionKind.EFFECT, "unexpected_outcome"));
	}

	/**
	 * Returns the E5 text trial's exact recorded assertion IDs and accepted failure behaviors.
	 *
	 * @return the assertions the recorded judge decided
	 */
	private static List<JudgedAssertion> textJudgeAssertions()
	{
		return List.of(
			judged("answers_accurately", AssertionKind.GUARD, "inaccurate_answer"),
			judged("one_sentence", AssertionKind.EFFECT, "more_than_one_sentence"),
			judged(TestCase.EXPECTED_OUTCOME, AssertionKind.EFFECT, "unexpected_outcome"));
	}

	/**
	 * Creates a recorded assertion with one behavior accepted by its requirement.
	 *
	 * @param id the assertion identifier
	 * @param kind whether the prompt should cause or guard the behavior
	 * @param behavior the behavior that the assertion can fail with
	 * @return the judged assertion
	 */
	private static JudgedAssertion judged(String id, AssertionKind kind, String behavior)
	{
		Assertion assertion = new Assertion(id, kind, null, OnToolFailure.FAIL, "The recorded E5 assertion is satisfied.");
		Requirement requirement = new Requirement(id, assertion.statement(), Requirement.GOAL, null, List.of(behavior));
		return new JudgedAssertion(assertion, requirement);
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
			// The only check that asks for the login gives the port a source home whose only credential is a setup token in
			// CAT's credential file
			return Set.of(HostLogin.SUBSCRIPTION);
		}

		@Override
		public boolean reportsExitStatus()
		{
			return false;
		}

		@Override
		public Path pluginWithoutHooks() throws IOException
		{
			// Claude Code runs every hook of an installed plugin, so the adapter reads no manifest
			return Files.createDirectories(workspace.resolve("plugin"));
		}

		@Override
		public List<RateLimitWindow> windowsReportedBeforeInterrupt()
		{
			return List.of(new RateLimitWindow(0.37, Instant.ofEpochSecond(1_792_455_600L)),
				new RateLimitWindow(0.25, Instant.ofEpochSecond(1_792_879_200L)));
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
