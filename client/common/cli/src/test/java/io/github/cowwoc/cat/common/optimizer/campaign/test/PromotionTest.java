package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.cat.common.optimizer.campaign.test.Builder.targeted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.AttemptResult;
import io.github.cowwoc.cat.common.optimizer.campaign.AttemptSelection;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.DiagnosticRerun;
import io.github.cowwoc.cat.common.optimizer.campaign.EditOrContinue;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.TestCaseOutcomes;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.Minimality;
import io.github.cowwoc.cat.common.optimizer.campaign.Promotion;
import io.github.cowwoc.cat.common.optimizer.campaign.PromotionRun.Role;
import io.github.cowwoc.cat.common.optimizer.campaign.PromotionRun;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.RegressionTestCase;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.secrets.PromotionSecrets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link Promotion} and the prompt author attempts that it judges.
 */
public final class PromotionTest
{
	private static final Sha256 ATTEMPT = Builder.snapshot("attempt");

	/**
	 * Creates the test.
	 */
	public PromotionTest()
	{
	}

	/**
	 * Returns the working-record examples and their required passing streak lengths.
	 *
	 * @return passes in four outcomes, required runs, and whether the test case is fixed
	 */
	@DataProvider
	public static Object[][] workingRecords()
	{
		return new Object[][] {{0, 2, true}, {1, 3, true}, {2, 4, true}, {3, 4, false}};
	}

	/**
	 * Plans only the extra runs needed by the working candidate's recorded outcomes on a fixed test case.
	 *
	 * @param passes the working candidate's passes in four outcomes
	 * @param requiredRuns the attempt's required passing runs
	 * @param fixed whether that streak establishes a fix
	 */
	// Requirement: R-D3
	@Test(dataProvider = "workingRecords")
	public void workingRecordDeterminesStreak(int passes, int requiredRuns, boolean fixed)
	{
		Campaign campaign = streakRound(passes);
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		DrawnTestCase testCase = campaign.development().promptAuthor().runs().getFirst().testCase();
		requireThat(campaign.development().promptAuthor().runs(), "startingRuns").size().isEqualTo(2);
		for (int completed = 0; completed < requiredRuns; ++completed)
		{
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, testCase, Verdict.PASS);
			if (completed + 1 < requiredRuns)
			{
				PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
				requireThat(promptAuthor.runs(), "plannedRuns").size().isEqualTo(Math.max(2, completed + 2));
				requireThat(Promotion.decide(promptAuthor, campaign.lineage().regressionSet(),
					campaign.development().testCaseOutcomes()), "decision").
					isEqualTo(Promotion.Decision.PENDING);
			}
		}
		if (fixed)
		{
			requireThat(Promotion.decide(campaign.development().promptAuthor(), campaign.lineage().regressionSet(),
				campaign.development().testCaseOutcomes()),
				"fixed").isEqualTo(Promotion.Decision.PROMOTE);
		}
		else
			requireThat(campaign.development().promptAuthor(), "rejected").isNull();
	}

	/**
	 * A failure on an extended streak stops further runs and rejects the only possible fix.
	 */
	// Requirement: R-D3
	@Test
	public void failureStopsExtendedStreak()
	{
		Campaign campaign = TransitionSteps.submit(streakRound(2), "safety", ATTEMPT, List.of());
		DrawnTestCase testCase = campaign.development().promptAuthor().runs().getFirst().testCase();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, testCase, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, testCase, Verdict.PASS);
		requireThat(campaign.development().promptAuthor().runs(), "extension").size().isEqualTo(3);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, testCase, Verdict.FAIL);
		requireThat(campaign.development().promptAuthor(), "rejected").isNull();
		requireThat(campaign.development().working().number(), "working").isEqualTo(1);
	}

	/**
	 * A representative pass on the same test case contributes to the working record used for a streak.
	 */
	// Requirement: R-D3
	@Test
	public void adoptedSameContentPassExtendsStreak()
	{
		Generator generator = new Generator(ContractFixtures.contract());
		DrawnTestCase representative = generator.draw("safety", DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		DrawnTestCase testCase = generator.markTargeted(representative);
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 60, 1).
			failure(testCase).regression(testCase, Verdict.FAIL).round(new Round(Map.of("safety", 0))).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.PASS);
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		for (int completed = 0; completed < 3; ++completed)
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, testCase, Verdict.PASS);
		requireThat(campaign.development().promptAuthor().runs(), "fourthRun").size().isEqualTo(4);
		requireThat(Promotion.decide(campaign.development().promptAuthor(), campaign.lineage().regressionSet(),
				campaign.development().testCaseOutcomes()),
			"pending").isEqualTo(Promotion.Decision.PENDING);
	}

	/**
	 * Returns a round whose triggering test case has four working-candidate outcomes.
	 *
	 * @param passes the passing diagnostic outcomes
	 * @return the campaign
	 */
	static Campaign streakRound(int passes)
	{
		DrawnTestCase testCase = targeted("safety", "logs");
		List<DiagnosticRerun> reruns = new ArrayList<>();
		for (int rerun = 0; rerun < 3; ++rerun)
		{
			Verdict verdict = Verdict.FAIL;
			if (rerun < passes)
				verdict = Verdict.PASS;
			reruns.add(new DiagnosticRerun(verdict, "diagnostic outcome"));
		}
		return new Builder().confirmed().working(1).development("safety", 60, 1).
			development("formatting", 61, 0).failure(new Failure(testCase, "representative failure", List.of(), null, reruns,
				List.of(), Builder.FILES)).regression(testCase, Verdict.FAIL).round(new Round(Map.of("safety", 0))).build();
	}

	/**
	 * Returns an unassigned run.
	 *
	 * @param role the run's role
	 * @param testCase the test case
	 * @return the run
	 */
	private static PromotionRun run(Role role, DrawnTestCase testCase)
	{
		return new PromotionRun(role, testCase, null, null);
	}

	/**
	 * Returns a judged run.
	 *
	 * @param role the run's role
	 * @param testCase the test case
	 * @param verdict the run's outcome
	 * @return the run
	 */
	private static PromotionRun run(Role role, DrawnTestCase testCase, Verdict verdict)
	{
		TrialKey key = new TrialKey(Purpose.PROMOTION, 1, ATTEMPT, testCase.testId(), 1);
		return new PromotionRun(role, testCase, key, verdict);
	}

	/**
	 * Returns a prompt author attempt on {@code safety}.
	 *
	 * @param runs the attempt's runs
	 * @return the attempt
	 */
	private static PromptAuthorAttempt attempt(PromotionRun... runs)
	{
		return new PromptAuthorAttempt(1, "safety", ATTEMPT, List.of(runs), Minimality.start(List.of(), ATTEMPT));
	}

	/**
	 * Judges synthetic run evidence against a working candidate with four failing outcomes per triggering test case.
	 *
	 * @param attempt the tested attempt
	 * @param regressions its regression evidence
	 * @return the production promotion decision
	 */
	private static Promotion.Decision decide(PromptAuthorAttempt attempt, List<RegressionTestCase> regressions)
	{
		List<TestCaseOutcomes> records = new ArrayList<>();
		for (PromotionRun run : attempt.runs())
		{
			if (run.role() == Role.TRIGGERING && records.stream().noneMatch(record ->
				PromotionSecrets.access().isSameTestCase(record.testCase(), run.testCase())))
			{
				records.add(new TestCaseOutcomes(run.testCase(), 0, 4));
			}
		}
		return Promotion.decide(attempt, regressions, records);
	}

	/**
	 * Verifies that the plan runs each triggering test case twice, each other regression test case once, and the
	 * working candidate once on each regression test case that it has no result on.
	 */
	// Requirement: R-D3
	@Test
	public void planRunsTriggeringTwiceAndRegressionOnce()
	{
		// Requirement: R-D3
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase cache = targeted("safety", "cache");
		DrawnTestCase formal = targeted("formatting", "formal");
		List<RegressionTestCase> regressionSet = List.of(new RegressionTestCase(logs, Verdict.FAIL, false),
			new RegressionTestCase(cache, Verdict.PASS, false), new RegressionTestCase(formal, null, false));
		List<PromotionRun> plan = Promotion.plan("safety", List.of(logs, formal), regressionSet,
			new Generator(ContractFixtures.contract()));

		requireThat(plan, "plan").isEqualTo(List.of(run(Role.TRIGGERING, logs), run(Role.TRIGGERING, logs),
			run(Role.REGRESSION, cache), run(Role.REGRESSION, formal), run(Role.WORKING_REFERENCE, formal)));
	}

	/**
	 * Verifies that an attempt is promoted when every triggering run passes and it fails no regression test case
	 * that the working candidate passes.
	 */
	// Requirement: R-D3
	@Test
	public void passingAttemptIsPromoted()
	{
		// Requirement: R-D3
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase cache = targeted("safety", "cache");
		List<RegressionTestCase> regressionSet = List.of(new RegressionTestCase(cache, Verdict.PASS, false));
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS),
			run(Role.TRIGGERING, logs, Verdict.PASS), run(Role.REGRESSION, cache, Verdict.PASS)), regressionSet),
			"decision").isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * Verifies that a failing run on the only triggering test case rejects the attempt.
	 */
	// Requirement: R-D3
	@Test
	public void onlyTriggeringTestCaseFails()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS),
			run(Role.TRIGGERING, logs, Verdict.FAIL)), List.of()), "decision").
			isEqualTo(Promotion.Decision.REJECT);
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.FAIL),
			run(Role.TRIGGERING, logs)), List.of()), "early").isEqualTo(Promotion.Decision.REJECT);
	}

	/**
	 * Verifies that a regression failure rejects the attempt only where the working candidate passes.
	 */
	// Requirement: R-D3
	@Test
	public void regressionCountsOnlyWhereWorkingPasses()
	{
		// Requirement: R-D3
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase cache = targeted("safety", "cache");
		DrawnTestCase tmp = targeted("safety", "tmp");
		List<RegressionTestCase> regressionSet = List.of(new RegressionTestCase(cache, Verdict.PASS, false),
			new RegressionTestCase(tmp, Verdict.FAIL, false));
		PromotionRun first = run(Role.TRIGGERING, logs, Verdict.PASS);
		PromotionRun second = run(Role.TRIGGERING, logs, Verdict.PASS);

		requireThat(decide(attempt(first, second, run(Role.REGRESSION, cache, Verdict.FAIL),
			run(Role.REGRESSION, tmp, Verdict.PASS)), regressionSet), "workingPasses").
			isEqualTo(Promotion.Decision.REJECT);
		requireThat(decide(attempt(first, second, run(Role.REGRESSION, cache, Verdict.PASS),
			run(Role.REGRESSION, tmp, Verdict.FAIL)), regressionSet), "workingFails").
			isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * Verifies that the working candidate's reference run decides a regression test case that it had no result on.
	 */
	// Requirement: R-D3
	@Test
	public void referenceRunDecidesUnknownWorkingResult()
	{
		// Requirement: R-D3
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase formal = targeted("formatting", "formal");
		List<RegressionTestCase> regressionSet = List.of(new RegressionTestCase(formal, null, false));
		PromotionRun first = run(Role.TRIGGERING, logs, Verdict.PASS);
		PromotionRun second = run(Role.TRIGGERING, logs, Verdict.PASS);
		PromotionRun regression = run(Role.REGRESSION, formal, Verdict.FAIL);

		requireThat(decide(attempt(first, second, regression,
			run(Role.WORKING_REFERENCE, formal, Verdict.PASS)), regressionSet), "referencePasses").
			isEqualTo(Promotion.Decision.REJECT);
		requireThat(decide(attempt(first, second, regression,
			run(Role.WORKING_REFERENCE, formal, Verdict.FAIL)), regressionSet), "referenceFails").
			isEqualTo(Promotion.Decision.PROMOTE);
		requireThat(decide(attempt(first, second, regression, run(Role.WORKING_REFERENCE, formal)),
			regressionSet), "referencePending").isEqualTo(Promotion.Decision.PENDING);
	}

	/**
	 * Returns the shared working-reference verdict and its effect on regression failures in every contender.
	 *
	 * @return the reference verdict and the expected promotion decision
	 */
	@DataProvider
	public static Object[][] referenceVerdicts()
	{
		return new Object[][] {{Verdict.PASS, Promotion.Decision.REJECT},
			{Verdict.FAIL, Promotion.Decision.PROMOTE}};
	}

	/**
	 * Three contenders share one assigned reference by content and all use its verdict without counting it as
	 * their own promotion evidence.
	 *
	 * @param verdict the working candidate's reference outcome
	 * @param decision the expected decision for each contender
	 */
	// Requirement: R-D12
	@Test(dataProvider = "referenceVerdicts")
	public void weakContendersShareReference(Verdict verdict, Promotion.Decision decision)
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase formal = targeted("formatting", "formal");
		DrawnTestCase repeated = new DrawnTestCase(formal.cellId(), formal.testId(),
			formal.testCase(), formal.turns(), formal.expectedBehaviour(), formal.generator(), formal.streamKind(),
			formal.streamPosition() + 1, true);
		// A second addressed cell without attempts keeps the round open, so its contenders remain inspectable
		Map<String, Integer> addressed = new LinkedHashMap<>();
		addressed.put("safety", 0);
		addressed.put("formatting", 0);
		Campaign campaign = new Builder().confirmed().working(1).
			development("safety", 0,
			EditOrContinue.failureLimit(Contract.DEFAULT_MINIMUM_PASS_RATE) + 1).failure(new Failure(logs, "failure",
			List.of(),
				null, List.of(new DiagnosticRerun(Verdict.FAIL, "fail"), new DiagnosticRerun(Verdict.FAIL, "fail"),
					new DiagnosticRerun(Verdict.FAIL, "fail")), List.of(), Builder.FILES)).regression(formal, null).
			round(new Round(addressed, true, Map.of(), List.of())).build();
		Sha256 working = campaign.development().working().snapshot();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, 1, working, repeated);
		TrialKey reference = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(reference));
		for (int number = 2; number <= 3; ++number)
			campaign = TransitionSteps.submit(campaign, "safety", Builder.snapshot("attempt" + number), List.of());
		requireThat(campaign.development().round().validationAttempts().stream().flatMap(attempt ->
			attempt.runs().stream()).filter(run -> run.role() == Role.WORKING_REFERENCE).count(),
			"sharedReferenceRuns").isEqualTo(1L);
		for (PromptAuthorAttempt attempt : campaign.development().round().validationAttempts())
		{
			for (int completed = 0; completed < Promotion.STARTING_TRIGGERING_RUNS; ++completed)
				campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, attempt.number(), attempt.snapshot(),
					logs, Verdict.PASS);
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, attempt.number(), attempt.snapshot(),
				formal, Verdict.FAIL);
			PromptAuthorAttempt pending = campaign.development().round().validationAttempts().stream().
				filter(candidate -> candidate.number() == attempt.number()).findFirst().orElseThrow();
			requireThat(Promotion.decide(pending, campaign.lineage().regressionSet(),
				campaign.development().testCaseOutcomes()), "pendingReference").isEqualTo(Promotion.Decision.PENDING);
		}
		campaign = TransitionSteps.complete(campaign, reference, verdict);
		for (PromptAuthorAttempt attempt : campaign.development().round().validationAttempts())
		{
			requireThat(Promotion.decide(attempt, campaign.lineage().regressionSet(),
				campaign.development().testCaseOutcomes()), "sharedDecision").isEqualTo(decision);
			requireThat(AttemptSelection.passingRuns(attempt.runs()), "ownPassingRuns").isEqualTo(2L);
			requireThat(AttemptSelection.plannedRuns(attempt.runs()), "ownPlannedRuns").isEqualTo(3L);
		}
		requireThat(campaign.optimization().attempts().size(), "settledAttempts").isEqualTo(3);
		for (AttemptResult result : campaign.optimization().attempts())
			requireThat(result.accepted(), "accepted").isEqualTo(decision == Promotion.Decision.PROMOTE);
	}

	/**
	 * A reference in flight remains usable after its sequential owner rejects and serves the next attempt.
	 */
	// Requirement: R-D12
	@Test
	public void referenceSurvivesRejectedOwner()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase formal = targeted("formatting", "formal");
		Campaign campaign = new Builder().confirmed().working(1).failure(new Failure(logs, "failure", List.of(), null,
			List.of(new DiagnosticRerun(Verdict.FAIL, "fail"), new DiagnosticRerun(Verdict.FAIL, "fail"),
				new DiagnosticRerun(Verdict.FAIL, "fail")), List.of(), Builder.FILES)).regression(formal, null).
			round(new Round(Map.of("safety", 0))).build();
		Sha256 working = campaign.development().working().snapshot();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, 1, working, formal);
		TrialKey reference = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(reference));
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, logs, Verdict.FAIL);
		requireThat(campaign.development().promptAuthor(), "rejectedOwner").isNull();

		Sha256 next = Builder.snapshot("next");
		campaign = TransitionSteps.submit(campaign, "safety", next, List.of());
		requireThat(campaign.development().promptAuthor().runs().stream().filter(run ->
			run.role() == Role.WORKING_REFERENCE).count(), "additionalReferences").isEqualTo(0L);
		for (int completed = 0; completed < Promotion.STARTING_TRIGGERING_RUNS; ++completed)
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 2, next, logs, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 2, next, formal, Verdict.FAIL);
		requireThat(Promotion.decide(campaign.development().promptAuthor(), campaign.lineage().regressionSet(),
			campaign.development().testCaseOutcomes()), "pendingReference").isEqualTo(Promotion.Decision.PENDING);
		campaign = TransitionSteps.complete(campaign, reference, Verdict.PASS);
		requireThat(campaign.development().promptAuthor(), "regressingAttempt").isNull();
		requireThat(campaign.development().round(), "endedRound").isNull();
		requireThat(campaign.lineage().regressionSet().getFirst().working(), "sharedVerdict").isEqualTo(Verdict.PASS);
	}

	/**
	 * Verifies that an attempt is pending while a needed run has no outcome.
	 */
	// Requirement: R-D3
	@Test
	public void unjudgedRunIsPending()
	{
		// Requirement: R-D3
		DrawnTestCase logs = targeted("safety", "logs");
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS), run(Role.TRIGGERING, logs)),
			List.of()), "decision").isEqualTo(Promotion.Decision.PENDING);
	}

	/**
	 * An attempt fixing one of two triggering test cases is accepted without a regression.
	 */
	// Requirement: R-D3
	@Test
	public void partialFixIsPromoted()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase cache = targeted("safety", "cache");
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS),
			run(Role.TRIGGERING, logs, Verdict.PASS), run(Role.TRIGGERING, cache, Verdict.FAIL),
			run(Role.TRIGGERING, cache, Verdict.PASS)), List.of()), "decision").
			isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * Rejects once each triggering test case has a failure, while another fix remains possible until then.
	 */
	// Requirement: R-D3
	@Test
	public void rejectsWhenEveryFixIsImpossible()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase cache = targeted("safety", "cache");
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.FAIL),
			run(Role.TRIGGERING, logs), run(Role.TRIGGERING, cache), run(Role.TRIGGERING, cache)), List.of()),
			"possibleFix").isEqualTo(Promotion.Decision.PENDING);
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.FAIL),
			run(Role.TRIGGERING, logs), run(Role.TRIGGERING, cache, Verdict.FAIL), run(Role.TRIGGERING, cache),
			run(Role.REGRESSION, targeted("formatting", "formal"))), List.of()), "noPossibleFix").
			isEqualTo(Promotion.Decision.REJECT);
	}

	/**
	 * A partial fix cannot regress a test case that the working candidate passes.
	 */
	// Requirement: R-D3
	@Test
	public void regressionRejectsPartialFix()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase cache = targeted("safety", "cache");
		DrawnTestCase formal = targeted("formatting", "formal");
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS),
			run(Role.TRIGGERING, logs, Verdict.PASS), run(Role.TRIGGERING, cache), run(Role.TRIGGERING, cache),
			run(Role.REGRESSION, formal, Verdict.FAIL)), List.of(new RegressionTestCase(formal, Verdict.PASS, false))),
			"decision").isEqualTo(Promotion.Decision.REJECT);
	}

	/**
	 * A partial fix waits for its remaining triggering and regression outcomes.
	 */
	// Requirement: R-D3
	@Test
	public void partialFixWaitsForNeededOutcomes()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase cache = targeted("safety", "cache");
		DrawnTestCase formal = targeted("formatting", "formal");
		PromotionRun first = run(Role.TRIGGERING, logs, Verdict.PASS);
		PromotionRun second = run(Role.TRIGGERING, logs, Verdict.PASS);
		requireThat(decide(attempt(first, second, run(Role.TRIGGERING, cache, Verdict.FAIL),
			run(Role.TRIGGERING, cache)), List.of()), "triggeringPending").isEqualTo(Promotion.Decision.PENDING);
		requireThat(decide(attempt(first, second, run(Role.TRIGGERING, cache, Verdict.FAIL),
			run(Role.TRIGGERING, cache, Verdict.FAIL), run(Role.REGRESSION, formal)), List.of()),
			"regressionPending").isEqualTo(Promotion.Decision.PENDING);
	}

	/**
	 * Two passing runs fix the same trial even when its draw position differs.
	 */
	// Requirement: R-D3
	@Test
	public void fixesMatchByContent()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase repeated = new DrawnTestCase(logs.cellId(), logs.testId(), logs.testCase(),
			logs.turns(), logs.expectedBehaviour(), logs.generator(), logs.streamKind(), logs.streamPosition() + 1,
			true);
		DrawnTestCase cache = targeted("safety", "cache");
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS),
			run(Role.TRIGGERING, repeated, Verdict.PASS), run(Role.TRIGGERING, cache, Verdict.FAIL),
			run(Role.TRIGGERING, cache, Verdict.FAIL)), List.of()), "decision").isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * One passing run cannot establish a fixed test case, and an empty plan cannot be promoted.
	 */
	// Requirement: R-D3
	@Test
	public void requiresBothTriggeringPasses()
	{
		requireThat(decide(attempt(run(Role.TRIGGERING, targeted("safety", "logs"), Verdict.PASS)),
			List.of()), "onePass").isEqualTo(Promotion.Decision.REJECT);
		requireThat(decide(attempt(), List.of()), "emptyPlan").isEqualTo(Promotion.Decision.REJECT);
	}

	/**
	 * Returns a campaign in a round on {@code safety} whose working candidate failed {@code logs}.
	 *
	 * @return the campaign
	 */
	private static Campaign inRound()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		return new Builder().confirmed().working(1).development("safety", 60, 1).
			development("formatting", 61, 0).failure(new Failure(logs, "failure", List.of(), null, List.of(
				new DiagnosticRerun(Verdict.FAIL, "fail"), new DiagnosticRerun(Verdict.FAIL, "fail"),
				new DiagnosticRerun(Verdict.FAIL, "fail")), List.of(), Builder.FILES)).regression(logs, Verdict.FAIL).
			round(new Round(Map.of("safety", 0))).build();
	}

	/**
	 * Verifies that promotion runs never count as representative evidence, and that a promoted attempt becomes the
	 * working candidate with new counts.
	 */
	// Requirement: R-D3
	@Test
	public void promotionRunsNeverCount()
	{
		// Requirement: R-D3
		Campaign campaign = inRound();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
		requireThat(promptAuthor.runs(), "runs").size().isEqualTo(2);
		DrawnTestCase logs = promptAuthor.runs().getFirst().testCase();

		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, promptAuthor.number(), ATTEMPT, logs,
			Verdict.PASS);
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(Builder.counts(60, 1));
		requireThat(campaign.development().working().number(), "working").isEqualTo(1);

		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, promptAuthor.number(), ATTEMPT, logs,
			Verdict.PASS);
		campaign = Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(ATTEMPT));
		requireThat(campaign.development().working(), "promoted").isEqualTo(new Candidate(2, ATTEMPT));
		requireThat(campaign.development().count("safety"), "newCounts").isEqualTo(CellCounts.EMPTY);
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(campaign.lineage().regressionSet(), "regressionSet").
			isEqualTo(List.of(new RegressionTestCase(logs, Verdict.PASS, false)));
	}

	/**
	 * Verifies that a run's outcome is recorded on that run, even when a run of another test or snapshot has the
	 * same sequence number.
	 */
	// Requirement: R-D3
	@Test
	public void outcomeReachesItsOwnRun()
	{
		// Requirement: R-D3
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase formal = targeted("formatting", "formal");
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 60, 1).
			development("formatting", 61, 0).failure(new Failure(logs, "failure", List.of(), null, List.of(
				new DiagnosticRerun(Verdict.FAIL, "fail"), new DiagnosticRerun(Verdict.FAIL, "fail"),
				new DiagnosticRerun(Verdict.FAIL, "fail")), List.of(), Builder.FILES)).regression(logs, Verdict.FAIL).
			regression(formal, Verdict.PASS).round(new Round(Map.of("safety", 0))).build();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
		requireThat(promptAuthor.runs().stream().map(PromotionRun::role).toList(), "roles").
			isEqualTo(List.of(Role.TRIGGERING, Role.TRIGGERING, Role.REGRESSION));
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, promptAuthor.number(), ATTEMPT, logs);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, promptAuthor.number(), ATTEMPT,
			promptAuthor.runs().get(2).testCase(), Verdict.PASS);

		List<PromotionRun> runs = campaign.development().promptAuthor().runs();
		requireThat(runs.get(0).verdict(), "triggering").isNull();
		requireThat(runs.get(2).verdict(), "regression").isEqualTo(Verdict.PASS);
	}

	/**
	 * Verifies that a rejected attempt keeps the working candidate and uses one of the test's attempts.
	 */
	// Requirement: R-D3
	@Test
	public void rejectedAttemptUsesRoundAttempt()
	{
		// Requirement: R-D3
		Campaign campaign = inRound();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
		DrawnTestCase logs = promptAuthor.runs().getFirst().testCase();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, promptAuthor.number(), ATTEMPT, logs,
			Verdict.FAIL);

		requireThat(campaign.development().working().number(), "working").isEqualTo(1);
		requireThat(campaign.development().promptAuthor(), "prompt_author").isNull();
		requireThat(campaign.development().round().attempts(), "completedAttempts").isEqualTo(Map.of("safety", 1));
		requireThat(campaign.promptAuthorAttempts(), "promptAuthorAttempts").isEqualTo(1);
	}

	/**
	 * Verifies that an attempt that leaves the working candidate unchanged is rejected without runs.
	 */
	// Requirement: R-D3
	@Test
	public void unchangedAttemptIsRejected()
	{
		// Requirement: R-D3
		Campaign campaign = inRound();
		campaign = TransitionSteps.submit(campaign, "safety",
			campaign.development().working().snapshot(), List.of());
		requireThat(campaign.development().promptAuthor(), "prompt_author").isNull();
		requireThat(campaign.development().round().attempts(), "completedAttempts").isEqualTo(Map.of("safety", 1));
	}

	/**
	 * Verifies that the outcome of an earlier prompt author attempt's run is dropped.
	 */
	// Requirement: R-D3
	@Test
	public void staleRunIsDropped()
	{
		// Requirement: R-D3
		Campaign campaign = inRound();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
		DrawnTestCase logs = promptAuthor.runs().getFirst().testCase();
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, promptAuthor.number(), ATTEMPT, logs);
		TrialKey slow = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(slow));
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, promptAuthor.number(), ATTEMPT, logs,
			Verdict.FAIL);
		requireThat(campaign.development().promptAuthor(), "prompt_author").isNull();

		List<LogicalTrial> before = new ArrayList<>(campaign.trials());
		campaign = TransitionSteps.complete(campaign, slow, Verdict.PASS);
		requireThat(campaign.trials(), "trials").size().isEqualTo(before.size() - 1);
		requireThat(campaign.development().round().attempts(), "completedAttempts").isEqualTo(Map.of("safety", 1));
	}
}
