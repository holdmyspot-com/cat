package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.campaign.Builder.counts;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable.Action;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable.AttemptDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable.CellDecision;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;

import java.util.List;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link DecisionTable} and the campaign actions that its decisions select.
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
	 * Returns boundaries under which a cell certifies at {@code 100 + 50 * failures} passes when reachable within
	 * the cap, is clearly below at 5 failures, and is proven worse below 250 passes from 2 failures. A cell can
	 * therefore certify while being proven worse.
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
				{99, 0, CellDecision.UNDECIDED},
				{100, 0, CellDecision.CERTIFIED},
				{149, 1, CellDecision.UNDECIDED},
				{150, 1, CellDecision.CERTIFIED},
				{0, 4, CellDecision.UNDECIDED},
				{0, 5, CellDecision.CLEARLY_BELOW},
				{295, 4, CellDecision.UNDECIDED},
				{296, 4, CellDecision.CAP_REACHED}
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
	public void cellDecisionOnBothSides(int passes, int failures, CellDecision expected)
	{
		// Requirement: R-F2
		requireThat(DecisionTable.cellDecision(Boundaries.standard(2), counts(passes, failures)), "decision").
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
	public void attemptDecisionFromCells(int formattingPasses, int formattingFailures, int safetyPasses,
		int safetyFailures, AttemptDecision expected)
	{
		// Requirement: R-F2
		CertificationAttempt attempt = new Builder().confirmed().certificationAttempt(1).
			cell("formatting", formattingPasses, formattingFailures).
			cell("safety", safetyPasses, safetyFailures).build().attempt(1);
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
		CertificationAttempt worse = new Builder().confirmed().certificationAttempt(1).boundaries(worseAtTwo()).
			cell("formatting", 100, 0).cell("safety", 200, 2).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(worse), "worse").isEqualTo(AttemptDecision.CERTIFIED_PROVEN_WORSE);
		CertificationAttempt notWorse = new Builder().confirmed().certificationAttempt(1).
			boundaries(worseAtTwo()).cell("formatting", 100, 0).cell("safety", 150, 1).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(notWorse), "notWorse").isEqualTo(AttemptDecision.CERTIFIED);
	}

	/**
	 * Verifies the comparison decisions on both sides.
	 */
	@Test
	public void comparisonDecisionsOnBothSides()
	{
		// Requirement: R-F6
		CertificationAttempt attempt = new Builder().confirmed().certificationAttempt(1).
			cell("formatting", 60, 0).cell("safety", 59, 0).build().attempt(1);
		requireThat(DecisionTable.isProvenBetter(attempt, "formatting"), "better60").isTrue();
		requireThat(DecisionTable.isProvenBetter(attempt, "safety"), "better59").isFalse();
		CertificationAttempt failing = new Builder().confirmed().certificationAttempt(1).
			cell("formatting", 0, 4).cell("safety", 0, 3).build().attempt(1);
		requireThat(DecisionTable.isProvenWorse(failing, "formatting"), "worse4").isTrue();
		requireThat(DecisionTable.isProvenWorse(failing, "safety"), "worse3").isFalse();
	}

	/**
	 * Verifies that missing measurement conditions make both improvement decisions unavailable, even when the
	 * frozen count boundaries would otherwise prove a difference.
	 */
	@Test
	public void missingConditionsBlockProofs()
	{
		// Requirement: R-M4, R-F6
		CertificationAttempt better = new Builder().confirmed().identity(MeasurementIdentity.EMPTY).
			certificationAttempt(1).baselineIdentity(Builder.confirmedIdentity("model-a")).
			cell("formatting", 60, 0).build().attempt(1);
		CertificationAttempt worse = new Builder().confirmed().identity(MeasurementIdentity.EMPTY).
			certificationAttempt(1).baselineIdentity(Builder.confirmedIdentity("model-a")).
			cell("formatting", 0, 4).build().attempt(1);

		requireThat(DecisionTable.isProvenBetter(better, "formatting"), "better").isFalse();
		requireThat(DecisionTable.isProvenWorse(worse, "formatting"), "worse").isFalse();
	}

	/**
	 * Verifies that a model differing from the frozen incumbent's model cannot prove a candidate better.
	 */
	@Test
	public void differingConditionsBlockBetter()
	{
		// Requirement: R-M4, R-F6
		CertificationAttempt attempt = new Builder().confirmed().certificationAttempt(1).
			baselineIdentity(Builder.confirmedIdentity("model-b")).cell("formatting", 60, 0).
			build().attempt(1);

		requireThat(DecisionTable.isProvenBetter(attempt, "formatting"), "better").isFalse();
	}

	/**
	 * Verifies that differing or missing incumbent measurement conditions cannot prove a candidate worse.
	 */
	@Test
	public void unavailableConditionsBlockWorse()
	{
		// Requirement: R-M4, R-F6
		CertificationAttempt differing = new Builder().confirmed().certificationAttempt(1).
			baselineIdentity(Builder.confirmedIdentity("model-b")).cell("formatting", 0, 4).
			build().attempt(1);
		CertificationAttempt missing = new Builder().confirmed().identity(MeasurementIdentity.EMPTY).
			certificationAttempt(1).baselineIdentity(Builder.confirmedIdentity("model-a")).
			cell("formatting", 0, 4).build().attempt(1);

		requireThat(DecisionTable.isProvenWorse(differing, "formatting"), "differing").isFalse();
		requireThat(DecisionTable.isProvenWorse(missing, "formatting"), "missing").isFalse();
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
		Campaign campaign = new Builder().confirmed().working(1).certificationAttempt(1).
			cell("formatting", 100, 0).cell("safety", 99, 0).build();
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
		Campaign campaign = new Builder().confirmed().working(1).certificationAttempt(1).
			boundaries(worseAtTwo()).cell("formatting", 100, 0).cell("safety", 199, 2).build();
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

		Campaign accepted = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.ACCEPT_TRADE_OFF));
		requireThat(accepted.lifecycle(), "accepted.lifecycle").isEqualTo(Lifecycle.FINISHED);
		requireThat(accepted.delivery(), "accepted.delivery").
			isEqualTo(new DeliveryRequest(campaign.attempt(1).candidate(), 1, Outcome.CERTIFIED));

		Campaign retained = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.RETAIN_INCUMBENT));
		requireThat(retained.lifecycle(), "retained.lifecycle").isEqualTo(Lifecycle.FINISHED);
		requireThat(retained.delivery(), "retained.delivery").isNull();
		requireThat(retained.outcome(), "retained.outcome").isEqualTo(Outcome.CERTIFIED);
	}

	/**
	 * Verifies that a clearly-below category ends the attempt and returns to development automatically, with the
	 * failed cell as a development cell: the attempt keeps its held-out failures for the user's report, while
	 * development receives none of their instances, starts no round from them and adds none of them to the regression
	 * set.
	 */
	@Test
	public void belowMinimumReturnsToDevelopment()
	{
		// Requirement: R-F2, R-D9, R-D3
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 100, 0).
			development("formatting", 100, 0).certificationAttempt(1).cell("formatting", 30, 0).
			cell("safety", 20, 4).build();
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		TrialKey key = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
		campaign = TransitionSteps.complete(campaign, key, Verdict.FAIL, "The reply deleted the build directory.");

		requireThat(campaign.attempt(1).outcome(), "attempt.outcome").isEqualTo(Outcome.BELOW_MINIMUM);
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.ACTIVE);
		requireThat(campaign.pending(), "pending").isNull();
		List<Failure> failures = campaign.attempt(1).failures();
		requireThat(failures.size(), "attempt.failures.size()").isEqualTo(1);
		requireThat(failures.getFirst().rationale(), "rationale").isEqualTo("The reply deleted the build directory.");
		requireThat(failures.getFirst().instance().targeted(), "targeted").isFalse();
		requireThat(campaign.development().failures(), "developmentFailures").isEmpty();
		requireThat(campaign.lineage().regressionSet(), "regressionSet").isEmpty();
		requireThat(campaign.development().diagnosis(), "diagnosis").isNull();
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(CellTier.developmentCellIds(campaign), "developmentCells").contains("safety");
		requireThat(campaign.development().count("safety"), "developmentCounts").isEqualTo(counts(100, 0));
	}

	/**
	 * Verifies that an undecided cell at its cap ends the attempt inconclusive, and a later outcome neither reopens
	 * the attempt nor changes its counts or fixed boundaries.
	 */
	@Test
	public void endedAttemptNeverReopens()
	{
		// Requirement: R-F2
		Campaign campaign = new Builder().confirmed().working(1).certificationAttempt(1).
			cell("formatting", 100, 0).cell("safety", 295, 4).build();
		DecisionBoundaries frozen = campaign.attempt(1).procedure().boundaries();
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		TrialKey first = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		TrialKey second = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(first));
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(second));

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
		new Builder().confirmed().certificationAttempt(1).outcome(Outcome.INCONCLUSIVE).build().attempt(1).
			end(Outcome.CERTIFIED);
	}

	/**
	 * Verifies that explicitly accepting a working candidate without certification finishes the campaign with
	 * {@link Outcome#NOT_CERTIFIED} and a delivery request carrying that label.
	 */
	@Test
	public void acceptanceWithoutCertificationIsExplicit()
	{
		// Requirement: R-F3
		Campaign campaign = new Builder().confirmed().working(2).pending(new PendingDecision.PlateauReached()).
			outcome(Outcome.PLATEAU).build();
		requireThat(campaign.choices().contains(Choice.ACCEPT_WITHOUT_CERTIFICATION), "offered").isTrue();
		campaign = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.ACCEPT_WITHOUT_CERTIFICATION));
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.FINISHED);
		requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.NOT_CERTIFIED);
		requireThat(campaign.delivery(), "delivery").isEqualTo(new DeliveryRequest(new Candidate(2,
			Builder.snapshot("candidate-2")), 0, Outcome.NOT_CERTIFIED));
	}

	/**
	 * Verifies that the incumbent cannot be accepted without certification, because there is nothing to deliver.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void incumbentCannotBeAccepted()
	{
		// Requirement: R-F3
		Campaign campaign = new Builder().confirmed().pending(new PendingDecision.PlateauReached()).
			outcome(Outcome.PLATEAU).build();
		Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.ACCEPT_WITHOUT_CERTIFICATION));
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
		CertificationAttempt certified = new Builder().confirmed(contract, 1).certificationAttempt(1).
			cell(ContractFixtures.BIG_CELL, 100, 0).cell(ContractFixtures.SMALL_CELL, 100, 0).
			cell("safety", 100, 0).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(certified), "certified").isEqualTo(AttemptDecision.CERTIFIED);

		CertificationAttempt split = new Builder().confirmed(contract, 1).certificationAttempt(1).
			cell(ContractFixtures.BIG_CELL, 100, 0).cell(ContractFixtures.SMALL_CELL, 0, 5).
			cell("safety", 100, 0).build().attempt(1);
		DecisionBoundaries boundaries = split.procedure().boundaries();
		requireThat(DecisionTable.cellDecision(boundaries, split.count(ContractFixtures.BIG_CELL)), "big").
			isEqualTo(CellDecision.CERTIFIED);
		requireThat(DecisionTable.cellDecision(boundaries, split.count(ContractFixtures.SMALL_CELL)), "small").
			isEqualTo(CellDecision.CLEARLY_BELOW);
		requireThat(DecisionTable.attemptDecision(split), "split").isEqualTo(AttemptDecision.BELOW_MINIMUM);

		CertificationAttempt undecided = new Builder().confirmed(contract, 1).certificationAttempt(1).
			cell(ContractFixtures.BIG_CELL, 100, 0).cell("safety", 100, 0).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(undecided), "smallUntried").isEqualTo(AttemptDecision.UNDECIDED);
	}
}
