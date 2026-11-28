package com.holdmyspot.cat.common.optimizer.adapter.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.RiskRank;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.UndecidableToolException;
import com.holdmyspot.cat.common.optimizer.contract.VariableDeclaration;
import com.holdmyspot.cat.common.optimizer.execution.EarlyTrialEnd;
import com.holdmyspot.cat.common.optimizer.execution.TrialEvidence;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.contract.test.SessionPortContract;
import com.holdmyspot.cat.common.optimizer.port.contract.test.SessionPortContract.Subject;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.Test;

/**
 * Runs the session port contract on {@link TestSessions}, the generic harness's subclass of {@link RunnerSessions},
 * over a scripted harness behind the real runner bridge, so that the tests that use it rely only on behaviour that
 * the contract checks.
 */
public final class TestSessionsContractTest
{
	/**
	 * The reply of every quick turn.
	 */
	private static final String REPLY = "ok";

	/**
	 * Creates a test.
	 */
	public TestSessionsContractTest()
	{
	}

	/**
	 * Returns a turn that replies at once and reports the model, its usage and its output.
	 *
	 * @return the turn
	 */
	private static Turn quickTurn()
	{
		return Turn.replies(REPLY, TestSessions.MODEL_EVENT, TestSessions.USAGE_EVENT, TestSessions.OUTPUT_EVENT);
	}

	/**
	 * Returns the contract over ports whose harness follows a script.
	 *
	 * @param jvm    the JVM scope
	 * @param script what the harness does for each turn, in turn order
	 * @return the contract
	 */
	private static SessionPortContract contract(JvmScope jvm, List<Turn> script)
	{
		return contract(jvm, script, new CountDownLatch(0));
	}

	/**
	 * Returns the contract over ports whose harness follows a script.
	 *
	 * @param jvm     the JVM scope
	 * @param script  what the harness does for each turn, in turn order
	 * @param entered counted down once a long turn is in progress
	 * @return the contract
	 */
	private static SessionPortContract contract(JvmScope jvm, List<Turn> script, CountDownLatch entered)
	{
		return new SessionPortContract(() -> ScriptedSubject.open(jvm, (options, input, output) ->
			ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--results")), TestSessions.HARNESS,
				script, input, output), entered));
	}

	/**
	 * Returns the contract over ports whose checks must not start a session.
	 *
	 * @param jvm the JVM scope
	 * @return the contract
	 */
	private static SessionPortContract contractWithoutSessions(JvmScope jvm)
	{
		return new SessionPortContract(() -> ScriptedSubject.open(jvm, (_, _, _) ->
		{
			throw new AssertionError("The check must not start a session");
		}, new CountDownLatch(0)));
	}

	/**
	 * Returns the contract over ports whose harness blocks the turn until it is interrupted.
	 *
	 * @param jvm the JVM scope
	 * @return the contract
	 */
	private static SessionPortContract interruptedContract(JvmScope jvm)
	{
		CountDownLatch entered = new CountDownLatch(1);
		return contract(jvm, List.of(Turn.blocksUntilInterrupted(TestSessions.WINDOW_EVENT).announcing(entered)),
			entered);
	}

	/**
	 * Runs the contract check that every turn's evidence is reported.
	 *
	 * @throws IOException          if the trial evidence cannot be prepared or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	@Test(timeOut = 60_000)
	public void completesWithEvidenceOfEveryTurn()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contract(jvm, List.of(quickTurn(), quickTurn())).completesWithEvidenceOfEveryTurn();
		}
	}

	/**
	 * Ends a tool-decided trial normally after its first complete turn and never requests its second turn.
	 *
	 * @throws IOException          if the trial evidence cannot be prepared or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	// Requirement: R-E25
	@Test(timeOut = 60_000)
	public void settledTrialSkipsRemainingTurns()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		List<String> requests = new ArrayList<>();
		try (TestJvmScope jvm = new TestJvmScope();
			ScriptedSubject subject = ScriptedSubject.open(jvm, (options, input, output) ->
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--results")), TestSessions.HARNESS,
					List.of(quickTurn(), quickTurn()), input, output, requests), new CountDownLatch(0)))
		{
			SessionResult.Completed completed = completeObservedTrial(subject, trialTest("exact-text turn=1 ok"));

			requireThat(requests.size(), "requestedTurnsSentToHarness").isEqualTo(1);
			requireThat(completed.responses(), "responses").isEqualTo(List.of(List.of(REPLY)));
			requireThat(completed.requestedTurns(), "requestedTurns").isEqualTo(2);
			requireThat(completed.endedEarly(), "endedEarly").isTrue();
			requireThat(completed.turnsNotRun(), "turnsNotRun").isEqualTo(List.of(2));
			requireThat(completed.settledAssertions(), "settledAssertions").
				isEqualTo(Map.of("reply", 1));
		}
	}

	/**
	 * Runs both turns when a passing unscoped reply assertion could still fail on a later turn.
	 *
	 * @throws IOException          if the trial evidence cannot be prepared or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	// Requirement: R-E25
	@Test(timeOut = 60_000)
	public void unsettledTrialRunsRemainingTurns()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		List<String> requests = new ArrayList<>();
		try (TestJvmScope jvm = new TestJvmScope();
			ScriptedSubject subject = ScriptedSubject.open(jvm, (options, input, output) ->
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--results")), TestSessions.HARNESS,
					List.of(quickTurn(), quickTurn()), input, output, requests), new CountDownLatch(0)))
		{
			SessionResult.Completed completed = completeObservedTrial(subject, trialTest("exact-text ok"));

			requireThat(requests.size(), "requestedTurnsSentToHarness").isEqualTo(2);
			requireThat(requests.get(1), "secondTurn").isEqualTo("Reply again.");
			requireThat(completed.responses(), "responses").isEqualTo(List.of(List.of(REPLY), List.of(REPLY)));
			requireThat(completed.requestedTurns(), "requestedTurns").isEqualTo(2);
			requireThat(completed.endedEarly(), "endedEarly").isFalse();
			requireThat(completed.turnsNotRun(), "turnsNotRun").isEmpty();
			requireThat(completed.settledAssertions(), "settledAssertions").isEmpty();
		}
	}

	/**
	 * Stops after a cumulative count exceeds its maximum, which later turns cannot undo.
	 *
	 * @throws IOException              if the trial evidence cannot be prepared or read
	 * @throws InterruptedException     if waiting for completion is interrupted
	 * @throws ExecutionException       if the session fails
	 * @throws TimeoutException         if the session does not complete
	 * @throws UndecidableToolException if the recorded evidence cannot decide the count
	 */
	// Requirement: R-E25
	@Test(timeOut = 60_000)
	public void aboveMaximumCountSkipsRemainingTurns()
		throws IOException, InterruptedException, ExecutionException, TimeoutException, UndecidableToolException
	{
		List<String> requests = new ArrayList<>();
		try (TestJvmScope jvm = new TestJvmScope();
			ScriptedSubject subject = ScriptedSubject.open(jvm, (options, input, output) ->
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--results")), TestSessions.HARNESS,
					List.of(quickTurn(), quickTurn()), input, output, requests), new CountDownLatch(0)))
		{
			PromptTest test = trialTest("count ok 0 0");
			SessionResult.Completed completed = completeObservedTrial(subject, test);

			requireThat(requests.size(), "requestedTurnsSentToHarness").isEqualTo(1);
			requireThat(completed.responses(), "responses").isEqualTo(List.of(List.of(REPLY)));
			requireThat(completed.requestedTurns(), "requestedTurns").isEqualTo(2);
			requireThat(completed.endedEarly(), "endedEarly").isTrue();
			requireThat(completed.turnsNotRun(), "turnsNotRun").isEqualTo(List.of(2));
			requireThat(completed.settledAssertions(), "settledAssertions").
				isEqualTo(Map.of("reply", 1));
			requireThat(test.decide(test.testCases().getFirst(), TrialEvidence.read(subject.port(), completed)).
				stream().map(BuiltInToolResult::passed).toList(), "finalToolVerdicts").isEqualTo(List.of(false));
		}
	}

	/**
	 * Runs the second turn when its added match can bring a cumulative count up to its minimum.
	 *
	 * @throws IOException              if the trial evidence cannot be prepared or read
	 * @throws InterruptedException     if waiting for completion is interrupted
	 * @throws ExecutionException       if the session fails
	 * @throws TimeoutException         if the session does not complete
	 * @throws UndecidableToolException if the recorded evidence cannot decide the count
	 */
	// Requirement: R-E25
	@Test(timeOut = 60_000)
	public void belowMinimumCountRunsRemainingTurns()
		throws IOException, InterruptedException, ExecutionException, TimeoutException, UndecidableToolException
	{
		List<String> requests = new ArrayList<>();
		try (TestJvmScope jvm = new TestJvmScope();
			ScriptedSubject subject = ScriptedSubject.open(jvm, (options, input, output) ->
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--results")), TestSessions.HARNESS,
					List.of(quickTurn(), quickTurn()), input, output, requests), new CountDownLatch(0)))
		{
			PromptTest test = trialTest("count {{expected_outcome}} 2 2");
			SessionResult.Completed completed = completeObservedTrial(subject, test);

			requireThat(requests.size(), "requestedTurnsSentToHarness").isEqualTo(2);
			requireThat(requests.get(1), "secondTurn").isEqualTo("Reply again.");
			requireThat(completed.responses(), "responses").isEqualTo(List.of(List.of(REPLY), List.of(REPLY)));
			requireThat(completed.requestedTurns(), "requestedTurns").isEqualTo(2);
			requireThat(completed.endedEarly(), "endedEarly").isFalse();
			requireThat(completed.turnsNotRun(), "turnsNotRun").isEmpty();
			requireThat(completed.settledAssertions(), "settledAssertions").isEmpty();
			requireThat(test.decide(test.testCases().getFirst(), TrialEvidence.read(subject.port(), completed)).
				stream().map(BuiltInToolResult::passed).toList(), "finalToolVerdicts").isEqualTo(List.of(true));
		}
	}

	/**
	 * A settled reply check cannot skip a later turn while a declared assertion needs whole-trial judgment.
	 *
	 * @throws IOException if the trial evidence cannot be prepared or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException if the session fails
	 * @throws TimeoutException if the session does not complete
	 */
	// Requirement: R-E25
	@Test(timeOut = 60_000)
	public void judgedAssertionRunsRemainingTurns()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		List<String> requests = new ArrayList<>();
		try (TestJvmScope jvm = new TestJvmScope();
			ScriptedSubject subject = ScriptedSubject.open(jvm, (options, input, output) ->
				ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--results")), TestSessions.HARNESS,
					List.of(quickTurn(), quickTurn()), input, output, requests), new CountDownLatch(0)))
		{
			Assertion followUp = new Assertion("follow_up", AssertionKind.EFFECT, null, OnToolFailure.FAIL,
				"The follow-up reply uses context from the first user turn.",
				"A tool cannot decide whether the follow-up uses that context.", EvidenceScope.RESPONSE);
			PromptTest test = trialTest("exact-text turn=1 ok", List.of(new MappedAssertion(followUp, "reply_required")));
			SessionResult.Completed completed = completeObservedTrial(subject, test);

			requireThat(requests.size(), "requestedTurnsSentToHarness").isEqualTo(2);
			requireThat(completed.responses(), "responses").isEqualTo(List.of(List.of(REPLY), List.of(REPLY)));
			requireThat(completed.endedEarly(), "endedEarly").isFalse();
			requireThat(completed.turnsNotRun(), "turnsNotRun").isEmpty();
		}
	}

	/**
	 * Creates a two-turn test with one declared reply check.
	 *
	 * @param tool the built-in reply check
	 * @return the test
	 */
	private static PromptTest trialTest(String tool)
	{
		return trialTest(tool, List.of());
	}

	/**
	 * Creates a two-turn test with a declared reply check and any additional assertions.
	 *
	 * @param tool                 the reply check's built-in tool
	 * @param additionalAssertions other assertions declared by the test
	 * @return the test
	 */
	private static PromptTest trialTest(String tool, List<MappedAssertion> additionalAssertions)
	{
		TestCase testCase = new TestCase(Map.of("request", new TestValue.Text("Reply with ok.")), REPLY);
		MappedAssertion reply = new MappedAssertion(new Assertion("reply", AssertionKind.EFFECT, tool,
			"The reply is ok."), "reply_required");
		List<MappedAssertion> assertions = new ArrayList<>(List.of(reply));
		assertions.addAll(additionalAssertions);
		return new PromptTest("reply", new RiskRank(1), List.of("reply_required"), List.of(), "A reply is requested.",
			List.of("{{request}}", "Reply again."), Duration.ofSeconds(30), "Replies with ok.", List.of(),
			List.of(new VariableDeclaration("request", "The first user turn.")), List.of(), List.of(testCase),
			assertions, List.of(), List.of(), Optional.empty());
	}

	/**
	 * Runs the real session adapter with the production observer and requires normal completion.
	 *
	 * @param subject the adapter and scripted harness
	 * @param test    the two-turn trial contract
	 * @return the completed trial evidence
	 * @throws IOException          if the session cannot start
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	private static SessionResult.Completed completeObservedTrial(ScriptedSubject subject, PromptTest test)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		TestCase testCase = test.testCases().getFirst();
		SessionRequest request = new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, subject.workspace(),
			List.of("Reply with ok.", "Reply again."), List.of(), test.timeLimit(), new TrialProfile("model-a", "low")).
			withTurnObserver(EarlyTrialEnd.forTest(test, testCase, subject.port()));
		SessionResult result = subject.port().start(request).completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
		requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
		return (SessionResult.Completed) result;
	}

	/**
	 * Runs the shared check that a scripted judge session completes and leaves
	 * the local evidence fixture bytes unchanged.
	 *
	 * @throws IOException          if the trial evidence cannot be prepared or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	@Test(timeOut = 60_000)
	public void judgeKeepsLocalEvidence()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contract(jvm, List.of(quickTurn())).judgeKeepsLocalEvidence();
		}
	}

	/**
	 * Runs the contract check that an interrupt ends a running turn as interrupted.
	 *
	 * @throws IOException          if the trial evidence cannot be prepared or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	@Test(timeOut = 60_000)
	public void interruptEndsARunningTurnAsInterrupted()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			interruptedContract(jvm).interruptEndsARunningTurnAsInterrupted();
		}
	}

	/**
	 * Runs the contract check that an interrupt after the session ended changes nothing.
	 *
	 * @throws IOException          if the trial evidence cannot be prepared or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	@Test(timeOut = 60_000)
	public void interruptAfterTheEndChangesNothing()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contract(jvm, List.of(quickTurn())).interruptAfterTheEndChangesNothing();
		}
	}

	/**
	 * Runs the contract check that the host login is reported without a session.
	 *
	 * @throws IOException if the trial evidence cannot be prepared or read
	 */
	@Test(timeOut = 60_000)
	public void reportsTheHostLoginWithoutASession() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contractWithoutSessions(jvm).reportsTheHostLoginWithoutASession();
		}
	}

	/**
	 * Runs the contract check that the port reports exit status as its harness does.
	 *
	 * @throws IOException if the trial evidence cannot be prepared or read
	 */
	@Test(timeOut = 60_000)
	public void reportsExitStatusAsTheHarnessDoes() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contractWithoutSessions(jvm).reportsExitStatusAsTheHarnessDoes();
		}
	}

	/**
	 * Runs the contract check that the port identifies its versions and results folders.
	 *
	 * @throws IOException if the trial evidence cannot be prepared or read
	 */
	@Test(timeOut = 60_000)
	public void identifiesItsVersionsAndResultsFolders() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contractWithoutSessions(jvm).identifiesItsVersionsAndResultsFolders();
		}
	}

	/**
	 * Runs the contract check that an empty host reports nothing.
	 *
	 * @throws IOException if the trial evidence cannot be prepared or read
	 */
	@Test(timeOut = 60_000)
	public void reportsNothingForAnEmptyHost() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contractWithoutSessions(jvm).reportsNothingForAnEmptyHost();
		}
	}

	/**
	 * Runs the contract check that a completed transcript's evidence reads the same each time.
	 *
	 * @throws IOException          if the trial evidence cannot be prepared or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	@Test(timeOut = 60_000)
	public void readsTheEvidenceOfACompletedTranscript()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contract(jvm, List.of(quickTurn())).readsTheEvidenceOfACompletedTranscript();
		}
	}

	/**
	 * Runs the contract check that an interrupted session keeps the rate-limit windows reported before the interrupt.
	 *
	 * @throws IOException          if the trial evidence cannot be prepared or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	@Test(timeOut = 60_000)
	public void interruptKeepsTheWindowsReportedBeforeIt()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			interruptedContract(jvm).interruptKeepsTheWindowsReportedBeforeIt();
		}
	}

	/**
	 * A port of the generic harness in a temporary directory.
	 *
	 * @param cleanup   deletes the directory
	 * @param port      the port
	 * @param workspace the directory that holds the sessions' project
	 * @param entered   counted down once a long turn is in progress
	 */
	private record ScriptedSubject(TemporaryDirectories cleanup, SessionPort port, Path workspace,
		CountDownLatch entered) implements Subject
	{
		/**
		 * Creates a subject.
		 *
		 * @param cleanup   deletes the directory
		 * @param port      the port
		 * @param workspace the directory that holds the sessions' project
		 * @param entered   counted down once a long turn is in progress
		 * @throws NullPointerException if any argument is null
		 */
		private ScriptedSubject
		{
			requireThat(cleanup, "cleanup").isNotNull();
			requireThat(port, "port").isNotNull();
			requireThat(workspace, "workspace").isNotNull();
			requireThat(entered, "entered").isNotNull();
		}

		/**
		 * Creates a subject in a new temporary directory.
		 *
		 * @param jvm     the JVM scope
		 * @param bridge  runs the runner's bridge
		 * @param entered counted down once a long turn is in progress
		 * @return the subject
		 * @throws IOException if the directory cannot be created
		 */
		static ScriptedSubject open(JvmScope jvm, Bridge bridge, CountDownLatch entered) throws IOException
		{
			TemporaryDirectories cleanup = new TemporaryDirectories();
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-contract-"));
			Path workspace = Files.createDirectories(root.resolve("workspace"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("results"), List::of,
				false, Map.of(), Files.createDirectories(root.resolve("configuration")), HarnessCapacity.unlimited(),
				Clock.systemUTC(), Yielding.fileReplacer());
			return new ScriptedSubject(cleanup, new TestSessions(settings, bridge), workspace, entered);
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
			requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();
			requireThat(session.completion().toCompletableFuture().isDone(), "endedEarly").isFalse();
		}

		@Override
		public void finish(SessionHandle session)
		{
			// The scripted harness ends each quick turn in response to its request
		}

		@Override
		public boolean reportsExitStatus()
		{
			return true;
		}

		@Override
		public Path pluginWithoutHooks() throws IOException
		{
			return Files.createDirectories(workspace.resolveSibling("plugin"));
		}

		@Override
		public List<RateLimitWindow> windowsReportedBeforeInterrupt()
		{
			return List.of(TestSessions.WINDOW);
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
				throw new AssertionError(e);
			}
		}
	}
}
