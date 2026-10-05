package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.campaign.Builder.targeted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.PromotionRun.Role;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link Promotion} and the author attempts that it judges.
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
	 * @return passes in four outcomes, required runs, and whether the instance is fixed
	 */
	@DataProvider
	public static Object[][] workingRecords()
	{
		return new Object[][] {{0, 2, true}, {1, 3, true}, {2, 4, true}, {3, 4, false}};
	}

	/**
	 * Plans only the extra runs needed by the working candidate's recorded outcomes on a fixed instance.
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
		TrialInstance instance = campaign.development().author().runs().getFirst().instance();
		requireThat(campaign.development().author().runs(), "startingRuns").size().isEqualTo(2);
		for (int completed = 0; completed < requiredRuns; ++completed)
		{
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, instance, Verdict.PASS);
			if (completed + 1 < requiredRuns)
			{
				AuthorAttempt author = campaign.development().author();
				requireThat(author.runs(), "plannedRuns").size().isEqualTo(Math.max(2, completed + 2));
				requireThat(Promotion.decide(author, campaign.lineage().regressionSet(),
					campaign.development().instanceOutcomes()), "decision").
					isEqualTo(Promotion.Decision.PENDING);
			}
		}
		if (fixed)
		{
			requireThat(Promotion.decide(campaign.development().author(), campaign.lineage().regressionSet(),
				campaign.development().instanceOutcomes()),
				"fixed").isEqualTo(Promotion.Decision.PROMOTE);
		}
		else
			requireThat(campaign.development().author(), "rejected").isNull();
	}

	/**
	 * A failure on an extended streak stops further runs and rejects the only possible fix.
	 */
	// Requirement: R-D3
	@Test
	public void failureStopsExtendedStreak()
	{
		Campaign campaign = TransitionSteps.submit(streakRound(2), "safety", ATTEMPT, List.of());
		TrialInstance instance = campaign.development().author().runs().getFirst().instance();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, instance, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, instance, Verdict.PASS);
		requireThat(campaign.development().author().runs(), "extension").size().isEqualTo(3);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, instance, Verdict.FAIL);
		requireThat(campaign.development().author(), "rejected").isNull();
		requireThat(campaign.development().working().number(), "working").isEqualTo(1);
	}

	/**
	 * A representative pass on the same trial content contributes to the working record used for a streak.
	 */
	// Requirement: R-D3
	@Test
	public void adoptedSameContentPassExtendsStreak()
	{
		Generator generator = new Generator(ContractFixtures.contract());
		TrialInstance representative = generator.draw("safety", DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		TrialInstance instance = generator.markTargeted(representative);
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 60, 1).
			failure(instance).regression(instance, Verdict.FAIL).round(new Round(Map.of("safety", 0))).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.PASS);
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		for (int completed = 0; completed < 3; ++completed)
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, instance, Verdict.PASS);
		requireThat(campaign.development().author().runs(), "fourthRun").size().isEqualTo(4);
		requireThat(Promotion.decide(campaign.development().author(), campaign.lineage().regressionSet(),
				campaign.development().instanceOutcomes()),
			"pending").isEqualTo(Promotion.Decision.PENDING);
	}

	/**
	 * Returns a round whose triggering instance has four working-candidate outcomes.
	 *
	 * @param passes the passing diagnostic outcomes
	 * @return the campaign
	 */
	static Campaign streakRound(int passes)
	{
		TrialInstance instance = targeted("safety", "logs");
		List<DiagnosticRerun> reruns = new ArrayList<>();
		for (int rerun = 0; rerun < 3; ++rerun)
		{
			Verdict verdict = Verdict.FAIL;
			if (rerun < passes)
				verdict = Verdict.PASS;
			reruns.add(new DiagnosticRerun(verdict, "diagnostic outcome"));
		}
		return new Builder().confirmed().working(1).development("safety", 60, 1).
			development("formatting", 61, 0).failure(new Failure(instance, "representative failure", List.of(), null, reruns,
				List.of())).regression(instance, Verdict.FAIL).round(new Round(Map.of("safety", 0))).build();
	}

	/**
	 * Returns an unassigned run.
	 *
	 * @param role the run's role
	 * @param instance the instance
	 * @return the run
	 */
	private static PromotionRun run(Role role, TrialInstance instance)
	{
		return new PromotionRun(role, instance, null, null);
	}

	/**
	 * Returns a judged run.
	 *
	 * @param role the run's role
	 * @param instance the instance
	 * @param verdict the run's outcome
	 * @return the run
	 */
	private static PromotionRun run(Role role, TrialInstance instance, Verdict verdict)
	{
		TrialKey key = new TrialKey(Purpose.PROMOTION, 1, ATTEMPT, instance.categoryId(), 1);
		return new PromotionRun(role, instance, key, verdict);
	}

	/**
	 * Returns an author attempt on {@code safety}.
	 *
	 * @param runs the attempt's runs
	 * @return the attempt
	 */
	private static AuthorAttempt attempt(PromotionRun... runs)
	{
		return new AuthorAttempt(1, "safety", ATTEMPT, List.of(runs), Minimality.start(List.of(), ATTEMPT));
	}

	/**
	 * Judges synthetic run evidence against a working candidate with four failing outcomes per triggering instance.
	 *
	 * @param attempt the tested attempt
	 * @param regressions its regression evidence
	 * @return the production promotion decision
	 */
	private static Promotion.Decision decide(AuthorAttempt attempt, List<RegressionInstance> regressions)
	{
		List<InstanceOutcomes> records = new ArrayList<>();
		for (PromotionRun run : attempt.runs())
		{
			if (run.role() == Role.TRIGGERING && records.stream().noneMatch(record ->
				Promotion.isSameContent(record.instance(), run.instance())))
			{
				records.add(new InstanceOutcomes(run.instance(), 0, 4));
			}
		}
		return Promotion.decide(attempt, regressions, records);
	}

	/**
	 * Verifies that the plan runs each triggering instance twice, each other regression instance once, and the
	 * working candidate once on each regression instance that it has no result on.
	 */
	// Requirement: R-D3
	@Test
	public void planRunsTriggeringTwiceAndRegressionOnce()
	{
		// Requirement: R-D3
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance cache = targeted("safety", "cache");
		TrialInstance formal = targeted("formatting", "formal");
		List<RegressionInstance> regressionSet = List.of(new RegressionInstance(logs, Verdict.FAIL),
			new RegressionInstance(cache, Verdict.PASS), new RegressionInstance(formal, null));
		List<PromotionRun> plan = Promotion.plan("safety", List.of(logs, formal), regressionSet,
			new Generator(ContractFixtures.contract()));

		requireThat(plan, "plan").isEqualTo(List.of(run(Role.TRIGGERING, logs), run(Role.TRIGGERING, logs),
			run(Role.REGRESSION, cache), run(Role.REGRESSION, formal), run(Role.WORKING_REFERENCE, formal)));
	}

	/**
	 * Verifies that an attempt is promoted when every triggering run passes and it fails no regression instance
	 * that the working candidate passes.
	 */
	// Requirement: R-D3
	@Test
	public void passingAttemptIsPromoted()
	{
		// Requirement: R-D3
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance cache = targeted("safety", "cache");
		List<RegressionInstance> regressionSet = List.of(new RegressionInstance(cache, Verdict.PASS));
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS),
			run(Role.TRIGGERING, logs, Verdict.PASS), run(Role.REGRESSION, cache, Verdict.PASS)), regressionSet),
			"decision").isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * Verifies that a failing run on the only triggering instance rejects the attempt.
	 */
	// Requirement: R-D3
	@Test
	public void onlyTriggeringInstanceFails()
	{
		TrialInstance logs = targeted("safety", "logs");
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
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance cache = targeted("safety", "cache");
		TrialInstance tmp = targeted("safety", "tmp");
		List<RegressionInstance> regressionSet = List.of(new RegressionInstance(cache, Verdict.PASS),
			new RegressionInstance(tmp, Verdict.FAIL));
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
	 * Verifies that the working candidate's reference run decides a regression instance that it had no result on.
	 */
	// Requirement: R-D3
	@Test
	public void referenceRunDecidesUnknownWorkingResult()
	{
		// Requirement: R-D3
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance formal = targeted("formatting", "formal");
		List<RegressionInstance> regressionSet = List.of(new RegressionInstance(formal, null));
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
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance formal = targeted("formatting", "formal");
		TrialInstance repeated = new TrialInstance(formal.cellId(), formal.categoryId(), formal.testId(),
			formal.choices(), formal.turns(), formal.expectedBehaviour(), formal.generator(), formal.streamKind(),
			formal.streamPosition() + 1, true);
		// Failures above the limit keep a round without a winner, so its contenders remain inspectable
		Campaign campaign = new Builder().confirmed().working(1).
			development("safety", 0, EditOrContinue.FAILURE_LIMIT + 1).failure(new Failure(logs, "failure", List.of(),
				null, List.of(new DiagnosticRerun(Verdict.FAIL, "fail"), new DiagnosticRerun(Verdict.FAIL, "fail"),
					new DiagnosticRerun(Verdict.FAIL, "fail")), List.of())).regression(formal, null).
			round(new Round(Map.of("safety", 0), true, Map.of(), List.of())).build();
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
		for (AuthorAttempt attempt : campaign.development().round().validationAttempts())
		{
			for (int completed = 0; completed < Promotion.STARTING_TRIGGERING_RUNS; ++completed)
				campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, attempt.number(), attempt.snapshot(),
					logs, Verdict.PASS);
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, attempt.number(), attempt.snapshot(),
				formal, Verdict.FAIL);
			AuthorAttempt pending = campaign.development().round().validationAttempts().stream().
				filter(candidate -> candidate.number() == attempt.number()).findFirst().orElseThrow();
			requireThat(Promotion.decide(pending, campaign.lineage().regressionSet(),
				campaign.development().instanceOutcomes()), "pendingReference").isEqualTo(Promotion.Decision.PENDING);
		}
		campaign = TransitionSteps.complete(campaign, reference, verdict);
		for (AuthorAttempt attempt : campaign.development().round().validationAttempts())
		{
			requireThat(Promotion.decide(attempt, campaign.lineage().regressionSet(),
				campaign.development().instanceOutcomes()), "sharedDecision").isEqualTo(decision);
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
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance formal = targeted("formatting", "formal");
		Campaign campaign = new Builder().confirmed().working(1).failure(new Failure(logs, "failure", List.of(), null,
			List.of(new DiagnosticRerun(Verdict.FAIL, "fail"), new DiagnosticRerun(Verdict.FAIL, "fail"),
				new DiagnosticRerun(Verdict.FAIL, "fail")), List.of())).regression(formal, null).
			round(new Round(Map.of("safety", 0))).build();
		Sha256 working = campaign.development().working().snapshot();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, 1, working, formal);
		TrialKey reference = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(reference));
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, logs, Verdict.FAIL);
		requireThat(campaign.development().author(), "rejectedOwner").isNull();

		Sha256 next = Builder.snapshot("next");
		campaign = TransitionSteps.submit(campaign, "safety", next, List.of());
		requireThat(campaign.development().author().runs().stream().filter(run ->
			run.role() == Role.WORKING_REFERENCE).count(), "additionalReferences").isEqualTo(0L);
		for (int completed = 0; completed < Promotion.STARTING_TRIGGERING_RUNS; ++completed)
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 2, next, logs, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 2, next, formal, Verdict.FAIL);
		requireThat(Promotion.decide(campaign.development().author(), campaign.lineage().regressionSet(),
			campaign.development().instanceOutcomes()), "pendingReference").isEqualTo(Promotion.Decision.PENDING);
		campaign = TransitionSteps.complete(campaign, reference, Verdict.PASS);
		requireThat(campaign.development().author(), "regressingAttempt").isNull();
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
		TrialInstance logs = targeted("safety", "logs");
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS), run(Role.TRIGGERING, logs)),
			List.of()), "decision").isEqualTo(Promotion.Decision.PENDING);
	}

	/**
	 * An attempt fixing one of two triggering instances is accepted without a regression.
	 */
	// Requirement: R-D3
	@Test
	public void partialFixIsPromoted()
	{
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance cache = targeted("safety", "cache");
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS),
			run(Role.TRIGGERING, logs, Verdict.PASS), run(Role.TRIGGERING, cache, Verdict.FAIL),
			run(Role.TRIGGERING, cache, Verdict.PASS)), List.of()), "decision").
			isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * Rejects once each triggering instance has a failure, while another fix remains possible until then.
	 */
	// Requirement: R-D3
	@Test
	public void rejectsWhenEveryFixIsImpossible()
	{
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance cache = targeted("safety", "cache");
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.FAIL),
			run(Role.TRIGGERING, logs), run(Role.TRIGGERING, cache), run(Role.TRIGGERING, cache)), List.of()),
			"possibleFix").isEqualTo(Promotion.Decision.PENDING);
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.FAIL),
			run(Role.TRIGGERING, logs), run(Role.TRIGGERING, cache, Verdict.FAIL), run(Role.TRIGGERING, cache),
			run(Role.REGRESSION, targeted("formatting", "formal"))), List.of()), "noPossibleFix").
			isEqualTo(Promotion.Decision.REJECT);
	}

	/**
	 * A partial fix cannot regress an instance that the working candidate passes.
	 */
	// Requirement: R-D3
	@Test
	public void regressionRejectsPartialFix()
	{
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance cache = targeted("safety", "cache");
		TrialInstance formal = targeted("formatting", "formal");
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS),
			run(Role.TRIGGERING, logs, Verdict.PASS), run(Role.TRIGGERING, cache), run(Role.TRIGGERING, cache),
			run(Role.REGRESSION, formal, Verdict.FAIL)), List.of(new RegressionInstance(formal, Verdict.PASS))),
			"decision").isEqualTo(Promotion.Decision.REJECT);
	}

	/**
	 * A partial fix waits for its remaining triggering and regression outcomes.
	 */
	// Requirement: R-D3
	@Test
	public void partialFixWaitsForNeededOutcomes()
	{
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance cache = targeted("safety", "cache");
		TrialInstance formal = targeted("formatting", "formal");
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
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance repeated = new TrialInstance(logs.cellId(), logs.categoryId(), logs.testId(), logs.choices(),
			logs.turns(), logs.expectedBehaviour(), logs.generator(), logs.streamKind(), logs.streamPosition() + 1,
			true);
		TrialInstance cache = targeted("safety", "cache");
		requireThat(decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS),
			run(Role.TRIGGERING, repeated, Verdict.PASS), run(Role.TRIGGERING, cache, Verdict.FAIL),
			run(Role.TRIGGERING, cache, Verdict.FAIL)), List.of()), "decision").isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * One passing run cannot establish a fixed instance, and an empty plan cannot be promoted.
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
		TrialInstance logs = targeted("safety", "logs");
		return new Builder().confirmed().working(1).development("safety", 60, 1).
			development("formatting", 61, 0).failure(new Failure(logs, "failure", List.of(), null, List.of(
				new DiagnosticRerun(Verdict.FAIL, "fail"), new DiagnosticRerun(Verdict.FAIL, "fail"),
				new DiagnosticRerun(Verdict.FAIL, "fail")), List.of())).regression(logs, Verdict.FAIL).
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
		AuthorAttempt author = campaign.development().author();
		requireThat(author.runs(), "runs").size().isEqualTo(2);
		TrialInstance logs = author.runs().getFirst().instance();

		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, logs, Verdict.PASS);
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(Builder.counts(60, 1));
		requireThat(campaign.development().working().number(), "working").isEqualTo(1);

		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, logs, Verdict.PASS);
		campaign = Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(ATTEMPT));
		requireThat(campaign.development().working(), "promoted").isEqualTo(new Candidate(2, ATTEMPT));
		requireThat(campaign.development().count("safety"), "newCounts").isEqualTo(CellCounts.EMPTY);
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(campaign.lineage().regressionSet(), "regressionSet").
			isEqualTo(List.of(new RegressionInstance(logs, Verdict.PASS)));
	}

	/**
	 * Verifies that a run's outcome is recorded on that run, even when a run of another category or snapshot has the
	 * same sequence number.
	 */
	// Requirement: R-D3
	@Test
	public void outcomeReachesItsOwnRun()
	{
		// Requirement: R-D3
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance formal = targeted("formatting", "formal");
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 60, 1).
			development("formatting", 61, 0).failure(new Failure(logs, "failure", List.of(), null, List.of(
				new DiagnosticRerun(Verdict.FAIL, "fail"), new DiagnosticRerun(Verdict.FAIL, "fail"),
				new DiagnosticRerun(Verdict.FAIL, "fail")), List.of())).regression(logs, Verdict.FAIL).
			regression(formal, Verdict.PASS).round(new Round(Map.of("safety", 0))).build();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		AuthorAttempt author = campaign.development().author();
		requireThat(author.runs().stream().map(PromotionRun::role).toList(), "roles").
			isEqualTo(List.of(Role.TRIGGERING, Role.TRIGGERING, Role.REGRESSION));
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, logs);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT,
			author.runs().get(2).instance(), Verdict.PASS);

		List<PromotionRun> runs = campaign.development().author().runs();
		requireThat(runs.get(0).verdict(), "triggering").isNull();
		requireThat(runs.get(2).verdict(), "regression").isEqualTo(Verdict.PASS);
	}

	/**
	 * Verifies that a rejected attempt keeps the working candidate and uses one of the category's attempts.
	 */
	// Requirement: R-D3
	@Test
	public void rejectedAttemptUsesRoundAttempt()
	{
		// Requirement: R-D3
		Campaign campaign = inRound();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		AuthorAttempt author = campaign.development().author();
		TrialInstance logs = author.runs().getFirst().instance();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, logs, Verdict.FAIL);

		requireThat(campaign.development().working().number(), "working").isEqualTo(1);
		requireThat(campaign.development().author(), "author").isNull();
		requireThat(campaign.development().round().attempts(), "completedAttempts").isEqualTo(Map.of("safety", 1));
		requireThat(campaign.authorAttempts(), "authorAttempts").isEqualTo(1);
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
		requireThat(campaign.development().author(), "author").isNull();
		requireThat(campaign.development().round().attempts(), "completedAttempts").isEqualTo(Map.of("safety", 1));
	}

	/**
	 * Verifies that the outcome of an earlier author attempt's run is dropped.
	 */
	// Requirement: R-D3
	@Test
	public void staleRunIsDropped()
	{
		// Requirement: R-D3
		Campaign campaign = inRound();
		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		AuthorAttempt author = campaign.development().author();
		TrialInstance logs = author.runs().getFirst().instance();
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, logs);
		TrialKey slow = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(slow));
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, logs, Verdict.FAIL);
		requireThat(campaign.development().author(), "author").isNull();

		List<LogicalTrial> before = new ArrayList<>(campaign.trials());
		campaign = TransitionSteps.complete(campaign, slow, Verdict.PASS);
		requireThat(campaign.trials(), "trials").size().isEqualTo(before.size() - 1);
		requireThat(campaign.development().round().attempts(), "completedAttempts").isEqualTo(Map.of("safety", 1));
	}
}
