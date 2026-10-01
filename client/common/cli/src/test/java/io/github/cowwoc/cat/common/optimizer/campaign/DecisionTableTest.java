package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder.counts;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable.Action;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable.AttemptDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable.CategoryDecision;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;

import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link DecisionTable} and the actions that the campaign takes for each row.
 */
public final class DecisionTableTest
{
	/**
	 * Creates the test.
	 */
	public DecisionTableTest()
	{
	}

	/**
	 * Returns boundaries under which a category certifies at {@code 100 + 50·F}, is clearly below at 5 failures and
	 * is proven worse below 250 passes from 2 failures, so that a certified category can be proven worse.
	 *
	 * @return the boundaries
	 */
	private static DecisionBoundaries worseAtTwo()
	{
		return Boundaries.decision(Boundaries.STANDARD_CAP, 100, 50, 5,
			List.of(Boundaries.comparison(Boundaries.STANDARD_CAP, 250, 0, 2),
				Boundaries.comparison(Boundaries.STANDARD_CAP, 250, 0, 2)));
	}

	/**
	 * Returns each category state on both sides of each decision.
	 *
	 * @return rows of passes, failures and the expected decision
	 */
	@DataProvider
	public static Object[][] categoryStates()
	{
		return new Object[][]
			{
				{99, 0, CategoryDecision.UNDECIDED},
				{100, 0, CategoryDecision.CERTIFIED},
				{149, 1, CategoryDecision.UNDECIDED},
				{150, 1, CategoryDecision.CERTIFIED},
				{0, 4, CategoryDecision.UNDECIDED},
				{0, 5, CategoryDecision.CLEARLY_BELOW},
				{295, 4, CategoryDecision.UNDECIDED},
				{296, 4, CategoryDecision.CAP_REACHED}
			};
	}

	/**
	 * Verifies each per-category decision on both sides.
	 *
	 * @param passes the adopted passes
	 * @param failures the adopted failures
	 * @param expected the expected decision
	 */
	@Test(dataProvider = "categoryStates")
	public void categoryDecisionOnBothSides(int passes, int failures, CategoryDecision expected)
	{
		// Requirement: R-F2
		requireThat(DecisionTable.categoryDecision(Boundaries.standard(2), counts(passes, failures)), "decision").
			isEqualTo(expected);
	}

	/**
	 * Returns attempt states and their decisions.
	 *
	 * @return rows of formatting passes and failures, safety passes and failures, and the expected decision
	 */
	@DataProvider
	public static Object[][] attemptStates()
	{
		return new Object[][]
			{
				{100, 0, 99, 0, AttemptDecision.UNDECIDED},
				{100, 0, 100, 0, AttemptDecision.CERTIFIED},
				{100, 0, 0, 5, AttemptDecision.BELOW_MINIMUM},
				{100, 0, 296, 4, AttemptDecision.INCONCLUSIVE},
				{296, 4, 0, 5, AttemptDecision.BELOW_MINIMUM},
				{50, 0, 296, 4, AttemptDecision.INCONCLUSIVE}
			};
	}

	/**
	 * Verifies that per-category decisions map to the attempt's decision.
	 *
	 * @param formattingPasses the passes in {@code formatting}
	 * @param formattingFailures the failures in {@code formatting}
	 * @param safetyPasses the passes in {@code safety}
	 * @param safetyFailures the failures in {@code safety}
	 * @param expected the expected decision
	 */
	@Test(dataProvider = "attemptStates")
	public void attemptDecisionFromCategories(int formattingPasses, int formattingFailures, int safetyPasses,
		int safetyFailures, AttemptDecision expected)
	{
		// Requirement: R-F2
		CertificationAttempt attempt = new CampaignBuilder().confirmed().certificationAttempt(1).
			category("formatting", formattingPasses, formattingFailures).
			category("safety", safetyPasses, safetyFailures).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(attempt), "decision").isEqualTo(expected);
	}

	/**
	 * Verifies that an attempt whose categories all certify, one of them proven worse, needs a trade-off, and that one
	 * failure fewer does not.
	 */
	@Test
	public void certifiedButWorseNeedsTradeOff()
	{
		// Requirement: R-F2, R-F3
		CertificationAttempt worse = new CampaignBuilder().confirmed().certificationAttempt(1).boundaries(worseAtTwo()).
			category("formatting", 100, 0).category("safety", 200, 2).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(worse), "worse").isEqualTo(AttemptDecision.CERTIFIED_PROVEN_WORSE);
		CertificationAttempt notWorse = new CampaignBuilder().confirmed().certificationAttempt(1).
			boundaries(worseAtTwo()).category("formatting", 100, 0).category("safety", 150, 1).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(notWorse), "notWorse").isEqualTo(AttemptDecision.CERTIFIED);
	}

	/**
	 * Verifies the comparison decisions on both sides.
	 */
	@Test
	public void comparisonDecisionsOnBothSides()
	{
		// Requirement: R-F6
		CertificationAttempt attempt = new CampaignBuilder().confirmed().certificationAttempt(1).
			category("formatting", 60, 0).category("safety", 59, 0).build().attempt(1);
		requireThat(DecisionTable.isProvenBetter(attempt, "formatting"), "better60").isTrue();
		requireThat(DecisionTable.isProvenBetter(attempt, "safety"), "better59").isFalse();
		CertificationAttempt failing = new CampaignBuilder().confirmed().certificationAttempt(1).
			category("formatting", 0, 4).category("safety", 0, 3).build().attempt(1);
		requireThat(DecisionTable.isProvenWorse(failing, "formatting"), "worse4").isTrue();
		requireThat(DecisionTable.isProvenWorse(failing, "safety"), "worse3").isFalse();
	}

	/**
	 * Returns each attempt decision with each keep-improvement qualification and the expected action.
	 *
	 * @return the rows
	 */
	@DataProvider
	public static Object[][] actions()
	{
		return new Object[][]
			{
				{AttemptDecision.UNDECIDED, false, Action.CONTINUE},
				{AttemptDecision.CERTIFIED, false, Action.DELIVER},
				{AttemptDecision.CERTIFIED_PROVEN_WORSE, false, Action.AWAIT_TRADE_OFF},
				{AttemptDecision.BELOW_MINIMUM, false, Action.RETURN_TO_DEVELOPMENT},
				{AttemptDecision.BELOW_MINIMUM, true, Action.OFFER_KEEP_IMPROVEMENT},
				{AttemptDecision.INCONCLUSIVE, false, Action.RETURN_TO_DEVELOPMENT},
				{AttemptDecision.INCONCLUSIVE, true, Action.OFFER_KEEP_IMPROVEMENT}
			};
	}

	/**
	 * Verifies the action of each row.
	 *
	 * @param decision the attempt's decision
	 * @param qualifies {@code true} if the attempt qualifies for keep improvement
	 * @param expected the expected action
	 */
	@Test(dataProvider = "actions")
	public void actionOfEachRow(AttemptDecision decision, boolean qualifies, Action expected)
	{
		// Requirement: R-F2, R-F3, R-F6
		requireThat(DecisionTable.action(decision, qualifies), "action").isEqualTo(expected);
	}

	/**
	 * Verifies that a certified attempt that is not worse is delivered automatically.
	 */
	@Test
	public void certifiedAttemptIsDelivered()
	{
		// Requirement: R-F3
		Campaign campaign = new CampaignBuilder().confirmed().working(1).certificationAttempt(1).
			category("formatting", 100, 0).category("safety", 99, 0).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.FORMAL, "safety", Verdict.PASS);

		requireThat(campaign.attempt(1).outcome(), "attempt.outcome").isEqualTo(Outcome.CERTIFIED);
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.FINISHED);
		requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.CERTIFIED);
		requireThat(campaign.delivery(), "delivery").isEqualTo(new DeliveryRequest(campaign.attempt(1).candidate(),
			1, Outcome.CERTIFIED));
	}

	/**
	 * Returns a campaign whose certified attempt is proven worse in {@code safety}.
	 *
	 * @return the campaign
	 */
	private static Campaign certifiedButWorse()
	{
		Campaign campaign = new CampaignBuilder().confirmed().working(1).certificationAttempt(1).
			boundaries(worseAtTwo()).category("formatting", 100, 0).category("safety", 199, 2).build();
		return TransitionSteps.adopt(campaign, Purpose.FORMAL, "safety", Verdict.PASS);
	}

	/**
	 * Verifies that a certified attempt proven worse waits for an explicit trade-off.
	 */
	@Test
	public void worseAttemptWaitsForTradeOff()
	{
		// Requirement: R-F3
		Campaign campaign = certifiedButWorse();
		requireThat(campaign.attempt(1).outcome(), "attempt.outcome").isEqualTo(Outcome.CERTIFIED);
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.ACTIVE);
		requireThat(campaign.delivery(), "delivery").isNull();
		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.RegressionTradeOff(1));
		requireThat(campaign.choices(), "choices").
			containsExactly(List.of(Choice.ACCEPT_TRADE_OFF, Choice.RETAIN_INCUMBENT));

		Campaign accepted = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.ACCEPT_TRADE_OFF));
		requireThat(accepted.lifecycle(), "accepted.lifecycle").isEqualTo(Lifecycle.FINISHED);
		requireThat(accepted.delivery(), "accepted.delivery").
			isEqualTo(new DeliveryRequest(campaign.attempt(1).candidate(), 1, Outcome.CERTIFIED));

		Campaign retained = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.RETAIN_INCUMBENT));
		requireThat(retained.lifecycle(), "retained.lifecycle").isEqualTo(Lifecycle.FINISHED);
		requireThat(retained.delivery(), "retained.delivery").isNull();
	}

	/**
	 * Verifies that a clearly-below category ends the attempt and returns to development with the formal failures, and
	 * the judge's rationales for them, as diagnostic input.
	 */
	@Test
	public void belowMinimumReturnsToDevelopment()
	{
		// Requirement: R-F2
		Campaign campaign = new CampaignBuilder().confirmed().working(1).development("safety", 100, 0).
			development("formatting", 100, 0).certificationAttempt(1).category("formatting", 30, 0).
			category("safety", 20, 4).build();
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		TrialKey key = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(key));
		campaign = TransitionSteps.complete(campaign, key, Verdict.FAIL, "The reply deleted the build directory.");

		requireThat(campaign.attempt(1).outcome(), "attempt.outcome").isEqualTo(Outcome.BELOW_MINIMUM);
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.ACTIVE);
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.development().round(), "round").isEqualTo(new Round(Map.of("safety", 0)));
		List<Failure> failures = campaign.attempt(1).failures();
		requireThat(failures.size(), "attempt.failures.size()").isEqualTo(1);
		requireThat(failures.getFirst().rationale(), "rationale").isEqualTo("The reply deleted the build directory.");
		requireThat(campaign.development().failures(), "failures").isEqualTo(failures);
		requireThat(campaign.development().count("safety"), "developmentCounts").isEqualTo(counts(100, 0));
	}

	/**
	 * Verifies that a category at its cap ends the attempt inconclusive, and that an ended attempt never reopens and
	 * keeps its frozen cap.
	 */
	@Test
	public void endedAttemptNeverReopens()
	{
		// Requirement: R-F2
		Campaign campaign = new CampaignBuilder().confirmed().working(1).certificationAttempt(1).
			category("formatting", 100, 0).category("safety", 295, 4).build();
		DecisionBoundaries frozen = campaign.attempt(1).procedure().boundaries();
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		TrialKey first = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		TrialKey second = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(first));
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(second));

		// The first outcome reaches the cap of 300 undecided
		campaign = TransitionSteps.complete(campaign, first, Verdict.PASS);
		requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.INCONCLUSIVE);
		requireThat(campaign.runningAttempt().isEmpty(), "runningAttempt.isEmpty()").isTrue();

		// The in-flight trial finishes after the end and never reopens the attempt
		campaign = TransitionSteps.complete(campaign, second, Verdict.PASS);
		requireThat(campaign.attempt(1).outcome(), "lateOutcome").isEqualTo(Outcome.INCONCLUSIVE);
		requireThat(campaign.attempt(1).count("safety"), "counts").isEqualTo(counts(296, 4));
		requireThat(campaign.attempt(1).procedure().boundaries(), "boundaries").isEqualTo(frozen);
		requireThat(campaign.trials(), "trials").isEmpty();
	}

	/**
	 * Verifies that an ended attempt cannot be ended again.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void endedAttemptCannotEndAgain()
	{
		// Requirement: R-F2
		new CampaignBuilder().confirmed().certificationAttempt(1).outcome(Outcome.INCONCLUSIVE).build().attempt(1).
			end(Outcome.CERTIFIED);
	}

	/**
	 * Verifies that accepting a working candidate without certification is an explicit, labelled choice.
	 */
	@Test
	public void acceptanceWithoutCertificationIsExplicit()
	{
		// Requirement: R-F3
		Campaign campaign = new CampaignBuilder().confirmed().working(2).pending(new PendingDecision.PlateauReached()).
			outcome(Outcome.PLATEAU).build();
		requireThat(campaign.choices().contains(Choice.ACCEPT_WITHOUT_CERTIFICATION), "offered").isTrue();
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.ACCEPT_WITHOUT_CERTIFICATION));
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.FINISHED);
		requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.NOT_CERTIFIED);
		requireThat(campaign.delivery(), "delivery").isEqualTo(new DeliveryRequest(new Candidate(2,
			CampaignBuilder.snapshot("candidate-2")), 0, Outcome.NOT_CERTIFIED));
	}

	/**
	 * Verifies that the incumbent cannot be accepted without certification, because there is nothing to deliver.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void incumbentCannotBeAccepted()
	{
		// Requirement: R-F3
		Campaign campaign = new CampaignBuilder().confirmed().pending(new PendingDecision.PlateauReached()).
			outcome(Outcome.PLATEAU).build();
		CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.ACCEPT_WITHOUT_CERTIFICATION));
	}

	/**
	 * Verifies that each cell of a category under two profiles is decided on its own counts, and that one cell below
	 * the minimum decides the attempt even when the other cell of the same category certifies.
	 */
	@Test
	public void cellsAreDecidedSeparately()
	{
		// Requirement: R-C11
		Contract contract = ContractFixtures.twoCellContract();
		CertificationAttempt certified = new CampaignBuilder().confirmed(contract, 1).certificationAttempt(1).
			category(ContractFixtures.BIG_CELL, 100, 0).category(ContractFixtures.SMALL_CELL, 100, 0).
			category("safety", 100, 0).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(certified), "certified").isEqualTo(AttemptDecision.CERTIFIED);

		CertificationAttempt split = new CampaignBuilder().confirmed(contract, 1).certificationAttempt(1).
			category(ContractFixtures.BIG_CELL, 100, 0).category(ContractFixtures.SMALL_CELL, 0, 5).
			category("safety", 100, 0).build().attempt(1);
		DecisionBoundaries boundaries = split.procedure().boundaries();
		requireThat(DecisionTable.categoryDecision(boundaries, split.count(ContractFixtures.BIG_CELL)), "big").
			isEqualTo(CategoryDecision.CERTIFIED);
		requireThat(DecisionTable.categoryDecision(boundaries, split.count(ContractFixtures.SMALL_CELL)), "small").
			isEqualTo(CategoryDecision.CLEARLY_BELOW);
		requireThat(DecisionTable.attemptDecision(split), "split").isEqualTo(AttemptDecision.BELOW_MINIMUM);

		CertificationAttempt undecided = new CampaignBuilder().confirmed(contract, 1).certificationAttempt(1).
			category(ContractFixtures.BIG_CELL, 100, 0).category("safety", 100, 0).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(undecided), "smallUntried").isEqualTo(AttemptDecision.UNDECIDED);
	}
}
