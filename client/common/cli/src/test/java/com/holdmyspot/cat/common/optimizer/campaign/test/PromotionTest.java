package com.holdmyspot.cat.common.optimizer.campaign.test;

import static com.holdmyspot.cat.common.optimizer.campaign.test.Builder.targeted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.AttemptResult;
import com.holdmyspot.cat.common.optimizer.campaign.AttemptSelection;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Candidate;
import com.holdmyspot.cat.common.optimizer.campaign.CellCounts;
import com.holdmyspot.cat.common.optimizer.campaign.DiagnosticRerun;
import com.holdmyspot.cat.common.optimizer.campaign.EditOrContinue;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.TestCaseOutcomes;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.Minimality;
import com.holdmyspot.cat.common.optimizer.campaign.Promotion;
import com.holdmyspot.cat.common.optimizer.campaign.PromotionReplay.Role;
import com.holdmyspot.cat.common.optimizer.campaign.PromotionReplay;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.Scheduler;
import com.holdmyspot.cat.common.optimizer.campaign.RegressionTestCase;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.Generator;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.report.ReportEvidence;
import com.holdmyspot.cat.common.optimizer.report.StatusReport;
import com.holdmyspot.cat.common.secrets.PromotionSecrets;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
	 * @return passes in four outcomes and required replays
	 */
	@DataProvider
	public static Object[][] workingRecords()
	{
		return new Object[][] {{0, 2}, {1, 3}, {2, 4}, {3, 6}};
	}

	/**
	 * Plans only the extra replays needed by the working candidate's recorded outcomes on a fixed test case.
	 *
	 * @param passes the working candidate's passes in four outcomes
	 * @param requiredReplays the attempt's required passing replays
	 */
	// Requirement: R-D3
	@Test(dataProvider = "workingRecords")
	public void workingRecordDeterminesStreak(int passes, int requiredReplays)
	{
		Campaign campaign = streakRound(passes);
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		DrawnTestCase testCase = campaign.development().promptAuthor().replays().getFirst().testCase();
		requireThat(campaign.development().promptAuthor().replays(), "startingReplays").size().isEqualTo(2);
		for (int completed = 0; completed < requiredReplays; ++completed)
		{
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, testCase, Verdict.PASS);
			if (completed + 1 < requiredReplays)
			{
				PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
				requireThat(promptAuthor.replays(), "plannedReplays").size().isEqualTo(Math.max(2, completed + 2));
				requireThat(Promotion.decide(promptAuthor, campaign.development().working().snapshot(),
					campaign.lineage().regressionSet(),
					campaign.development().testCaseOutcomes()), "decision").
					isEqualTo(Promotion.Decision.PENDING);
			}
		}
		requireThat(Promotion.decide(campaign.development().promptAuthor(), campaign.development().working().snapshot(),
			campaign.lineage().regressionSet(),
			campaign.development().testCaseOutcomes()),
			"fixed").isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * A failure on an extended streak stops further replays and rejects the only possible fix.
	 */
	// Requirement: R-D3
	@Test
	public void failureStopsExtendedStreak()
	{
		Campaign campaign = TransitionSteps.submit(streakRound(2), "safety", ATTEMPT, List.of());
		DrawnTestCase testCase = campaign.development().promptAuthor().replays().getFirst().testCase();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, testCase, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, testCase, Verdict.PASS);
		requireThat(campaign.development().promptAuthor().replays(), "extension").size().isEqualTo(3);
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
		requireThat(campaign.development().promptAuthor().replays(), "fourthReplay").size().isEqualTo(4);
		requireThat(Promotion.decide(campaign.development().promptAuthor(), campaign.development().working().snapshot(),
			campaign.lineage().regressionSet(),
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
	 * Returns an unassigned replay.
	 *
	 * @param role the replay's role
	 * @param testCase the test case
	 * @return the replay
	 */
	private static PromotionReplay replay(Role role, DrawnTestCase testCase)
	{
		return new PromotionReplay(role, testCase, null, null);
	}

	/**
	 * Returns a judged replay.
	 *
	 * @param role the replay's role
	 * @param testCase the test case
	 * @param verdict the replay's outcome
	 * @return the replay
	 */
	private static PromotionReplay replay(Role role, DrawnTestCase testCase, Verdict verdict)
	{
		TrialKey key = new TrialKey(Purpose.PROMOTION, 1, ATTEMPT, testCase.testId(), 1);
		return new PromotionReplay(role, testCase, key, verdict);
	}

	/**
	 * Returns a prompt author attempt on {@code safety}.
	 *
	 * @param replays the attempt's replays
	 * @return the attempt
	 */
	private static PromptAuthorAttempt attempt(PromotionReplay... replays)
	{
		return new PromptAuthorAttempt(1, "safety", ATTEMPT, List.of(replays), Minimality.start(List.of(), ATTEMPT));
	}

	/**
	 * Judges synthetic replay evidence against a working candidate with four failing outcomes per triggering test case.
	 *
	 * @param attempt the tested attempt
	 * @param regressions its regression evidence
	 * @return the production promotion decision
	 */
	private static Promotion.Decision decide(PromptAuthorAttempt attempt, List<RegressionTestCase> regressions)
	{
		List<TestCaseOutcomes> records = new ArrayList<>();
		for (PromotionReplay replay : attempt.replays())
		{
			if (replay.role() == Role.TRIGGERING && records.stream().noneMatch(record ->
				PromotionSecrets.getPromotionAccess().isSameTestCase(record.testCase(), replay.testCase())))
			{
				records.add(new TestCaseOutcomes(replay.testCase(), 0, 4));
			}
		}
		return Promotion.decide(attempt, Builder.snapshot("working"), regressions, records);
	}

	/**
	 * Verifies that the plan runs each triggering test case twice, and that once a triggering test case passes twice,
	 * each other regression test case runs once and the working candidate runs once on each regression test case that
	 * it has no result on.
	 */
	// Requirement: R-D3
	@Test
	public void planReplaysTriggeringTwiceRegressionOnce()
	{
		// Requirement: R-D3
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase cache = targeted("safety", "cache");
		DrawnTestCase formal = targeted("formatting", "formal");
		List<RegressionTestCase> regressionSet = List.of(new RegressionTestCase(logs, Verdict.FAIL, false),
			new RegressionTestCase(cache, Verdict.PASS, false), new RegressionTestCase(formal, null, false));
		List<PromotionReplay> plan = Promotion.plan(List.of("safety"), List.of(logs, formal),
			new Generator(ContractFixtures.contract()));
		requireThat(plan, "plan").isEqualTo(List.of(replay(Role.TRIGGERING, logs), replay(Role.TRIGGERING, logs)));

		PromptAuthorAttempt fixed = Promotion.extend(attempt(replay(Role.TRIGGERING, logs, Verdict.PASS),
			replay(Role.TRIGGERING, logs, Verdict.PASS)), regressionSet, List.of(new TestCaseOutcomes(logs, 0, 4)),
			List.of());
		requireThat(fixed.replays().subList(2, fixed.replays().size()), "regressionReplays").isEqualTo(List.of(
			replay(Role.REGRESSION, cache), replay(Role.REGRESSION, formal), replay(Role.WORKING_REFERENCE, formal)));
	}

	/**
	 * Verifies that an attempt is promoted when every triggering replay passes and it fails no regression test case
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
		requireThat(decide(attempt(replay(Role.TRIGGERING, logs, Verdict.PASS),
			replay(Role.TRIGGERING, logs, Verdict.PASS), replay(Role.REGRESSION, cache, Verdict.PASS)), regressionSet),
			"decision").isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * Verifies that a failing replay on the only triggering test case rejects the attempt.
	 */
	// Requirement: R-D3
	@Test
	public void onlyTriggeringTestCaseFails()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		requireThat(decide(attempt(replay(Role.TRIGGERING, logs, Verdict.PASS),
			replay(Role.TRIGGERING, logs, Verdict.FAIL)), List.of()), "decision").
			isEqualTo(Promotion.Decision.REJECT);
		requireThat(decide(attempt(replay(Role.TRIGGERING, logs, Verdict.FAIL),
			replay(Role.TRIGGERING, logs)), List.of()), "early").isEqualTo(Promotion.Decision.REJECT);
	}

	/**
	 * Verifies that the working candidate's failures on the same test cases make the attempt's regression failures
	 * likely: failures that would reject the attempt where the working candidate passes need more paired replays where
	 * the working candidate also fails.
	 */
	// Requirement: R-D3
	@Test
	public void regressionCountsOnlyWhereWorkingPasses()
	{
		// Requirement: R-D3
		List<RegressionTestCase> regressionSet = List.of(new RegressionTestCase(targeted("formatting", "formal"),
			Verdict.PASS, false));
		requireThat(decide(pairedAttempt(List.of(), pairs(5, 1, 2), pairs(5)), regressionSet), "workingPasses").
			isEqualTo(Promotion.Decision.REJECT);
		requireThat(decide(pairedAttempt(List.of(), pairs(5, 1, 2), pairs(5, 1, 2)), regressionSet),
			"workingFails").isEqualTo(Promotion.Decision.PENDING);
	}

	/**
	 * Verifies that the working candidate's reference replay supplies its outcome on a regression test case that it had
	 * no result on.
	 */
	// Requirement: R-D3
	@Test
	public void referenceReplayDecidesUnknownWorking()
	{
		// Requirement: R-D3
		DrawnTestCase formal = targeted("formatting", "formal");
		List<RegressionTestCase> regressionSet = List.of(new RegressionTestCase(formal, null, false));
		requireThat(decide(pairedAttempt(List.of(replay(Role.WORKING_REFERENCE, formal, Verdict.PASS)), pairs(5, 1, 2),
			pairs(5)), regressionSet), "referencePasses").isEqualTo(Promotion.Decision.REJECT);
		requireThat(decide(pairedAttempt(List.of(replay(Role.WORKING_REFERENCE, formal, Verdict.FAIL)), pairs(5, 1, 2),
			pairs(5)), regressionSet), "referenceFails").isEqualTo(Promotion.Decision.PENDING);
		requireThat(decide(pairedAttempt(List.of(replay(Role.WORKING_REFERENCE, formal)), List.of(), List.of()),
			regressionSet), "referencePending").isEqualTo(Promotion.Decision.PENDING);
	}

	/**
	 * Three contenders share one assigned reference by content and all wait for its verdict without counting it as
	 * their own promotion evidence; once it is judged, each plans its own paired replays.
	 */
	// Requirement: R-D12
	@Test
	public void weakContendersShareReference()
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
		for (int completed = 0; completed < Promotion.STARTING_TRIGGERING_REPLAYS; ++completed)
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, logs, Verdict.PASS);
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, 1, working, repeated);
		TrialKey reference = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(reference));
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, formal, Verdict.FAIL);
		for (int number = 2; number <= 3; ++number)
		{
			Sha256 snapshot = Builder.snapshot("attempt" + number);
			campaign = TransitionSteps.submit(campaign, "safety", snapshot, List.of());
			for (int completed = 0; completed < Promotion.STARTING_TRIGGERING_REPLAYS; ++completed)
				campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, number, snapshot, logs, Verdict.PASS);
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, number, snapshot, formal, Verdict.FAIL);
		}
		requireThat(campaign.development().round().validationAttempts().stream().flatMap(attempt ->
			attempt.replays().stream()).filter(replay -> replay.role() == Role.WORKING_REFERENCE).count(),
			"sharedReferenceReplays").isEqualTo(1L);
		for (PromptAuthorAttempt attempt : campaign.development().round().validationAttempts())
		{
			requireThat(Promotion.decide(attempt, working, campaign.lineage().regressionSet(),
				campaign.development().testCaseOutcomes()), "pendingReference").isEqualTo(Promotion.Decision.PENDING);
			requireThat(testCases(attempt, Role.PAIRED_REGRESSION), "noPairedReplaysYet").isEmpty();
		}
		campaign = TransitionSteps.complete(campaign, reference, Verdict.PASS);
		requireThat(campaign.lineage().regressionSet().getFirst().working(), "sharedVerdict").isEqualTo(Verdict.PASS);
		for (PromptAuthorAttempt attempt : campaign.development().round().validationAttempts())
		{
			requireThat(testCases(attempt, Role.PAIRED_REGRESSION), "pairedRegressions").
				isEqualTo(Collections.nCopies(Promotion.PAIRED_REPLAY_STEP, formal));
			requireThat(AttemptSelection.passingReplays(attempt.replays()), "ownPassingReplays").isEqualTo(2L);
			requireThat(AttemptSelection.plannedReplays(attempt.replays()), "ownPlannedReplays").
				isEqualTo(3L + Promotion.PAIRED_REPLAY_STEP);
		}
	}

	/**
	 * A running working reference outlives the rejection of the sequential attempt that planned it: its outcome is
	 * recorded, and the next attempt shares it instead of planning its own.
	 */
	// Requirement: R-D3
	@Test
	public void referenceSurvivesRejectedOwner()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase formal = targeted("formatting", "formal");
		DrawnTestCase fruits = targeted("plain_list", "fruits");
		Campaign campaign = new Builder().confirmed().working(1).failure(new Failure(logs, "failure", List.of(), null,
			List.of(new DiagnosticRerun(Verdict.FAIL, "fail"), new DiagnosticRerun(Verdict.FAIL, "fail"),
				new DiagnosticRerun(Verdict.FAIL, "fail")), List.of(), Builder.FILES)).regression(formal, null).
			regression(fruits, Verdict.PASS).round(new Round(Map.of("safety", 0))).build();
		Sha256 working = campaign.development().working().snapshot();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		for (int completed = 0; completed < Promotion.STARTING_TRIGGERING_REPLAYS; ++completed)
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, logs, Verdict.PASS);
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, 1, working, formal);
		TrialKey reference = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(reference));
		// The plain_list group regresses at its first look after paired replays, while formatting waits for the reference
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, fruits, Verdict.FAIL);
		for (Verdict verdict : pairs(Promotion.PAIRED_REPLAY_STEP, 1, 2))
		{
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, fruits, verdict);
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, working, fruits, Verdict.PASS);
		}
		requireThat(campaign.development().promptAuthor(), "rejectedOwner").isNull();

		Sha256 next = Builder.snapshot("next");
		campaign = TransitionSteps.submit(campaign, "safety", next, List.of());
		for (int completed = 0; completed < Promotion.STARTING_TRIGGERING_REPLAYS; ++completed)
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 2, next, logs, Verdict.PASS);
		requireThat(testCases(campaign.development().promptAuthor(), Role.WORKING_REFERENCE),
			"additionalReferences").isEmpty();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 2, next, formal, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 2, next, fruits, Verdict.FAIL);
		requireThat(decision(campaign), "pendingReference").isEqualTo(Promotion.Decision.PENDING);
		campaign = TransitionSteps.complete(campaign, reference, Verdict.PASS);
		requireThat(campaign.lineage().regressionSet().getFirst().working(), "sharedVerdict").isEqualTo(Verdict.PASS);
		requireThat(campaign.optimization().attempts().getFirst().replays().stream().filter(replay ->
			replay.role() == Role.WORKING_REFERENCE).map(PromotionReplay::verdict).toList(), "recordedReference").
			isEqualTo(List.of(Verdict.PASS));
		requireThat(campaign.development().promptAuthor(), "nextAttempt").isNotNull();
	}

	/**
	 * Verifies that an attempt is pending while a needed replay has no outcome.
	 */
	// Requirement: R-D3
	@Test
	public void unjudgedRunIsPending()
	{
		// Requirement: R-D3
		DrawnTestCase logs = targeted("safety", "logs");
		requireThat(decide(attempt(replay(Role.TRIGGERING, logs, Verdict.PASS), replay(Role.TRIGGERING, logs)),
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
		requireThat(decide(attempt(replay(Role.TRIGGERING, logs, Verdict.PASS),
			replay(Role.TRIGGERING, logs, Verdict.PASS), replay(Role.TRIGGERING, cache, Verdict.FAIL),
			replay(Role.TRIGGERING, cache, Verdict.PASS)), List.of()), "decision").
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
		requireThat(decide(attempt(replay(Role.TRIGGERING, logs, Verdict.FAIL),
			replay(Role.TRIGGERING, logs), replay(Role.TRIGGERING, cache), replay(Role.TRIGGERING, cache)), List.of()),
			"possibleFix").isEqualTo(Promotion.Decision.PENDING);
		requireThat(decide(attempt(replay(Role.TRIGGERING, logs, Verdict.FAIL),
			replay(Role.TRIGGERING, logs), replay(Role.TRIGGERING, cache, Verdict.FAIL), replay(Role.TRIGGERING, cache),
			replay(Role.REGRESSION, targeted("formatting", "formal"))), List.of()), "noPossibleFix").
			isEqualTo(Promotion.Decision.REJECT);
	}

	/**
	 * A partial fix is rejected as soon as a group regresses, even while another triggering test case still runs.
	 */
	// Requirement: R-D3
	@Test
	public void regressionRejectsPartialFix()
	{
		DrawnTestCase cache = targeted("safety", "cache");
		DrawnTestCase formal = targeted("formatting", "formal");
		requireThat(decide(pairedAttempt(List.of(replay(Role.TRIGGERING, cache), replay(Role.TRIGGERING, cache)),
			pairs(5, 1, 2), pairs(5)), List.of(new RegressionTestCase(formal, Verdict.PASS, false))),
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
		PromotionReplay first = replay(Role.TRIGGERING, logs, Verdict.PASS);
		PromotionReplay second = replay(Role.TRIGGERING, logs, Verdict.PASS);
		requireThat(decide(attempt(first, second, replay(Role.TRIGGERING, cache, Verdict.FAIL),
			replay(Role.TRIGGERING, cache)), List.of()), "triggeringPending").isEqualTo(Promotion.Decision.PENDING);
		requireThat(decide(attempt(first, second, replay(Role.TRIGGERING, cache, Verdict.FAIL),
			replay(Role.TRIGGERING, cache, Verdict.FAIL), replay(Role.REGRESSION, formal)), List.of()),
			"regressionPending").isEqualTo(Promotion.Decision.PENDING);
	}

	/**
	 * Two passing replays fix the same trial even when its draw position differs.
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
		requireThat(decide(attempt(replay(Role.TRIGGERING, logs, Verdict.PASS),
			replay(Role.TRIGGERING, repeated, Verdict.PASS), replay(Role.TRIGGERING, cache, Verdict.FAIL),
			replay(Role.TRIGGERING, cache, Verdict.FAIL)), List.of()), "decision").isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * One passing replay cannot establish a fixed test case, and an empty plan cannot be promoted.
	 */
	// Requirement: R-D3
	@Test
	public void requiresBothTriggeringPasses()
	{
		requireThat(decide(attempt(replay(Role.TRIGGERING, targeted("safety", "logs"), Verdict.PASS)),
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
	 * Verifies that promotion replays never count as representative evidence, and that a promoted attempt becomes the
	 * working candidate with new counts.
	 */
	// Requirement: R-D3
	@Test
	public void promotionReplaysNeverCount()
	{
		// Requirement: R-D3
		Campaign campaign = inRound();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
		requireThat(promptAuthor.replays(), "replays").size().isEqualTo(2);
		DrawnTestCase logs = promptAuthor.replays().getFirst().testCase();

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
	 * Verifies that a replay's outcome is recorded on that replay, even when a replay of another snapshot has the same
	 * cell and sequence number.
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
			regression(formal, null).round(new Round(Map.of("safety", 0))).build();
		Sha256 working = campaign.development().working().snapshot();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		for (int completed = 0; completed < Promotion.STARTING_TRIGGERING_REPLAYS; ++completed)
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, logs, Verdict.PASS);
		PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
		requireThat(promptAuthor.replays().stream().map(PromotionReplay::role).toList(), "roles").
			isEqualTo(List.of(Role.TRIGGERING, Role.TRIGGERING, Role.REGRESSION, Role.WORKING_REFERENCE));
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, 1, ATTEMPT, formal);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, working, formal, Verdict.FAIL);

		List<PromotionReplay> replays = campaign.development().promptAuthor().replays();
		requireThat(replays.get(2).trial().sequence(), "sameSequence").isEqualTo(replays.get(3).trial().sequence());
		requireThat(replays.get(2).verdict(), "regression").isNull();
		requireThat(replays.get(3).verdict(), "reference").isEqualTo(Verdict.FAIL);
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
		DrawnTestCase logs = promptAuthor.replays().getFirst().testCase();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, promptAuthor.number(), ATTEMPT, logs,
			Verdict.FAIL);

		requireThat(campaign.development().working().number(), "working").isEqualTo(1);
		requireThat(campaign.development().promptAuthor(), "prompt_author").isNull();
		requireThat(campaign.development().round().attempts(), "completedAttempts").isEqualTo(Map.of("safety", 1));
		requireThat(campaign.promptAuthorAttempts(), "promptAuthorAttempts").isEqualTo(1);
	}

	/**
	 * Verifies that an attempt that leaves the working candidate unchanged is rejected without replays.
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
	 * Verifies that the outcome of an earlier prompt author attempt's replay is dropped.
	 */
	// Requirement: R-D3
	@Test
	public void staleRunIsDropped()
	{
		// Requirement: R-D3
		Campaign campaign = inRound();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
		DrawnTestCase logs = promptAuthor.replays().getFirst().testCase();
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

	/**
	 * Returns a sequential round on {@code safety} whose working candidate failed {@code logs} four times and passed
	 * every regression test case.
	 *
	 * @param regressions the regression test cases that the working candidate passed, in regression set order
	 * @return the campaign
	 */
	static Campaign regressionRound(DrawnTestCase... regressions)
	{
		DrawnTestCase logs = targeted("safety", "logs");
		Builder builder = new Builder().confirmed().working(1).development("safety", 60, 1).
			development("formatting", 61, 0).failure(new Failure(logs, "failure", List.of(), null, List.of(
				new DiagnosticRerun(Verdict.FAIL, "fail"), new DiagnosticRerun(Verdict.FAIL, "fail"),
				new DiagnosticRerun(Verdict.FAIL, "fail")), List.of(), Builder.FILES)).regression(logs, Verdict.FAIL);
		for (DrawnTestCase regression : regressions)
			builder.regression(regression, Verdict.PASS);
		return builder.round(new Round(Map.of("safety", 0))).build();
	}

	/**
	 * Returns the test cases of an attempt's replays with a role, in replay order.
	 *
	 * @param attempt the attempt
	 * @param role the role
	 * @return the test cases
	 */
	private static List<DrawnTestCase> testCases(PromptAuthorAttempt attempt, Role role)
	{
		return attempt.replays().stream().filter(replay -> replay.role() == role).map(PromotionReplay::testCase).toList();
	}

	/**
	 * Returns the decision of the campaign's sequential prompt author attempt.
	 *
	 * @param campaign the campaign
	 * @return the decision
	 */
	private static Promotion.Decision decision(Campaign campaign)
	{
		return Promotion.decide(campaign.development().promptAuthor(), campaign.development().working().snapshot(),
			campaign.lineage().regressionSet(), campaign.development().testCaseOutcomes());
	}

	/**
	 * Runs the attempt's next paired regression replay and the working candidate's paired replay on {@code formal}.
	 *
	 * @param campaign the campaign
	 * @param attempt the attempt's verdict
	 * @return the updated campaign
	 */
	private static Campaign pair(Campaign campaign, Verdict attempt)
	{
		DrawnTestCase formal = targeted("formatting", "formal");
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, formal, attempt);
		return TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, campaign.development().working().snapshot(),
			formal, Verdict.PASS);
	}

	/**
	 * Runs an attempt's triggering test case to a fix and its only regression test case to one failure that the
	 * working candidate passed.
	 *
	 * @return the campaign with the first pass complete
	 */
	private static Campaign oneRegressionFailure()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase formal = targeted("formatting", "formal");
		Campaign campaign = TransitionSteps.submit(regressionRound(formal), "safety", ATTEMPT, List.of());
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, logs, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, logs, Verdict.PASS);
		return TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, formal, Verdict.FAIL);
	}

	/**
	 * One regression failure that the working candidate's paired replays show to be likely for it does not reject a
	 * fix: the attempt is promoted after 15 paired replays, none of them counts as representative evidence, and the new
	 * working candidate's outcome on the test case is its latest replay there, so the early failure does not block the
	 * commit gate.
	 */
	// Requirement: R-D3
	@Test
	public void likelyFailurePromotedAfterPairedReplays()
	{
		Campaign campaign = oneRegressionFailure();
		DrawnTestCase formal = targeted("formatting", "formal");
		PromptAuthorAttempt attempt = campaign.development().promptAuthor();
		requireThat(attempt, "inProgress").isNotNull();
		requireThat(testCases(attempt, Role.PAIRED_REGRESSION), "pairedAttemptReplays").
			isEqualTo(Collections.nCopies(5, formal));
		requireThat(testCases(attempt, Role.PAIRED_WORKING), "pairedWorkingReplays").
			isEqualTo(Collections.nCopies(5, formal));
		for (int pair = 1; pair <= 15; ++pair)
		{
			campaign = pair(campaign, Verdict.PASS);
			if (pair < 15)
				requireThat(decision(campaign), "pending" + pair).isEqualTo(Promotion.Decision.PENDING);
		}
		requireThat(decision(campaign), "promoted").isEqualTo(Promotion.Decision.PROMOTE);
		requireThat(testCases(campaign.development().promptAuthor(), Role.PAIRED_REGRESSION), "plannedPairedReplays").
			size().isEqualTo(15);
		requireThat(campaign.development().count("formatting"), "uncounted").isEqualTo(Builder.counts(61, 0));

		campaign = Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(ATTEMPT));
		requireThat(campaign.development().working().snapshot(), "promotedWorking").isEqualTo(ATTEMPT);
		RegressionTestCase promoted = campaign.lineage().regressionSet().stream().
			filter(regression -> regression.testCase().equals(formal)).findFirst().orElseThrow();
		requireThat(promoted.working(), "latestOutcome").isEqualTo(Verdict.PASS);
	}

	/**
	 * Regression failures that stay unlikely for the working candidate reject the attempt at the look after 10
	 * paired replays, not at the earlier paired replay where they first became unlikely.
	 */
	// Requirement: R-D3
	@Test
	public void unlikelyRegressionFailuresRejectAtLook()
	{
		Campaign campaign = oneRegressionFailure();
		List<Verdict> attempt = new ArrayList<>(Collections.nCopies(10, Verdict.PASS));
		attempt.set(0, Verdict.FAIL);
		attempt.set(5, Verdict.FAIL);
		for (int pair = 1; pair <= 9; ++pair)
		{
			campaign = pair(campaign, attempt.get(pair - 1));
			requireThat(campaign.development().promptAuthor(), "noLook" + pair).isNotNull();
		}
		campaign = pair(campaign, attempt.get(9));
		requireThat(campaign.development().promptAuthor(), "rejected").isNull();
		AttemptResult result = campaign.optimization().attempts().getFirst();
		requireThat(result.accepted(), "accepted").isFalse();
	}

	/**
	 * Each prompt test's regression replays are judged on their own: three failures in four replays would be unlikely if
	 * pooled across both prompt tests, but neither prompt test's failures are, so each plans paired replays that cycle
	 * through its own test cases, starting with those the attempt failed.
	 */
	// Requirement: R-D3
	@Test
	public void groupsAreJudgedSeparately()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase formal = targeted("formatting", "formal");
		DrawnTestCase casual = targeted("formatting", "casual");
		DrawnTestCase tools = targeted("plain_list", "tools");
		DrawnTestCase fruits = targeted("plain_list", "fruits");
		Campaign campaign = TransitionSteps.submit(regressionRound(formal, casual, tools, fruits),
			"safety", ATTEMPT, List.of());
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, logs, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, logs, Verdict.PASS);
		Map<DrawnTestCase, Verdict> firstPass = new LinkedHashMap<>();
		firstPass.put(formal, Verdict.FAIL);
		firstPass.put(casual, Verdict.FAIL);
		firstPass.put(tools, Verdict.PASS);
		firstPass.put(fruits, Verdict.FAIL);
		for (Map.Entry<DrawnTestCase, Verdict> entry : firstPass.entrySet())
		{
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, entry.getKey(),
				entry.getValue());
		}
		requireThat(decision(campaign), "separateGroups").isEqualTo(Promotion.Decision.PENDING);
		PromptAuthorAttempt attempt = campaign.development().promptAuthor();
		requireThat(testCases(attempt, Role.PAIRED_REGRESSION), "pairedAttemptReplays").isEqualTo(List.of(
			formal, casual, formal, casual, formal, fruits, tools, fruits, tools, fruits));
		requireThat(testCases(attempt, Role.PAIRED_WORKING), "pairedWorkingReplays").isEqualTo(List.of(
			formal, casual, formal, casual, formal, fruits, tools, fruits, tools, fruits));
	}

	/**
	 * Returns an attempt whose triggering test case is fixed and whose only regression test case failed its first
	 * pass, followed by paired replays.
	 *
	 * @param verdicts the attempt's paired replay verdicts; each has a passing paired working paired replay
	 * @return the attempt
	 */
	private static PromptAuthorAttempt pairedAttempt(List<Verdict> verdicts)
	{
		return pairedAttempt(List.of(), verdicts, Collections.nCopies(verdicts.size(), Verdict.PASS));
	}

	/**
	 * Returns an attempt whose triggering test case is fixed and whose only regression test case failed its first
	 * pass, followed by other replays and then paired replays.
	 *
	 * @param others the replays after the first pass
	 * @param attempt the attempt's paired replay verdicts
	 * @param working the verdicts of the working candidate's paired replays
	 * @return the attempt
	 */
	private static PromptAuthorAttempt pairedAttempt(List<PromotionReplay> others, List<Verdict> attempt,
		List<Verdict> working)
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase formal = targeted("formatting", "formal");
		List<PromotionReplay> replays = new ArrayList<>(List.of(replay(Role.TRIGGERING, logs, Verdict.PASS),
			replay(Role.TRIGGERING, logs, Verdict.PASS), replay(Role.REGRESSION, formal, Verdict.FAIL)));
		replays.addAll(others);
		for (int pair = 0; pair < attempt.size(); ++pair)
		{
			replays.add(replay(Role.PAIRED_REGRESSION, formal, attempt.get(pair)));
			replays.add(replay(Role.PAIRED_WORKING, formal, working.get(pair)));
		}
		return attempt(replays.toArray(PromotionReplay[]::new));
	}

	/**
	 * Returns paired replay verdicts that fail at the given one-based positions and pass otherwise.
	 *
	 * @param count the number of paired replays
	 * @param failures the failing positions
	 * @return the verdicts
	 */
	private static List<Verdict> pairs(int count, int... failures)
	{
		List<Verdict> verdicts = new ArrayList<>(Collections.nCopies(count, Verdict.PASS));
		for (int failure : failures)
			verdicts.set(failure - 1, Verdict.FAIL);
		return verdicts;
	}

	/**
	 * The regression check looks only after the first pass and after 5, 10 and 15 paired replays, and a group that has
	 * not regressed at the look after 15 paired replays passes.
	 */
	// Requirement: R-D3
	@Test
	public void looksEveryFivePairedReplaysUpToFifteen()
	{
		List<RegressionTestCase> regressionSet = List.of(new RegressionTestCase(targeted("formatting", "formal"),
			Verdict.PASS, false));
		requireThat(decide(pairedAttempt(List.of()), regressionSet), "firstPass").
			isEqualTo(Promotion.Decision.PENDING);
		// Three failures in 7 replays would be unlikely, but 6 paired replays is not a look
		requireThat(decide(pairedAttempt(pairs(6, 1, 6)), regressionSet), "betweenLooks").
			isEqualTo(Promotion.Decision.PENDING);
		requireThat(decide(pairedAttempt(pairs(10, 1, 6)), regressionSet), "lookAtTen").
			isEqualTo(Promotion.Decision.REJECT);
		requireThat(decide(pairedAttempt(pairs(5, 1, 2)), regressionSet), "lookAtFive").
			isEqualTo(Promotion.Decision.REJECT);
		requireThat(decide(pairedAttempt(pairs(14, 7, 12)), regressionSet), "beforeLastLook").
			isEqualTo(Promotion.Decision.PENDING);
		requireThat(decide(pairedAttempt(pairs(15, 7, 12)), regressionSet), "lastLookPasses").
			isEqualTo(Promotion.Decision.PROMOTE);
		requireThat(decide(pairedAttempt(pairs(15, 11, 12, 13)), regressionSet), "lastLookRejects").
			isEqualTo(Promotion.Decision.REJECT);
	}

	/**
	 * An attempt plans its regression replays only after a triggering test case passes twice without a failure, so an
	 * attempt that fails every triggering test case is rejected without any regression replay.
	 */
	// Requirement: R-D3
	@Test
	public void triggeringFailureSkipsRegression()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase formal = targeted("formatting", "formal");
		Campaign campaign = TransitionSteps.submit(regressionRound(formal), "safety", ATTEMPT, List.of());
		requireThat(testCases(campaign.development().promptAuthor(), Role.REGRESSION), "notYetPlanned").isEmpty();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, logs, Verdict.PASS);
		requireThat(testCases(campaign.development().promptAuthor(), Role.REGRESSION), "onePass").isEmpty();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, logs, Verdict.FAIL);
		requireThat(campaign.development().promptAuthor(), "rejected").isNull();
		requireThat(campaign.optimization().attempts().getFirst().replays().stream().map(PromotionReplay::role).toList(),
			"rejectedReplays").isEqualTo(List.of(Role.TRIGGERING, Role.TRIGGERING));

		campaign = TransitionSteps.submit(campaign, "safety", Builder.snapshot("next"), List.of());
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 2, Builder.snapshot("next"), logs, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 2, Builder.snapshot("next"), logs, Verdict.PASS);
		requireThat(testCases(campaign.development().promptAuthor(), Role.REGRESSION), "afterStreak").
			isEqualTo(List.of(formal));
	}

	/**
	 * A weak contender stops when it is rejected: its unstarted replays are cancelled and none is planned, its running
	 * replay finishes and is recorded without changing the decision, the status shows the replays it ran and its early
	 * rejection, and the round still selects its winner once every contender is decided.
	 */
	// Requirement: R-D12
	@Test
	public void rejectedContenderStopsEarly()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DrawnTestCase cache = targeted("safety", "cache");
		DrawnTestCase formal = targeted("formatting", "formal");
		List<DiagnosticRerun> failed = List.of(new DiagnosticRerun(Verdict.FAIL, "fail"),
			new DiagnosticRerun(Verdict.FAIL, "fail"), new DiagnosticRerun(Verdict.FAIL, "fail"));
		Campaign campaign = new Builder().confirmed().working(1).
			development("safety", 0, EditOrContinue.failureLimit(Contract.DEFAULT_MINIMUM_PASS_RATE) + 1).
			failure(new Failure(logs, "failure", List.of(), null, failed, List.of(), Builder.FILES)).
			failure(new Failure(cache, "failure", List.of(), null, failed, List.of(), Builder.FILES)).
			regression(formal, Verdict.PASS).round(new Round(Map.of("safety", 0), true, Map.of(), List.of())).build();
		for (int number = 1; number <= 3; ++number)
			campaign = TransitionSteps.submit(campaign, "safety", Builder.snapshot("attempt" + number), List.of());
		Sha256 first = Builder.snapshot("attempt1");
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, 1, first, logs);
		TrialKey running = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(running));
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, 1, first, cache);
		TrialKey queued = TransitionSteps.last(campaign);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, first, logs, Verdict.FAIL);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, first, cache, Verdict.FAIL);

		requireThat(campaign.development().round(), "openRound").isNotNull();
		requireThat(campaign.optimization().attempts().stream().map(AttemptResult::number).toList(),
			"retainedAtRejection").isEqualTo(List.of(1));
		List<TrialKey> remaining = campaign.trials().stream().map(LogicalTrial::key).toList();
		requireThat(remaining, "cancelled").doesNotContain(queued);
		requireThat(remaining, "remaining").isEqualTo(List.of(running));
		for (Admission admission : Scheduler.plan(campaign, Instant.parse("2026-10-01T12:00:00Z"), 64))
		{
			if (admission instanceof Admission.Assign assign && assign.purpose() == Purpose.PROMOTION)
				requireThat(assign.series(), "plannedSeries").isNotEqualTo(1);
		}
		campaign = TransitionSteps.complete(campaign, running, Verdict.PASS);
		AttemptResult rejected = campaign.optimization().attempts().getFirst();
		requireThat(rejected.accepted(), "stillRejected").isFalse();
		requireThat(rejected.replays().stream().filter(replay -> replay.verdict() != null).count(), "recordedReplays").
			isEqualTo(3L);

		for (int number = 2; number <= 3; ++number)
		{
			Sha256 snapshot = Builder.snapshot("attempt" + number);
			for (int replay = 0; replay < Promotion.STARTING_TRIGGERING_REPLAYS; ++replay)
				campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, number, snapshot, logs, Verdict.PASS);
			Verdict verdict = Verdict.FAIL;
			if (number == 3)
				verdict = Verdict.PASS;
			for (int replay = 0; replay < Promotion.STARTING_TRIGGERING_REPLAYS; ++replay)
				campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, number, snapshot, cache, verdict);
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, number, snapshot, formal, Verdict.PASS);
		}
		requireThat(campaign.development().promptAuthor().number(), "winner").isEqualTo(3);
		JsonNode attempts = StatusReport.of(campaign, ReportEvidence.NONE).toJson(JsonMapper.builder().build()).
			get("prompt_author_attempts");
		requireThat(attempts.get(0).get("total_replays").asInt(), "ranReplays").isEqualTo(3);
		requireThat(attempts.get(0).get("rejected_early").asBoolean(), "rejectedEarly").isTrue();
		requireThat(attempts.get(1).get("rejected_early").asBoolean(), "decidedLate").isFalse();
	}
}
