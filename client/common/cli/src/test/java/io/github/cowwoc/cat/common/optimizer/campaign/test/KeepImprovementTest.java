package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.Pairing;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.ImprovementOffer;
import io.github.cowwoc.cat.common.optimizer.campaign.KeepImprovement;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PairCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link KeepImprovement}.
 */
public final class KeepImprovementTest
{
	/**
	 * Creates the test.
	 */
	public KeepImprovementTest()
	{
	}

	/**
	 * Returns boundaries that distinguish the tested decisions. With {@code F} failures, the certification minimum
	 * is {@code 100 + 50·F} passes. The proven-better minimum is {@code 60 + 40·F} for {@code formatting} and
	 * {@code 150 + 40·F} for {@code safety}; {@code plain_list} is never proven better or worse, so the tests below
	 * certify it with 100 passes and decide the attempt by the other two cells. Each minimum is capped at
	 * {@code cap - F + 1}, where {@code cap} is the maximum total outcomes.
	 * A cell is clearly below with at least 6 failures and fewer passes than its certification minimum; it is proven
	 * worse with at least 8 failures and fewer passes than its proven-better minimum.
	 *
	 * @return the boundaries
	 */
	private static DecisionBoundaries boundaries()
	{
		return Boundaries.decision(Boundaries.STANDARD_CAP, 100, 50, 6,
			List.of(Boundaries.comparison(Boundaries.STANDARD_CAP, 60, 40, 8),
				Boundaries.comparison(Boundaries.STANDARD_CAP, 1000, 0, Boundaries.STANDARD_CAP + 1),
				Boundaries.comparison(Boundaries.STANDARD_CAP, 150, 40, 8)));
	}

	/**
	 * Returns an ended attempt under lineage 1.
	 *
	 * @param formattingPasses the passes in {@code formatting}
	 * @param formattingFailures the failures in {@code formatting}
	 * @param safetyPasses the passes in {@code safety}
	 * @param safetyFailures the failures in {@code safety}
	 * @return the attempt
	 */
	private static CertificationAttempt attempt(int formattingPasses, int formattingFailures, int safetyPasses,
		int safetyFailures)
	{
		return new Builder().confirmed().certificationAttempt(1).boundaries(boundaries()).
			cell("formatting", formattingPasses, formattingFailures).cell("plain_list", 100, 0).
			cell("safety", safetyPasses, safetyFailures).outcome(Outcome.INCONCLUSIVE).build().attempt(1);
	}

	/**
	 * Verifies that an attempt qualifies only with at least one test proven better.
	 */
	@Test
	public void requiresProvenBetterCell()
	{
		// Requirement: R-F6
		requireThat(KeepImprovement.qualifies(attempt(60, 0, 100, 0), 1), "better").isTrue();
		requireThat(KeepImprovement.qualifies(attempt(59, 0, 100, 0), 1), "notBetter").isFalse();
	}

	/**
	 * Verifies that one-sided missing measurement conditions do not qualify or enter the ranking of improvement
	 * offers, even when the formal counts otherwise satisfy every improvement condition.
	 */
	@Test
	public void incompleteIdentitiesAreExcluded()
	{
		// Requirement: R-M4, R-F6
		Campaign campaign = new Builder().confirmed().identity(MeasurementIdentity.EMPTY).
			certificationAttempt(1).baselineIdentity(Builder.confirmedIdentity("model-a")).
			boundaries(boundaries()).cell("formatting", 60, 0).cell("plain_list", 100, 0).
			cell("safety", 100, 0).outcome(Outcome.INCONCLUSIVE).campaign().certificationAttempt(2).
			baselineIdentity(Builder.confirmedIdentity("model-a")).
			boundaries(boundaries()).cell("formatting", 200, 0).cell("plain_list", 100, 0).cell("safety", 200, 0).
			outcome(Outcome.INCONCLUSIVE).build();

		requireThat(KeepImprovement.qualifies(campaign.attempt(1), 1), "qualifies").isFalse();
		requireThat(KeepImprovement.offers(campaign), "offers").isEmpty();
	}

	/**
	 * Verifies that an attempt with a test proven worse does not qualify.
	 */
	@Test
	public void rejectsProvenWorseCell()
	{
		// Requirement: R-F6
		requireThat(KeepImprovement.qualifies(attempt(60, 0, 93, 7), 1), "notWorse").isTrue();
		requireThat(KeepImprovement.qualifies(attempt(60, 0, 92, 8), 1), "worse").isFalse();
	}

	/**
	 * Verifies that every other test must be certified or have at least 100 adopted outcomes.
	 */
	@Test
	public void requiresRegressionCheck()
	{
		// Requirement: R-F6
		requireThat(KeepImprovement.qualifies(attempt(60, 0, 95, 5), 1), "hundredOutcomes").isTrue();
		requireThat(KeepImprovement.qualifies(attempt(60, 0, 94, 5), 1), "ninetyNineOutcomes").isFalse();
		requireThat(KeepImprovement.qualifies(attempt(60, 0, 99, 0), 1), "ninetyNineUncertified").isFalse();
		requireThat(KeepImprovement.qualifies(attempt(60, 0, 100, 0), 1), "certified").isTrue();
	}

	/**
	 * Verifies that a cell with fewer than 100 adopted outcomes counts as certified only once its control comparisons
	 * passed, not once its minimum-pass-rate part certified.
	 */
	@Test
	public void certifiedCellNeedsControlComparisons()
	{
		// Requirement: R-F6, R-F7
		BigDecimal paired = Boundaries.PAIRED_MINIMUM_PASS_RATE;
		DecisionBoundaries early = Boundaries.decision(Boundaries.STANDARD_CAP, 20, 50, 6,
			List.of(Boundaries.comparison(Boundaries.STANDARD_CAP, 60, 40, 8, paired),
				Boundaries.comparison(Boundaries.STANDARD_CAP, 1000, 0, Boundaries.STANDARD_CAP + 1, paired),
				Boundaries.comparison(Boundaries.STANDARD_CAP, 150, 40, 8, paired)), paired);
		CertificationAttempt compared = new Builder().confirmed().certificationAttempt(1).boundaries(early).
			cell("formatting", 60, 0).cell("plain_list", 20, 0).cell("safety", 150, 0).
			outcome(Outcome.BELOW_CONTROL).build().attempt(1);
		requireThat(KeepImprovement.qualifies(compared, 1), "compared").isTrue();

		Map<String, PairCounts> undecided = new LinkedHashMap<>();
		undecided.put("keeps_requested_tone", PairCounts.EMPTY);
		undecided.put(TestCase.EXPECTED_OUTCOME, PairCounts.EMPTY);
		CertificationAttempt uncompared = new Builder().confirmed().certificationAttempt(1).boundaries(early).
			cell("formatting", 60, 0).cell("plain_list", 20, 0).
			controlPairing("plain_list", new Pairing(List.of(), undecided)).cell("safety", 150, 0).
			outcome(Outcome.BELOW_CONTROL).build().attempt(1);
		requireThat(KeepImprovement.qualifies(uncompared, 1), "uncompared").isFalse();
	}

	/**
	 * Verifies that an attempt under an earlier contract does not qualify.
	 */
	@Test
	public void earlierContractIsExcluded()
	{
		// Requirement: R-F6
		requireThat(KeepImprovement.qualifies(attempt(60, 0, 100, 0), 2), "earlierLineage").isFalse();
		Campaign campaign = new Builder().confirmed(ContractFixtures.contract(), 2).certificationAttempt(1).
			lineage(1).boundaries(boundaries()).cell("formatting", 60, 0).cell("plain_list", 100, 0).
			cell("safety", 100, 0).
			outcome(Outcome.INCONCLUSIVE).build();
		requireThat(KeepImprovement.offers(campaign), "offers").isEmpty();
	}

	/**
	 * Verifies that a running, certified or assumption-invalid attempt does not qualify.
	 */
	@Test
	public void requiresUncertifiedEndedAttempt()
	{
		// Requirement: R-F6
		for (Outcome outcome : List.of(Outcome.BELOW_MINIMUM, Outcome.BELOW_CONTROL, Outcome.BELOW_INCUMBENT,
			Outcome.INCONCLUSIVE, Outcome.TESTING_ENVIRONMENT_CHANGED, Outcome.STOPPED_FOR_CONTRACT_REVISION,
			Outcome.UNSUPPORTED_OUTPUT))
		{
			CertificationAttempt attempt = new Builder().confirmed().certificationAttempt(1).
				boundaries(boundaries()).cell("formatting", 60, 0).cell("plain_list", 100, 0).cell("safety", 100, 0).
				outcome(outcome).build().attempt(1);
			requireThat(KeepImprovement.qualifies(attempt, 1), outcome.name()).isTrue();
		}
		for (Outcome outcome : List.of(Outcome.CERTIFIED, Outcome.INVALID_STATISTICAL_ASSUMPTIONS))
		{
			CertificationAttempt attempt = new Builder().confirmed().certificationAttempt(1).
				boundaries(boundaries()).cell("formatting", 60, 0).cell("plain_list", 100, 0).cell("safety", 100, 0).
				outcome(outcome).build().attempt(1);
			requireThat(KeepImprovement.qualifies(attempt, 1), outcome.name()).isFalse();
		}
		CertificationAttempt running = new Builder().confirmed().certificationAttempt(1).
			boundaries(boundaries()).cell("formatting", 60, 0).cell("plain_list", 100, 0).cell("safety", 100, 0).
			build().attempt(1);
		requireThat(KeepImprovement.qualifies(running, 1), "running").isFalse();
	}

	/**
	 * Returns a builder with two ended attempts under the current identity.
	 *
	 * @param first the passes of attempt 1 in {@code formatting} and {@code safety}
	 * @param second the passes of attempt 2 in {@code formatting} and {@code safety}
	 * @return the builder
	 */
	private static Builder twoAttempts(int[] first, int[] second)
	{
		Builder builder = new Builder().confirmed();
		builder.certificationAttempt(1).boundaries(boundaries()).cell("formatting", first[0], 0).
			cell("plain_list", 100, 0).
			cell("safety", first[1], 0).outcome(Outcome.INCONCLUSIVE);
		builder.certificationAttempt(2).boundaries(boundaries()).cell("formatting", second[0], 0).
			cell("plain_list", 100, 0).
			cell("safety", second[1], 0).outcome(Outcome.INCONCLUSIVE);
		return builder;
	}

	/**
	 * Verifies that the best attempt has the highest Wilson lower bound on its weakest test, regardless of
	 * recency.
	 */
	@Test
	public void bestHasStrongestWeakestCell()
	{
		// Requirement: R-F6
		Campaign campaign = twoAttempts(new int[]{80, 100}, new int[]{60, 100}).build();
		requireThat(KeepImprovement.offers(campaign), "offers").isEqualTo(List.of(new ImprovementOffer(1,
			campaign.identity(), false, List.of())));
	}

	/**
	 * Verifies that ties on the weakest test go to more tests proven better, regardless of recency.
	 */
	@Test
	public void tieGoesToMoreProvenBetterCells()
	{
		// Requirement: R-F6
		Campaign campaign = twoAttempts(new int[]{60, 150}, new int[]{60, 100}).build();
		requireThat(KeepImprovement.offers(campaign), "offers").isEqualTo(List.of(new ImprovementOffer(1,
			campaign.identity(), false, List.of())));
	}

	/**
	 * Verifies that remaining ties go to the more recent attempt.
	 */
	@Test
	public void tieGoesToMoreRecentAttempt()
	{
		// Requirement: R-F6
		Campaign campaign = twoAttempts(new int[]{60, 100}, new int[]{60, 100}).build();
		requireThat(KeepImprovement.offers(campaign), "offers").isEqualTo(List.of(new ImprovementOffer(2,
			campaign.identity(), false, List.of())));
	}

	/**
	 * Verifies that attempts under two measurement identities are offered separately, the current identity first,
	 * and that the earlier one is labelled historical with its differing conditions, even when it looks stronger.
	 */
	@Test
	public void identitiesAreOfferedSeparately()
	{
		// Requirement: R-F6, R-M4
		MeasurementIdentity earlier = Builder.confirmedIdentity("model-a");
		MeasurementIdentity current = Builder.confirmedIdentity("model-b");
		Builder builder = twoAttempts(new int[]{200, 200}, new int[]{60, 100}).identity(
			Builder.observed("model-b")).confirmed();
		builder.certificationAttempt(1).identity(earlier);
		builder.certificationAttempt(2).identity(current);
		Campaign campaign = builder.build();

		requireThat(KeepImprovement.offers(campaign), "offers").isEqualTo(List.of(
			new ImprovementOffer(2, current, false, List.of()),
			new ImprovementOffer(1, earlier, true, List.of(MeasurementCondition.MODEL))));
	}

	/**
	 * Verifies that a qualifying attempt stays on offer after the user chooses to optimize further, which returns to
	 * development without the attempt's held-out failures, and is offered again at a later review checkpoint.
	 */
	@Test
	public void qualifyingAttemptStaysAvailable()
	{
		// Requirement: R-F6, R-D9
		Campaign campaign = new Builder().confirmed().working(1).certificationAttempt(1).
			boundaries(boundaries()).cell("formatting", 60, 0).cell("plain_list", 100, 0).cell("safety", 94, 5).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.FORMAL, "safety", Verdict.FAIL);
		requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);
		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.ImprovementProven(1));
		requireThat(campaign.choices(), "choices").containsExactly(List.of(Choice.KEEP_IMPROVEMENT,
			Choice.OPTIMIZE_FURTHER, Choice.STOP));

		campaign = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.OPTIMIZE_FURTHER));
		requireThat(campaign.pending(), "afterOptimize").isNull();
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(campaign.development().failures(), "developmentFailures").isEmpty();
		requireThat(KeepImprovement.offers(campaign), "offers").size().isEqualTo(1);

		Campaign checkpoint = new Builder().confirmed().working(1).certificationAttempt(1).
			boundaries(boundaries()).cell("formatting", 60, 0).cell("plain_list", 100, 0).cell("safety", 94, 6).
			outcome(Outcome.BELOW_MINIMUM).campaign().development("formatting", 198, List.of(0, 0, 0, 2)).
			pending(new PendingDecision.CheckpointReached("formatting", 200)).build();
		requireThat(checkpoint.choices(), "checkpointChoices").containsExactly(List.of(Choice.MORE_EVIDENCE,
			Choice.TARGETED_OPTIMIZATION, Choice.STOP, Choice.KEEP_IMPROVEMENT, Choice.ACCEPT_WITHOUT_CERTIFICATION));
	}

	/**
	 * Verifies that keeping an improvement is explicit, ends optimization, is labelled {@code improved, not
	 * certified} and delivers the chosen attempt's frozen snapshot.
	 */
	@Test
	public void keepDeliversChosenSnapshot()
	{
		// Requirement: R-F6
		Candidate frozen = new Candidate(1, Builder.snapshot("candidate-1"));
		Campaign campaign = new Builder().confirmed().working(2).certificationAttempt(1).candidate(frozen).
			boundaries(boundaries()).cell("formatting", 60, 0).cell("plain_list", 100, 0).cell("safety", 100, 0).
			outcome(Outcome.INCONCLUSIVE).campaign().pending(new PendingDecision.CheckpointReached("safety", 200)).
			build();
		requireThat(campaign.choices(), "choices").containsExactly(List.of(Choice.MORE_EVIDENCE,
			Choice.TARGETED_OPTIMIZATION, Choice.KEEP_IMPROVEMENT, Choice.ACCEPT_WITHOUT_CERTIFICATION, Choice.STOP));

		campaign = Transitions.apply(campaign, CampaignEvent.Choose.keep(1));
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.FINISHED);
		requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.IMPROVED_NOT_CERTIFIED);
		requireThat(campaign.outcome().label(), "label").isEqualTo("improved, not certified");
		requireThat(campaign.delivery(), "delivery").isEqualTo(new DeliveryRequest(frozen, 1,
			Outcome.IMPROVED_NOT_CERTIFIED));
	}

	/**
	 * Verifies that only an offered attempt can be kept.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void onlyOfferedAttemptCanBeKept()
	{
		// Requirement: R-F6
		Campaign campaign = twoAttempts(new int[]{80, 100}, new int[]{60, 100}).
			pending(new PendingDecision.CheckpointReached("safety", 200)).build();
		Transitions.apply(campaign, CampaignEvent.Choose.keep(2));
	}
}
