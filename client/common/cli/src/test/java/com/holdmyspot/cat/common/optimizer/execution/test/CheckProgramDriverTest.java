package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.Allocation;
import com.holdmyspot.cat.common.optimizer.campaign.AllocationRule;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.Impact;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestLocations;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfiles;
import com.holdmyspot.cat.common.optimizer.contract.TrialRecord;
import com.holdmyspot.cat.common.optimizer.contract.UndecidableToolException;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram.Launch;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram.Outcome;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram.Result;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramEvaluation;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.execution.DriverContext;
import com.holdmyspot.cat.common.optimizer.execution.TrialEvidence;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TurnObserver;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort.FakeSession;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Exercises the owner's check-program decisions for recorded local and peer trials. */
public final class CheckProgramDriverTest
{
	private static final Harness OWNER = Harness.CODEX;
	private static final Harness PEER = Harness.CLAUDE;
	private static final String TEST = "formatting";
	private static final String CHECK = "records_service";
	private static final String TOOL = "check-program checks/Check.java {{tone}}";
	/**
	 * The reply assertion that a built-in tool decides after the check program.
	 */
	private static final String ACKNOWLEDGED = "acknowledged";
	private static final String SOURCE_MARKER = "private program source marker";
	private static final String EXAMPLE_MARKER = "private example evidence marker";
	private static final FileContent PROGRAM = new FileContent("""
		import java.nio.file.Files;
		import java.nio.file.Path;

		class Check
		{
			public static void main(String[] args) throws Exception
			{
				Path input = Path.of(System.getenv("CAT_CHECK_INPUT"));
				String before = Files.readString(input.resolve("initial/service.txt"));
				String after = Files.readString(input.resolve("project/service.txt"));
				System.out.println(args[0] + " " + before + " " + after);
				System.exit(1);
			}
		}
		// %s
		""".formatted(SOURCE_MARKER).getBytes(UTF_8));
	private static final FileContent EXAMPLE = new FileContent(("[[\"" + EXAMPLE_MARKER + "\"]]").getBytes(UTF_8));
	private static final Duration WAIT = Duration.ofSeconds(5);

	/**
	 * Returns both evaluators, tool-failure policies and kinds of unsuccessful check runs.
	 *
	 * @return the check cases
	 */
	@DataProvider
	public static Object[][] checkCases()
	{
		List<Object[]> cases = new ArrayList<>();
		for (Harness evaluator : List.of(OWNER, PEER))
		{
			for (OnToolFailure policy : OnToolFailure.values())
			{
				cases.add(new Object[]{evaluator, policy, false});
				cases.add(new Object[]{evaluator, policy, true});
			}
		}
		return cases.toArray(Object[][]::new);
	}

	/**
	 * Runs one check after the final turn and applies the tool-failure policy with isolated judge evidence.
	 *
	 * @param evaluator the harness that ran the trial
	 * @param policy whether the tool failure goes to the judge
	 * @param programError whether the failed check is a program error
	 * @throws IOException if the fixture cannot be used
	 * @throws InterruptedException if the test wait is interrupted
	 */
	@Test(dataProvider = "checkCases")
	public void checksOnlyFinishedTrial(Harness evaluator, OnToolFailure policy, boolean programError)
		throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("check-program-driver"));
			Path project = Files.createDirectory(directory.resolve("project"));
			Files.writeString(project.resolve(DriverHarness.PROMPT), "fork");
			Contract contract = contract(policy);
			Path testDirectory = PromptTestLocations.of(contract, DriverHarness.PROMPT).get(TEST).directory();
			writePrivateFiles(project.resolve(testDirectory));
			Campaign initial = assigned(contract, project, evaluator);
			TrialKey key = initial.trials().getFirst().key();
			DriverHarness harness = new DriverHarness(directory, initial, InventoryScanner.scan(project, Set.of()), project);
			Path completeSource = harness.workspace().stage(harness.overlays().require(key.snapshot()), Set.of());
			requireThat(Files.isRegularFile(completeSource.resolve(testDirectory).resolve("checks/Check.java")),
				"checkInInventory").isTrue();
			harness.judges(request -> harness.judged(request, Verdict.PASS, "The action meets the requirement."));
			harness.triages(_ -> harness.triaged(Impact.BLOCKING));
			AtomicInteger peerStarts = new AtomicInteger();
			FakeSessionPort peer = new FakeSessionPort()
			{
				@Override
				public SessionHandle start(SessionRequest request) throws IOException
				{
					requireThat(request.role(), "peerRole").isEqualTo(SessionRole.TRIAL);
					peerStarts.incrementAndGet();
					return harness.sessions().start(request);
				}
			};
			FakeSessionPort trialPort = harness.sessions();
			int expectedPeerStarts = 0;
			if (evaluator == PEER)
			{
				trialPort = peer;
				expectedPeerStarts = 1;
			}
			SessionResult.Completed prefix = harness.completed("pass", DriverHarness.MODEL);
			Files.writeString(prefix.initialProject().resolve("service.txt"), "old");
			Files.writeString(prefix.effects().resolve("service.txt"), "old");
			SessionResult.Completed finalResult = finalResult(prefix);
			SessionEvidence activity = new SessionEvidence(List.of(new SessionEvidence.Tool(2,
				SessionEvidence.ROOT_AGENT, evaluator.commandName() + "_write", "write service.txt", true, 0, "saved")));
			String observed = "check output for the " + evaluator.commandName() + " trial";
			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch finish = new CountDownLatch(1);
			AtomicInteger checkRuns = new AtomicInteger();
			Thread driverThread = Thread.currentThread();
			CheckProgramEvaluation check = (test, testCase, evidence, runs) ->
			{
				requireThat(Thread.currentThread() == driverThread, "ranOnDriverThread").isFalse();
				requireThat(checkRuns.incrementAndGet(), "checkRuns").isEqualTo(1);
				requireThat(runs, "runs").isEqualTo(1);
				started.countDown();
				if (!finish.await(WAIT.toSeconds(), TimeUnit.SECONDS))
					throw new AssertionError("The test did not release the check.");
				requireThat(test.id(), "test").isEqualTo(TEST);
				requireThat(testCase, "suppliedCase").isEqualTo(initial.trials().getFirst().testCase().testCase());
				requireThat(evidence.replies(), "replies").isEqualTo(finalResult.responses());
				requireThat(evidence.activity(), "activity").isEqualTo(activity);
				requireThat(evidence.initialProject(), "initialProject").isEqualTo(finalResult.initialProject());
				requireThat(evidence.effects(), "effects").isEqualTo(finalResult.effects());
				requireThat(Files.readString(evidence.initialProject().resolve("service.txt")), "initial").isEqualTo("old");
				requireThat(Files.readString(evidence.effects().resolve("service.txt")), "final").isEqualTo("new");
				return checkRun(test, testCase, programError, observed);
			};
			DriverContext context = withCheck(harness.context(harness.sessions(),
				Map.of(OWNER, harness.sessions(), PEER, peer), Map.of()), check);
			try (Driver driver = Driver.open(context, DriverHarness.CAMPAIGN).orElseThrow())
			{
				try
				{
					driver.step();
					FakeSession trial = harness.runningTrials().getFirst();
					requireThat(peerStarts.get(), "peerStarts").isEqualTo(expectedPeerStarts);
					requireThat(Files.notExists(trial.request().source().resolve(testDirectory)), "privateFilesHidden").
						isTrue();
					TurnObserver observer = trial.request().turnObserver().orElseThrow();
					requireThat(observer.endAfter(1, prefix), "endAfterFirstTurn").isFalse();
					requireThat(checkRuns.get(), "checksAfterFirstTurn").isEqualTo(0);
					Files.writeString(finalResult.effects().resolve("service.txt"), "new");
					trialPort.supplyEvidence(finalResult.transcript(), activity);
					requireThat(observer.endAfter(2, finalResult), "endAfterLastTurn").isFalse();
					requireThat(observer.progress().completedTurn(), "completedTurn").isEqualTo(2);
					requireThat(checkRuns.get(), "checksBeforeSessionEnd").isEqualTo(0);
					requireThat(judges(harness), "judgesBeforeSessionEnd").isEmpty();

					// Stop admitting trials while allowing the started trial and its decision to finish.
					harness.clock().advance(context.settings().budget());
					trial.end(finalResult);
					driver.step();
					requireThat(started.await(WAIT.toSeconds(), TimeUnit.SECONDS), "checkStarted").isTrue();
					requireThat(judges(harness), "judgesWhileChecking").isEmpty();
					requireThat(DriverTest.recordedOutcome(harness, key), "outcomeWhileChecking").isEmpty();
				}
				finally
				{
					finish.countDown();
				}
				requireThat(driver.awaitEvent(WAIT), "checkCompleted").isTrue();
				driver.step();
				if (policy == OnToolFailure.JUDGE)
					requireJudge(harness, context, finalResult, observed);
				else
					requireThat(judges(harness), "judgeStarts").isEmpty();
				harness.settle(driver);
			}
			requireThat(checkRuns.get(), "checkRuns").isEqualTo(1);
			requireThat(trialPort.readTranscripts(), "evaluatorRead").contains(finalResult.transcript());
			if (evaluator == PEER)
				requireThat(harness.sessions().readTranscripts(), "ownerRead").doesNotContain(finalResult.transcript());
			com.holdmyspot.cat.common.optimizer.store.TrialRecord recorded =
				harness.store().readRecord(DriverHarness.CAMPAIGN, key).orElseThrow();
			requireThat(recorded.infrastructureAttempts(), "infrastructureAttempts").isEmpty();
			requireThat(recorded.sessions().size(), "trialSessions").isEqualTo(1);
			requireThat(recorded.outcome().responses(), "recordedReplies").isEqualTo(finalResult.responses());
			requireThat(recorded.outcome().endedEarly(), "endedEarly").isFalse();
			if (policy == OnToolFailure.JUDGE)
			{
				requireThat(recorded.outcome().verdict(), "verdict").isEqualTo(Verdict.PASS);
				requireThat(recorded.outcome().judge().orElseThrow(), "judge").isEqualTo(contract.judge().profile());
				requireThat(recorded.outcome().failedAssertions(), "failedAssertions").isEmpty();
			}
			else
			{
				String behavior = "check_program_failed";
				if (programError)
					behavior = "check_program_error";
				requireThat(recorded.outcome().verdict(), "verdict").isEqualTo(Verdict.FAIL);
				requireThat(recorded.outcome().judge(), "judge").isEmpty();
				requireThat(recorded.outcome().failedAssertions(), "failedAssertions").
					isEqualTo(List.of(new FailedAssertion(CHECK, behavior)));
				requireThat(recorded.outcome().rationale(), "rationale").contains(observed);
			}
		}
	}

	/**
	 * Runs the staged Java source against recorded project snapshots and preserves ordinary built-in assertion order.
	 * The test requires one sandboxed JVM to observe both snapshots and the substituted argument.
	 *
	 * @throws IOException if the fixture cannot be staged or the sandbox cannot launch
	 * @throws InterruptedException if the check is interrupted
	 * @throws UndecidableToolException if the reply tool cannot decide
	 */
	@Test
	public void evaluatesStagedProgram() throws IOException, InterruptedException, UndecidableToolException
	{
		// Requirement: R-L21
		Launch launch = CheckProgramTest.nativeLaunch();
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "check-program-evaluation"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path staging = Files.createDirectory(root.resolve("staging"));
			Contract contract = contract(OnToolFailure.FAIL);
			PromptTest test = contract.findTest(TEST).orElseThrow();
			TestCase testCase = test.testCases().getFirst();
			Campaign campaign = assigned(contract, project, OWNER);
			DriverHarness harness = new DriverHarness(root, campaign, InventoryScanner.scan(project, Set.of()), project);
			SessionResult.Completed completed = harness.completed("pass", DriverHarness.MODEL);
			Files.writeString(completed.initialProject().resolve("service.txt"), "before");
			Files.writeString(completed.effects().resolve("service.txt"), "after");
			TrialRecord record = TrialEvidence.read(harness.sessions(), completed);

			List<BuiltInToolResult> results = CheckProgramEvaluation.isolated(launch, scope.json(), staging).
				decide(test, testCase, record);

			requireThat(results, "results").size().isEqualTo(2);
			BuiltInToolResult check = results.getFirst();
			requireThat(check.behavior(), "behavior").isEqualTo("check_program_failed");
			requireThat(check.failsTrial(), "failsTrial").isTrue();
			requireThat(check.observed(), "observed").contains(PromptTest.textByName(testCase).get("tone") + " before after");
			requireThat(results.getLast().assertion().id(), "finalAssertion").isEqualTo(ACKNOWLEDGED);
			requireThat(results.getLast().passed(), "replyPassed").isTrue();
			try (Stream<Path> entries = Files.list(staging))
			{
				requireThat(entries.findAny(), "remainingStaging").isEmpty();
			}
		}
	}

	/**
	 * Runs each check program as many times as requested on one staged copy of the evidence, each run seeing the same
	 * input, and leaves no staging behind.
	 *
	 * @throws IOException          if the fixture cannot be staged or the sandbox cannot launch
	 * @throws InterruptedException if the check is interrupted
	 */
	@Test
	public void runsEachProgramRequestedTimes() throws IOException, InterruptedException
	{
		// Requirement: R-L22
		Launch launch = CheckProgramTest.nativeLaunch();
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "check-program-runs"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path staging = Files.createDirectory(root.resolve("staging"));
			Contract contract = contract(OnToolFailure.FAIL);
			PromptTest test = contract.findTest(TEST).orElseThrow();
			TestCase testCase = test.testCases().getFirst();
			Campaign campaign = assigned(contract, project, OWNER);
			DriverHarness harness = new DriverHarness(root, campaign, InventoryScanner.scan(project, Set.of()), project);
			SessionResult.Completed completed = harness.completed("pass", DriverHarness.MODEL);
			Files.writeString(completed.initialProject().resolve("service.txt"), "before");
			Files.writeString(completed.effects().resolve("service.txt"), "after");
			TrialRecord record = TrialEvidence.read(harness.sessions(), completed);

			Map<String, List<Result>> runs = CheckProgramEvaluation.isolated(launch, scope.json(), staging).
				run(test, testCase, record, 2);

			requireThat(runs.keySet(), "assertions").containsExactly(Set.of(CHECK));
			List<Result> checkRuns = runs.get(CHECK);
			requireThat(checkRuns, "checkRuns").size().isEqualTo(2);
			String expected = PromptTest.textByName(testCase).get("tone") + " before after";
			for (Result run : checkRuns)
			{
				requireThat(run.outcome(), "outcome").isEqualTo(Outcome.FAIL);
				requireThat(run.observed(), "observed").contains(expected);
			}
			try (Stream<Path> entries = Files.list(staging))
			{
				requireThat(entries.findAny(), "remainingStaging").isEmpty();
			}
		}
	}

	/**
	 * Writes files a delivered test would have in the base project, where trial staging must exclude them.
	 *
	 * @param directory the test's directory
	 * @throws IOException if the check or example cannot be written
	 */
	private static void writePrivateFiles(Path directory) throws IOException
	{
		Path checks = Files.createDirectories(directory.resolve("checks"));
		Files.write(checks.resolve("Check.java"), PROGRAM.bytes());
		Path example = Files.createDirectories(directory.resolve("examples").resolve(CHECK).resolve("fail"));
		Files.write(example.resolve("replies.json"), EXAMPLE.bytes());
	}

	/**
	 * Returns a two-turn contract whose only assertion that may need a judge is the check program.
	 *
	 * @param policy what the check's failure means
	 * @return the shared contract and its confirmed check and example bytes
	 */
	private static Contract contract(OnToolFailure policy)
	{
		Contract base = ContractFixtures.contract();
		PromptTest formatting = base.findTest(TEST).orElseThrow();
		Requirement requirement = new Requirement(CHECK, "The requested service is recorded.", Requirement.GOAL, null,
			List.of("service_missing"));
		EvidenceScope scope = null;
		if (policy == OnToolFailure.JUDGE)
			scope = EvidenceScope.ACTIONS;
		Assertion check = new Assertion(CHECK, AssertionKind.EFFECT, TOOL, policy, requirement.description(), null, scope);
		Assertion acknowledged = new Assertion(ACKNOWLEDGED, AssertionKind.EFFECT, "exact-text turn=1 pass",
			OnToolFailure.FAIL, "The first reply acknowledges the request.");
		List<TestCase> cases = formatting.testCases().stream().map(testCase -> new TestCase(testCase.values(),
			"The initial request is acknowledged for " + testCase.describedValues() + ".")).toList();
		Path directory = PromptTestLocations.scenarioDirectory(DriverHarness.PROMPT).resolve(TEST);
		PromptTest.Draft draft = new PromptTest.Draft(directory, "Check the service saved by the second turn.", Map.of(),
			Map.of(CHECK + "/fail/replies.json", EXAMPLE), Set.of(), Map.of("Check.java", PROGRAM));
		PromptTest test = new PromptTest(TEST, formatting.riskRank(), List.of(CHECK), List.of(),
			"The first turn requests an acknowledgment.", List.of("Reply pass in {{tone}} style.",
			"Record a service for a {{audience}} reader."), formatting.timeLimit(),
			"The assistant acknowledges the request and records the service.", List.of(), formatting.variables(),
			List.of(), cases, List.of(new MappedAssertion(check, CHECK), new MappedAssertion(acknowledged, CHECK)),
			List.of(), List.of(), Optional.of(draft));
		TrialProfiles local = ContractFixtures.profiles(List.of(requirement), List.of(test));
		TrialProfiles profiles = TrialProfiles.forEvaluators(Map.of(OWNER, local, PEER, local));
		return new Contract(List.of(requirement), List.of(test), profiles, base.judge(), base.limitations(),
			base.minimumPassRate(), base.salt());
	}

	/**
	 * Assigns the first development trial to the requested evaluator.
	 *
	 * @param contract the confirmed two-turn test
	 * @param project the base project
	 * @param evaluator the harness responsible for the trial
	 * @return the campaign before the trial starts
	 */
	private static Campaign assigned(Contract contract, Path project, Harness evaluator)
	{
		Header base = new Builder().build().header();
		Header header = new Header(base.id(), project, project.resolve(DriverHarness.PROMPT), OWNER, base.lifecycle(),
			base.goal(), base.extraPermissions(), Set.of(OWNER, PEER));
		Campaign initial = new Builder().header(header).confirmed(contract, 1).working(1).readyBaseline().
			ceiling(1).window(1).build();
		String cell = contract.profiles().cells().stream().filter(candidate -> candidate.evaluator().orElseThrow() ==
			evaluator).findFirst().orElseThrow().id();
		Allocation allocation = new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, initial.evidenceRevision(),
			DriverHarness.START);
		Admission.Assign admission = new Admission.Assign(Purpose.DEVELOPMENT, initial.development().series(),
			initial.development().working().snapshot(), cell, null, allocation);
		return Transitions.apply(initial, new CampaignEvent.Assign(admission));
	}

	/**
	 * Adds the final messages to the same conversation, which the fixture updates before reporting completion.
	 *
	 * @param prefix the completed first turn
	 * @return the two-turn result
	 */
	private static SessionResult.Completed finalResult(SessionResult.Completed prefix)
	{
		return new SessionResult.Completed(prefix.transcript(), List.of(List.of("pass"), List.of("working", "saved")),
			prefix.effects(), prefix.initialProject(), prefix.model(), prefix.reasoning(), prefix.usage(), prefix.windows(),
			prefix.promptSources(), prefix.toolCalls());
	}

	/**
	 * Checks the program inputs and returns one fake run of the program, which fails or errs.
	 *
	 * @param test         the confirmed test
	 * @param testCase     the actual trial's values
	 * @param programError whether the program errored
	 * @param observed     the program output
	 * @return the run, keyed by the check-program assertion's ID
	 */
	private static Map<String, List<Result>> checkRun(PromptTest test, TestCase testCase, boolean programError,
		String observed)
	{
		Assertion assertion = test.assertions().getFirst().assertion();
		BuiltInTool.CheckProgram program = (BuiltInTool.CheckProgram) assertion.builtInTool().orElseThrow();
		requireThat(program.program(), "program").isEqualTo("checks/Check.java");
		PromptTest.Draft draft = test.draft().orElseThrow();
		requireThat(draft.checkFiles(), "confirmedCheckFiles").isEqualTo(Map.of("Check.java", PROGRAM));
		Map<String, String> values = PromptTest.textByName(testCase);
		requireThat(program.filledArguments(values), "arguments").isEqualTo(List.of(values.get("tone")));
		Outcome outcome = Outcome.FAIL;
		if (programError)
			outcome = Outcome.ERROR;
		return Map.of(assertion.id(), List.of(new Result(outcome, observed, Duration.ofSeconds(1))));
	}

	/**
	 * Supplies the owner's check evaluator to the fixture's driver context.
	 *
	 * @param base the fixture's context for sessions and storage
	 * @param check the fake evaluator for completed trial records
	 * @return the context
	 */
	private static DriverContext withCheck(DriverContext base, CheckProgramEvaluation check)
	{
		return new DriverContext(base.fileLocks(), base.store(), base.sessions(), base.workspace(), base.interrupts(),
			base.clock(), base.overlays(), base.judge(), base.triage(), base.ranking(), base.promptAuthor(), base.values(),
			base.deliverer(), base.calibrator(), base.terminator(), base.leftoverSessions(), base.authority(),
			base.promptSources(), base.environment(), base.lockFile(), base.random(), base.settings(), base.json(),
			base.candidateJudges(), base.judgeRecords(), base.evaluationSessions(), base.temporaryDirectory(), check);
	}

	/**
	 * Returns the judges started through the campaign owner's port.
	 *
	 * @param harness the campaign fixture
	 * @return the judge requests in launch order
	 */
	private static List<SessionRequest> judges(DriverHarness harness)
	{
		return harness.sessions().sessions().stream().map(FakeSession::request).
			filter(request -> request.role() == SessionRole.JUDGE).toList();
	}

	/**
	 * Verifies the isolated action judge receives the check's observation and the ordinary trial evidence.
	 *
	 * @param harness the campaign fixture
	 * @param context the context that supplies the JSON reader
	 * @param completed the checked trial
	 * @param observed the program's observation
	 */
	private static void requireJudge(DriverHarness harness, DriverContext context, SessionResult.Completed completed,
		String observed)
	{
		List<SessionRequest> judges = judges(harness);
		requireThat(judges, "judgeStarts").size().isEqualTo(1);
		SessionRequest request = judges.getFirst();
		requireThat(request.source(), "judgeSource").isEqualTo(harness.directory().resolve("judge"));
		requireThat(request.readOnly(), "judgeReadRoots").
			isEqualTo(List.of(completed.effects(), completed.transcript().getParent()));
		requireThat(harness.replies(request), "judgeReplies").isEqualTo(completed.responses());
		JsonNode assertions = harness.decidedAssertions(request);
		requireThat(assertions.size(), "judgedAssertions").isEqualTo(1);
		requireThat(assertions.get(0).path("id").asString(), "judgedAssertion").isEqualTo(CHECK);
		String turn = request.turns().getFirst();
		requireThat(turn, "judgePrompt").doesNotContain(SOURCE_MARKER).doesNotContain(EXAMPLE_MARKER);
		int start = turn.indexOf('[', turn.indexOf("### Built-in tool results"));
		int end = turn.indexOf("\n\n", start);
		JsonNode results = context.json().readTree(turn.substring(start, end));
		JsonNode check = results.get(0);
		requireThat(check.path("assertion_id").asString(), "observedAssertion").isEqualTo(CHECK);
		requireThat(check.path("tool").asString(), "shownTool").isEqualTo(TOOL);
		requireThat(check.path("on_tool_failure").asString(), "shownPolicy").isEqualTo("judge");
		requireThat(check.path("passed").asBoolean(true), "shownPass").isFalse();
		requireThat(check.path("observed").asString(), "observedValue").isEqualTo(observed);
	}
}
