package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.cat.common.optimizer.campaign.test.Builder.counts;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CellTier;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.Pairing;
import io.github.cowwoc.cat.common.optimizer.campaign.Pairing.UnpairedTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable.Action;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable.AttemptDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable.CellDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.FailedAssertion;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PairCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.Reference;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.secrets.CertificationAttemptSecrets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
				Boundaries.comparison(Boundaries.STANDARD_CAP, 250, 0, 2),
				Boundaries.comparison(Boundaries.STANDARD_CAP, 250, 0, 2)));
	}

	/**
	 * Returns each cell state on both sides of each decision.
	 *
	 * @return rows of passes, failures and the expected decision
	 */
	@DataProvider
	public static Object[][] cellStates()
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
	 * Verifies each per-cell decision on both sides.
	 *
	 * @param passes the adopted passes
	 * @param failures the adopted failures
	 * @param expected the expected decision
	 */
	@Test(dataProvider = "cellStates")
	public void cellDecisionOnBothSides(int passes, int failures, CellDecision expected)
	{
		// Requirement: R-F2
		requireThat(DecisionTable.minimumDecision(Boundaries.standard(2), counts(passes, failures)), "decision").
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
	 * Verifies that per-cell decisions map to the attempt's decision, with {@code plain_list} certified.
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
			cell("formatting", formattingPasses, formattingFailures).cell("plain_list", 100, 0).
			cell("safety", safetyPasses, safetyFailures).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(attempt), "decision").isEqualTo(expected);
	}

	/**
	 * Verifies that an attempt whose tests all certify, one of them proven worse, needs a trade-off, and that one
	 * failure fewer does not.
	 */
	@Test
	public void certifiedButWorseNeedsTradeOff()
	{
		// Requirement: R-F2, R-F3
		CertificationAttempt worse = new Builder().confirmed().certificationAttempt(1).boundaries(worseAtTwo()).
			cell("formatting", 100, 0).cell("plain_list", 100, 0).cell("safety", 200, 2).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(worse), "worse").isEqualTo(AttemptDecision.CERTIFIED_PROVEN_WORSE);
		CertificationAttempt notWorse = new Builder().confirmed().certificationAttempt(1).
			boundaries(worseAtTwo()).cell("formatting", 100, 0).cell("plain_list", 100, 0).cell("safety", 150, 1).
			build().attempt(1);
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
				{AttemptDecision.BELOW_CONTROL, false, Action.RETURN_TO_DEVELOPMENT},
				{AttemptDecision.BELOW_CONTROL, true, Action.OFFER_KEEP_IMPROVEMENT},
				{AttemptDecision.BELOW_INCUMBENT, false, Action.RETURN_TO_DEVELOPMENT},
				{AttemptDecision.BELOW_INCUMBENT, true, Action.OFFER_KEEP_IMPROVEMENT},
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
			cell("formatting", 100, 0).cell("plain_list", 100, 0).cell("safety", 100, 0).
			controlPairing("safety", oneControlShort()).build();
		campaign = TransitionSteps.adoptReference(campaign, Reference.CONTROL, "safety", Verdict.FAIL, List.of());

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
			boundaries(worseAtTwo()).cell("formatting", 100, 0).cell("plain_list", 100, 0).cell("safety", 200, 2).
			controlPairing("safety", oneControlShort()).build();
		return TransitionSteps.adoptReference(campaign, Reference.CONTROL, "safety", Verdict.FAIL, List.of());
	}

	/**
	 * Returns control comparisons of {@code safety} that pass once the control trial of the one waiting candidate
	 * formal trial fails.
	 *
	 * @return the comparisons
	 */
	private static Pairing oneControlShort()
	{
		PairCounts candidateAhead = new PairCounts(0, 6, 0, 0);
		Map<String, PairCounts> comparisons = new LinkedHashMap<>(comparing(candidateAhead, candidateAhead).
			comparisons());
		UnpairedTrial waiting = new UnpairedTrial(Builder.testCase("safety", "logs"),
			Set.of("asks_before_deleting", TestCase.EXPECTED_OUTCOME));
		return new Pairing(List.of(waiting), comparisons);
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
	 * Verifies that a clearly-below test ends the attempt and returns to development automatically, with the
	 * failed cell as a development cell: the attempt keeps its held-out failures for the user's report, while
	 * development receives none of their test cases, starts no round from them and adds none of them to the regression
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
		requireThat(failures.getFirst().testCase().targeted(), "targeted").isFalse();
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
		CertificationAttemptSecrets.access().end(new Builder().confirmed().certificationAttempt(1).
			outcome(Outcome.INCONCLUSIVE).build().attempt(1), Outcome.CERTIFIED);
	}

	/**
	 * Verifies that a review checkpoint offers to accept a working candidate that is not the incumbent without
	 * certification, and that the explicit choice finishes the campaign with {@link Outcome#NOT_CERTIFIED} and a
	 * delivery request carrying that label.
	 */
	@Test
	public void acceptanceWithoutCertificationIsExplicit()
	{
		// Requirement: R-F3
		Campaign campaign = new Builder().confirmed().working(2).
			pending(new PendingDecision.CheckpointReached("safety", 200)).build();
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
		Campaign campaign = new Builder().confirmed().
			pending(new PendingDecision.CheckpointReached("safety", 200)).build();
		Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.ACCEPT_WITHOUT_CERTIFICATION));
	}

	/**
	 * Verifies that each cell of a test under two profiles is decided on its own counts, and that one cell below
	 * the minimum decides the attempt even when the other cell of the same test certifies.
	 */
	@Test
	public void cellsAreDecidedSeparately()
	{
		// Requirement: R-C11
		Contract contract = ContractFixtures.twoCellContract();
		CertificationAttempt certified = new Builder().confirmed(contract, 1).certificationAttempt(1).
			cell(ContractFixtures.BIG_CELL, 100, 0).cell(ContractFixtures.SMALL_CELL, 100, 0).
			cell(ContractFixtures.PLAIN_BIG_CELL, 100, 0).cell(ContractFixtures.PLAIN_SMALL_CELL, 100, 0).
			cell("safety", 100, 0).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(certified), "certified").isEqualTo(AttemptDecision.CERTIFIED);

		CertificationAttempt split = new Builder().confirmed(contract, 1).certificationAttempt(1).
			cell(ContractFixtures.BIG_CELL, 100, 0).cell(ContractFixtures.SMALL_CELL, 0, 5).
			cell(ContractFixtures.PLAIN_BIG_CELL, 100, 0).cell(ContractFixtures.PLAIN_SMALL_CELL, 100, 0).
			cell("safety", 100, 0).build().attempt(1);
		DecisionBoundaries boundaries = split.procedure().boundaries();
		requireThat(DecisionTable.minimumDecision(boundaries, split.count(ContractFixtures.BIG_CELL)), "big").
			isEqualTo(CellDecision.CERTIFIED);
		requireThat(DecisionTable.minimumDecision(boundaries, split.count(ContractFixtures.SMALL_CELL)), "small").
			isEqualTo(CellDecision.CLEARLY_BELOW);
		requireThat(DecisionTable.attemptDecision(split), "split").isEqualTo(AttemptDecision.BELOW_MINIMUM);

		CertificationAttempt undecided = new Builder().confirmed(contract, 1).certificationAttempt(1).
			cell(ContractFixtures.BIG_CELL, 100, 0).cell("safety", 100, 0).build().attempt(1);
		requireThat(DecisionTable.attemptDecision(undecided), "smallUntried").isEqualTo(AttemptDecision.UNDECIDED);
	}

	/**
	 * Returns control comparisons of two assertions with no candidate formal trial waiting for a control trial.
	 *
	 * @param first the pairs of the prompt test's first assertion
	 * @param expectedOutcome the pairs of {@value TestCase#EXPECTED_OUTCOME}
	 * @return the comparisons
	 */
	private static Pairing comparing(PairCounts first, PairCounts expectedOutcome)
	{
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		comparisons.put("asks_before_deleting", first);
		comparisons.put(TestCase.EXPECTED_OUTCOME, expectedOutcome);
		return new Pairing(List.of(), comparisons);
	}

	/**
	 * Returns an attempt whose cells other than {@code safety} certified, with {@code safety}'s counts and control
	 * comparisons.
	 *
	 * @param passes the passes in {@code safety}
	 * @param failures the failures in {@code safety}
	 * @param pairing the control comparisons of {@code safety}
	 * @return the attempt
	 */
	private static CertificationAttempt safetyAttempt(int passes, int failures, Pairing pairing)
	{
		return new Builder().confirmed().certificationAttempt(1).cell("formatting", 100, 0).
			cell("plain_list", 100, 0).cell("safety", passes, failures).controlPairing("safety", pairing).build().attempt(1);
	}

	/**
	 * Verifies that a cell whose minimum-pass-rate part certified is below control, and ends the attempt below
	 * control, once some assertion's control trials are proven to pass it more often, and that one fewer pair that
	 * only the control passed decides nothing.
	 */
	@Test
	public void belowControlOnBothSides()
	{
		// Requirement: R-F7, R-S11
		CertificationAttempt below = safetyAttempt(100, 0, comparing(new PairCounts(1, 7, 0, 0),
			new PairCounts(0, 0, 8, 0)));
		requireThat(DecisionTable.cellDecision(below, "safety"), "below").isEqualTo(CellDecision.BELOW_CONTROL);
		requireThat(DecisionTable.attemptDecision(below), "belowAttempt").isEqualTo(AttemptDecision.BELOW_CONTROL);

		CertificationAttempt oneFewer = safetyAttempt(100, 0, comparing(new PairCounts(0, 7, 0, 0),
			new PairCounts(0, 0, 7, 0)));
		requireThat(DecisionTable.cellDecision(oneFewer, "safety"), "oneFewer").isEqualTo(CellDecision.UNDECIDED);
		requireThat(DecisionTable.attemptDecision(oneFewer), "oneFewerAttempt").isEqualTo(AttemptDecision.UNDECIDED);
	}

	/**
	 * Returns an attempt of working candidate 1 whose cells other than {@code safety} certified, with
	 * {@code safety}'s counts, control comparisons and incumbent comparisons.
	 *
	 * @param passes the passes in {@code safety}
	 * @param failures the failures in {@code safety}
	 * @param control the control comparisons of {@code safety}
	 * @param incumbent the incumbent comparisons of {@code safety}
	 * @return the attempt
	 */
	private static CertificationAttempt candidateAttempt(int passes, int failures, Pairing control,
		Pairing incumbent)
	{
		return new Builder().confirmed().working(1).certificationAttempt(1).cell("formatting", 100, 0).
			cell("plain_list", 100, 0).cell("safety", passes, failures).controlPairing("safety", control).
			incumbentPairing("safety", incumbent).build().attempt(1);
	}

	/**
	 * Verifies that a cell whose control comparisons passed is below incumbent, and ends the attempt below
	 * incumbent, once some assertion's incumbent trials are proven to pass it more often, and that one fewer pair that
	 * only the incumbent passed decides nothing.
	 */
	@Test
	public void belowIncumbentOnBothSides()
	{
		// Requirement: R-F8, R-S11
		PairCounts passed = new PairCounts(0, 7, 0, 0);
		CertificationAttempt below = candidateAttempt(100, 0, comparing(passed, passed),
			comparing(new PairCounts(1, 7, 0, 0), new PairCounts(0, 0, 8, 0)));
		requireThat(DecisionTable.cellDecision(below, "safety"), "below").isEqualTo(CellDecision.BELOW_INCUMBENT);
		requireThat(DecisionTable.attemptDecision(below), "belowAttempt").isEqualTo(AttemptDecision.BELOW_INCUMBENT);

		CertificationAttempt oneFewer = candidateAttempt(100, 0, comparing(passed, passed),
			comparing(new PairCounts(0, 7, 0, 0), new PairCounts(0, 0, 7, 0)));
		requireThat(DecisionTable.cellDecision(oneFewer, "safety"), "oneFewer").isEqualTo(CellDecision.UNDECIDED);
		requireThat(DecisionTable.attemptDecision(oneFewer), "oneFewerAttempt").isEqualTo(AttemptDecision.UNDECIDED);
	}

	/**
	 * Returns pairs that decide differently: passed, undecided on either side, and below.
	 *
	 * @return the rows
	 */
	@DataProvider
	public static Object[][] pairCounts()
	{
		return new Object[][]
			{
				{new PairCounts(0, 7, 0, 0)},
				{new PairCounts(0, 6, 0, 0)},
				{new PairCounts(3, 2, 2, 3)},
				{new PairCounts(0, 0, 7, 0)},
				{new PairCounts(0, 0, 8, 0)}
			};
	}

	/**
	 * Verifies that the incumbent comparisons decide by the same statistic as the control comparisons: the same pairs
	 * pass, stay undecided or fall below in both.
	 *
	 * @param counts the pairs of the compared assertion
	 */
	@Test(dataProvider = "pairCounts")
	public void incumbentComparisonUsesControlStatistic(PairCounts counts)
	{
		// Requirement: R-F8
		PairCounts passed = new PairCounts(0, 7, 0, 0);
		CellDecision control = DecisionTable.cellDecision(candidateAttempt(100, 0, comparing(counts, counts),
			comparing(passed, passed)), "safety");
		CellDecision incumbent = DecisionTable.cellDecision(candidateAttempt(100, 0, comparing(passed, passed),
			comparing(counts, counts)), "safety");
		CellDecision expected = switch (control)
		{
			case BELOW_CONTROL -> CellDecision.BELOW_INCUMBENT;
			default -> control;
		};
		requireThat(incumbent, "incumbent").isEqualTo(expected);
	}

	/**
	 * Verifies that a cell or attempt with an assertion below control and another below incumbent is below control.
	 */
	@Test
	public void belowControlTakesPrecedence()
	{
		// Requirement: R-F8, R-S11
		PairCounts passed = new PairCounts(0, 8, 0, 0);
		PairCounts below = new PairCounts(0, 0, 8, 0);
		CertificationAttempt sameCell = candidateAttempt(100, 0, comparing(passed, below), comparing(below, passed));
		requireThat(DecisionTable.cellDecision(sameCell, "safety"), "sameCell").isEqualTo(CellDecision.BELOW_CONTROL);
		requireThat(DecisionTable.attemptDecision(sameCell), "sameCellAttempt").
			isEqualTo(AttemptDecision.BELOW_CONTROL);

		CertificationAttempt otherCells = new Builder().confirmed().working(1).certificationAttempt(1).
			cell("formatting", 100, 0).controlPairing("formatting", comparing(passed, passed)).
			incumbentPairing("formatting", comparing(passed, below)).cell("plain_list", 100, 0).
			cell("safety", 100, 0).controlPairing("safety", comparing(below, passed)).build().attempt(1);
		requireThat(DecisionTable.cellDecision(otherCells, "formatting"), "formatting").
			isEqualTo(CellDecision.BELOW_INCUMBENT);
		requireThat(DecisionTable.attemptDecision(otherCells), "otherCellsAttempt").
			isEqualTo(AttemptDecision.BELOW_CONTROL);
	}

	/**
	 * Verifies that a cell whose minimum-pass-rate part certified certifies only once every assertion's control
	 * comparison passed.
	 */
	@Test
	public void certifiesOnceEveryComparisonPassed()
	{
		// Requirement: R-F7, R-S11
		PairCounts passed = new PairCounts(0, 7, 0, 0);
		CertificationAttempt undecided = safetyAttempt(100, 0, comparing(passed, new PairCounts(0, 6, 1, 0)));
		requireThat(DecisionTable.cellDecision(undecided, "safety"), "undecided").isEqualTo(CellDecision.UNDECIDED);
		requireThat(DecisionTable.attemptDecision(undecided), "undecidedAttempt").
			isEqualTo(AttemptDecision.UNDECIDED);

		CertificationAttempt certified = safetyAttempt(100, 0, comparing(passed, passed));
		requireThat(DecisionTable.cellDecision(certified, "safety"), "certified").isEqualTo(CellDecision.CERTIFIED);
		requireThat(DecisionTable.attemptDecision(certified), "certifiedAttempt").
			isEqualTo(AttemptDecision.CERTIFIED);
	}

	/**
	 * Verifies that a control trial that proves the control passes an assertion more often ends the attempt below
	 * control and returns to development automatically, with the cell as a development cell and no failure recorded
	 * from the control trial.
	 */
	@Test
	public void controlTrialEndsAttemptBelowControl()
	{
		// Requirement: R-F7, R-S11
		UnpairedTrial waiting = new UnpairedTrial(Builder.testCase("safety", "logs"), Set.of("asks_before_deleting"));
		Map<String, PairCounts> comparisons = new LinkedHashMap<>(comparing(new PairCounts(0, 7, 0, 0),
			new PairCounts(0, 0, 7, 0)).comparisons());
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 100, 0).
			development("formatting", 100, 0).certificationAttempt(1).cell("formatting", 30, 0).cell("safety", 100, 0).
			controlPairing("safety", new Pairing(List.of(waiting), comparisons)).build();
		campaign = TransitionSteps.adoptReference(campaign, Reference.CONTROL, "safety", Verdict.PASS, List.of());

		Pairing pairing = campaign.attempt(1).pairing(Reference.CONTROL, "safety");
		requireThat(pairing.unpaired(), "unpaired").isEmpty();
		requireThat(pairing.comparisons().get("asks_before_deleting"), "asksBeforeDeleting").
			isEqualTo(new PairCounts(1, 7, 0, 0));
		requireThat(pairing.comparisons().get(TestCase.EXPECTED_OUTCOME), "expectedOutcome").
			isEqualTo(new PairCounts(0, 0, 8, 0));
		requireThat(campaign.attempt(1).outcome(), "attempt.outcome").isEqualTo(Outcome.BELOW_CONTROL);
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.ACTIVE);
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.attempt(1).failures(), "failures").isEmpty();
		requireThat(CellTier.developmentCellIds(campaign), "developmentCells").contains("safety");
	}

	/**
	 * Verifies that a cell whose control comparisons passed certifies only once every assertion's incumbent comparison
	 * passed.
	 */
	@Test
	public void certifyingWaitsForIncumbent()
	{
		// Requirement: R-F8
		PairCounts passed = new PairCounts(0, 7, 0, 0);
		CertificationAttempt undecided = candidateAttempt(100, 0, comparing(passed, passed),
			comparing(passed, new PairCounts(0, 6, 1, 0)));
		requireThat(DecisionTable.cellDecision(undecided, "safety"), "undecided").isEqualTo(CellDecision.UNDECIDED);
		requireThat(DecisionTable.attemptDecision(undecided), "undecidedAttempt").
			isEqualTo(AttemptDecision.UNDECIDED);

		CertificationAttempt certified = candidateAttempt(100, 0, comparing(passed, passed),
			comparing(passed, passed));
		requireThat(DecisionTable.cellDecision(certified, "safety"), "certified").isEqualTo(CellDecision.CERTIFIED);
		requireThat(DecisionTable.attemptDecision(certified), "certifiedAttempt").
			isEqualTo(AttemptDecision.CERTIFIED);
	}

	/**
	 * Verifies that an incumbent trial that proves the incumbent passes an assertion more often ends the attempt below
	 * incumbent and returns to development automatically, with the cell as a development cell and no failure recorded
	 * from the incumbent trial.
	 */
	@Test
	public void incumbentTrialEndsAttemptBelowIncumbent()
	{
		// Requirement: R-F8, R-S11
		PairCounts passed = new PairCounts(0, 7, 0, 0);
		UnpairedTrial waiting = new UnpairedTrial(Builder.testCase("safety", "logs"), Set.of("asks_before_deleting"));
		Map<String, PairCounts> comparisons = new LinkedHashMap<>(comparing(new PairCounts(0, 7, 0, 0),
			new PairCounts(0, 0, 7, 0)).comparisons());
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 100, 0).
			development("formatting", 100, 0).certificationAttempt(1).cell("formatting", 30, 0).cell("safety", 100, 0).
			controlPairing("safety", comparing(passed, passed)).
			incumbentPairing("safety", new Pairing(List.of(waiting), comparisons)).build();
		campaign = TransitionSteps.adoptReference(campaign, Reference.INCUMBENT, "safety", Verdict.PASS, List.of());

		Pairing pairing = campaign.attempt(1).pairing(Reference.INCUMBENT, "safety");
		requireThat(pairing.unpaired(), "unpaired").isEmpty();
		requireThat(pairing.comparisons().get(TestCase.EXPECTED_OUTCOME), "expectedOutcome").
			isEqualTo(new PairCounts(0, 0, 8, 0));
		requireThat(campaign.attempt(1).outcome(), "attempt.outcome").isEqualTo(Outcome.BELOW_INCUMBENT);
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.ACTIVE);
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.attempt(1).failures(), "failures").isEmpty();
		requireThat(CellTier.developmentCellIds(campaign), "developmentCells").contains("safety");
	}

	/**
	 * Verifies that the candidate formal trial that certifies a cell's minimum-pass-rate part starts the cell's control
	 * comparisons and waits for its control trial, and that a later candidate formal trial waits with the assertions
	 * that it passed.
	 */
	@Test
	public void formalTrialStartsComparisons()
	{
		// Requirement: R-F7
		Campaign campaign = new Builder().confirmed().working(1).certificationAttempt(1).
			cell("formatting", 100, 0).cell("plain_list", 100, 0).cell("safety", 99, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		TrialKey first = TransitionSteps.last(campaign);
		DrawnTestCase firstTestCase = testCaseOf(campaign, first);
		campaign = TransitionSteps.run(campaign, first, Verdict.PASS);

		requireThat(campaign.attempt(1).outcome(), "attempt.outcome").isNull();
		Pairing pairing = campaign.attempt(1).pairing(Reference.CONTROL, "safety");
		requireThat(pairing.comparisons(), "comparisons").isEqualTo(Map.of("asks_before_deleting",
			PairCounts.EMPTY, TestCase.EXPECTED_OUTCOME, PairCounts.EMPTY));
		Set<String> all = Set.of("asks_before_deleting", TestCase.EXPECTED_OUTCOME);
		requireThat(pairing.unpaired(), "unpaired").isEqualTo(List.of(new UnpairedTrial(firstTestCase, all)));

		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		TrialKey second = TransitionSteps.last(campaign);
		DrawnTestCase secondTestCase = testCaseOf(campaign, second);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(second));
		campaign = Transitions.apply(campaign, new CampaignEvent.Complete(second, Verdict.FAIL, "",
			List.of(new FailedAssertion(TestCase.EXPECTED_OUTCOME, "unexpected_outcome")), null, campaign.identity(),
			null, List.of(), Builder.FILES));
		requireThat(campaign.attempt(1).pairing(Reference.CONTROL, "safety").unpaired(), "secondUnpaired").
			isEqualTo(List.of(new UnpairedTrial(firstTestCase, all),
				new UnpairedTrial(secondTestCase, Set.of("asks_before_deleting"))));
		requireThat(campaign.attempt(1).pairing(Reference.INCUMBENT, "safety"), "incumbentPairing").
			isEqualTo(campaign.attempt(1).pairing(Reference.CONTROL, "safety"));
	}

	/**
	 * Verifies that the candidate formal trial that certifies a cell's minimum-pass-rate part starts only the cell's
	 * control comparisons when the candidate is the incumbent.
	 */
	@Test
	public void noIncumbentPairsForIncumbent()
	{
		// Requirement: R-F8
		Campaign campaign = new Builder().confirmed().certificationAttempt(1).cell("formatting", 100, 0).
			cell("plain_list", 100, 0).cell("safety", 99, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		campaign = TransitionSteps.run(campaign, TransitionSteps.last(campaign), Verdict.PASS);

		requireThat(campaign.attempt(1).pairing(Reference.CONTROL, "safety").isComparing(), "control").isTrue();
		requireThat(campaign.attempt(1).pairing(Reference.INCUMBENT, "safety"), "incumbent").isEqualTo(Pairing.EMPTY);
	}

	/**
	 * Returns the test case of an in-flight trial.
	 *
	 * @param campaign the campaign
	 * @param key the trial's key
	 * @return the test case
	 */
	private static DrawnTestCase testCaseOf(Campaign campaign, TrialKey key)
	{
		for (LogicalTrial trial : campaign.trials())
		{
			if (trial.key().equals(key))
				return trial.testCase();
		}
		throw new AssertionError("No trial has the key " + key);
	}

	/**
	 * Verifies that a failure after a cell's minimum-pass-rate part certified does not undo the certification, while
	 * the same counts without control comparisons leave the cell undecided.
	 */
	@Test
	public void certifiedMinimumPartStaysCertified()
	{
		// Requirement: R-F7
		PairCounts passed = new PairCounts(0, 7, 0, 0);
		CertificationAttempt compared = safetyAttempt(100, 1, comparing(passed, passed));
		requireThat(DecisionTable.cellDecision(compared, "safety"), "compared").isEqualTo(CellDecision.CERTIFIED);

		CertificationAttempt uncompared = safetyAttempt(100, 1, Pairing.EMPTY);
		requireThat(DecisionTable.cellDecision(uncompared, "safety"), "uncompared").
			isEqualTo(CellDecision.UNDECIDED);
	}

	/**
	 * Verifies that a cell at its cap whose control comparison is undecided reaches its cap once every candidate
	 * formal trial has its control trial, and ends the attempt inconclusive.
	 */
	@Test
	public void undecidedComparisonAtCapIsInconclusive()
	{
		// Requirement: R-F7
		PairCounts passed = new PairCounts(0, 7, 0, 0);
		PairCounts undecided = new PairCounts(0, 6, 1, 0);
		CertificationAttempt capped = safetyAttempt(296, 4, comparing(passed, undecided));
		requireThat(DecisionTable.cellDecision(capped, "safety"), "capped").isEqualTo(CellDecision.CAP_REACHED);
		requireThat(DecisionTable.attemptDecision(capped), "cappedAttempt").isEqualTo(AttemptDecision.INCONCLUSIVE);

		DrawnTestCase testCase = Builder.testCase("safety", "logs");
		Map<String, PairCounts> comparisons = new LinkedHashMap<>(comparing(passed, undecided).comparisons());
		Pairing waiting = new Pairing(List.of(new UnpairedTrial(testCase, Set.of())), comparisons);
		CertificationAttempt pending = safetyAttempt(296, 4, waiting);
		requireThat(DecisionTable.cellDecision(pending, "safety"), "pending").isEqualTo(CellDecision.UNDECIDED);
	}

	/**
	 * Verifies that a cell at its cap whose incumbent comparison is undecided reaches its cap once every candidate
	 * formal trial has its incumbent trial, whatever waits for a control trial whose comparisons passed.
	 */
	@Test
	public void incumbentUndecidedAtCapIsInconclusive()
	{
		// Requirement: R-F8
		PairCounts passed = new PairCounts(0, 7, 0, 0);
		PairCounts undecided = new PairCounts(0, 6, 1, 0);
		DrawnTestCase testCase = Builder.testCase("safety", "logs");
		Pairing passedWaiting = new Pairing(List.of(new UnpairedTrial(testCase, Set.of())),
			comparing(passed, passed).comparisons());
		CertificationAttempt capped = candidateAttempt(296, 4, passedWaiting, comparing(passed, undecided));
		requireThat(DecisionTable.cellDecision(capped, "safety"), "capped").isEqualTo(CellDecision.CAP_REACHED);
		requireThat(DecisionTable.attemptDecision(capped), "cappedAttempt").isEqualTo(AttemptDecision.INCONCLUSIVE);

		Pairing undecidedWaiting = new Pairing(List.of(new UnpairedTrial(testCase, Set.of())),
			comparing(passed, undecided).comparisons());
		CertificationAttempt pending = candidateAttempt(296, 4, comparing(passed, passed), undecidedWaiting);
		requireThat(DecisionTable.cellDecision(pending, "safety"), "pending").isEqualTo(CellDecision.UNDECIDED);
	}
}
