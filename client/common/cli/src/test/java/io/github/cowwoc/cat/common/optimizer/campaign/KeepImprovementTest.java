package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;

import java.util.List;
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
	 * Returns boundaries that keep the decisions apart: a category certifies at {@code 100 + 50·F} and is clearly
	 * below at 6 failures. {@code formatting} is proven better at {@code 60 + 40·F} and {@code safety} at
	 * {@code 150 + 40·F}; both are proven worse at 8 failures.
	 *
	 * @return the boundaries
	 */
	private static DecisionBoundaries boundaries()
	{
		return Boundaries.decision(Boundaries.STANDARD_CAP, 100, 50, 6,
			List.of(Boundaries.comparison(Boundaries.STANDARD_CAP, 60, 40, 8),
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
		return new CampaignBuilder().confirmed().certificationAttempt(1).boundaries(boundaries()).
			category("formatting", formattingPasses, formattingFailures).category("safety", safetyPasses,
				safetyFailures).outcome(Outcome.INCONCLUSIVE).build().attempt(1);
	}

	/**
	 * Verifies that an attempt qualifies only with at least one category proven better.
	 */
	@Test
	public void requiresProvenBetterCategory()
	{
		// Requirement: R-F6
		requireThat(KeepImprovement.qualifies(attempt(60, 0, 100, 0), 1), "better").isTrue();
		requireThat(KeepImprovement.qualifies(attempt(59, 0, 100, 0), 1), "notBetter").isFalse();
	}

	/**
	 * Verifies that an attempt with a category proven worse does not qualify.
	 */
	@Test
	public void rejectsProvenWorseCategory()
	{
		// Requirement: R-F6
		requireThat(KeepImprovement.qualifies(attempt(60, 0, 93, 7), 1), "notWorse").isTrue();
		requireThat(KeepImprovement.qualifies(attempt(60, 0, 92, 8), 1), "worse").isFalse();
	}

	/**
	 * Verifies that every other category must be certified or have at least 100 adopted outcomes.
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
	 * Verifies that an attempt under an earlier contract does not qualify.
	 */
	@Test
	public void earlierContractIsExcluded()
	{
		// Requirement: R-F6
		requireThat(KeepImprovement.qualifies(attempt(60, 0, 100, 0), 2), "earlierLineage").isFalse();
		Campaign campaign = new CampaignBuilder().confirmed(ContractFixtures.contract(), 2).certificationAttempt(1).
			lineage(1).boundaries(boundaries()).category("formatting", 60, 0).category("safety", 100, 0).
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
		for (Outcome outcome : List.of(Outcome.BELOW_MINIMUM, Outcome.INCONCLUSIVE, Outcome.PLATEAU,
			Outcome.TESTING_ENVIRONMENT_CHANGED, Outcome.STOPPED_FOR_CONTRACT_REVISION, Outcome.UNSUPPORTED_OUTPUT))
		{
			CertificationAttempt attempt = new CampaignBuilder().confirmed().certificationAttempt(1).
				boundaries(boundaries()).category("formatting", 60, 0).category("safety", 100, 0).outcome(outcome).
				build().attempt(1);
			requireThat(KeepImprovement.qualifies(attempt, 1), outcome.name()).isTrue();
		}
		for (Outcome outcome : List.of(Outcome.CERTIFIED, Outcome.INVALID_STATISTICAL_ASSUMPTIONS))
		{
			CertificationAttempt attempt = new CampaignBuilder().confirmed().certificationAttempt(1).
				boundaries(boundaries()).category("formatting", 60, 0).category("safety", 100, 0).outcome(outcome).
				build().attempt(1);
			requireThat(KeepImprovement.qualifies(attempt, 1), outcome.name()).isFalse();
		}
		CertificationAttempt running = new CampaignBuilder().confirmed().certificationAttempt(1).
			boundaries(boundaries()).category("formatting", 60, 0).category("safety", 100, 0).build().attempt(1);
		requireThat(KeepImprovement.qualifies(running, 1), "running").isFalse();
	}

	/**
	 * Returns a builder with two ended attempts under the current identity.
	 *
	 * @param first the passes of attempt 1 in {@code formatting} and {@code safety}
	 * @param second the passes of attempt 2 in {@code formatting} and {@code safety}
	 * @return the builder
	 */
	private static CampaignBuilder twoAttempts(int[] first, int[] second)
	{
		CampaignBuilder builder = new CampaignBuilder().confirmed();
		builder.certificationAttempt(1).boundaries(boundaries()).category("formatting", first[0], 0).
			category("safety", first[1], 0).outcome(Outcome.INCONCLUSIVE);
		builder.certificationAttempt(2).boundaries(boundaries()).category("formatting", second[0], 0).
			category("safety", second[1], 0).outcome(Outcome.INCONCLUSIVE);
		return builder;
	}

	/**
	 * Verifies that the best attempt has the highest Wilson lower bound on its weakest category, regardless of
	 * recency.
	 */
	@Test
	public void bestHasStrongestWeakestCategory()
	{
		// Requirement: R-F6
		Campaign campaign = twoAttempts(new int[]{80, 100}, new int[]{60, 100}).build();
		requireThat(KeepImprovement.offers(campaign), "offers").isEqualTo(List.of(new ImprovementOffer(1,
			campaign.identity(), false, List.of())));
	}

	/**
	 * Verifies that ties on the weakest category go to more categories proven better, regardless of recency.
	 */
	@Test
	public void tieGoesToMoreProvenBetterCategories()
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
		// Requirement: R-F6
		MeasurementIdentity earlier = CampaignBuilder.confirmedIdentity("model-a");
		MeasurementIdentity current = CampaignBuilder.confirmedIdentity("model-b");
		CampaignBuilder builder = twoAttempts(new int[]{200, 200}, new int[]{60, 100}).identity(
			CampaignBuilder.observed("model-b")).confirmed();
		builder.certificationAttempt(1).identity(earlier);
		builder.certificationAttempt(2).identity(current);
		Campaign campaign = builder.build();

		requireThat(KeepImprovement.offers(campaign), "offers").isEqualTo(List.of(
			new ImprovementOffer(2, current, false, List.of()),
			new ImprovementOffer(1, earlier, true, List.of(MeasurementCondition.MODEL))));
	}

	/**
	 * Verifies that a qualifying attempt stays on offer after the user chooses to optimize further, and is offered
	 * again at a later review checkpoint.
	 */
	@Test
	public void qualifyingAttemptStaysAvailable()
	{
		// Requirement: R-F6
		Campaign campaign = new CampaignBuilder().confirmed().working(1).certificationAttempt(1).
			boundaries(boundaries()).category("formatting", 60, 0).category("safety", 94, 5).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.FORMAL, "safety", Verdict.FAIL);
		requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);
		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.ImprovementProven(1));
		requireThat(campaign.choices(), "choices").containsExactly(List.of(Choice.KEEP_IMPROVEMENT,
			Choice.OPTIMIZE_FURTHER, Choice.STOP));

		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.OPTIMIZE_FURTHER));
		requireThat(campaign.pending(), "afterOptimize").isNull();
		requireThat(campaign.development().round(), "round").isNotNull();
		requireThat(KeepImprovement.offers(campaign), "offers").size().isEqualTo(1);

		Campaign checkpoint = new CampaignBuilder().confirmed().working(1).certificationAttempt(1).
			boundaries(boundaries()).category("formatting", 60, 0).category("safety", 94, 6).
			outcome(Outcome.BELOW_MINIMUM).campaign().development("formatting", 198, List.of(0, 0, 0, 2)).
			pending(new PendingDecision.CheckpointReached("formatting", 200)).build();
		requireThat(checkpoint.choices(), "checkpointChoices").containsExactly(List.of(Choice.MORE_EVIDENCE,
			Choice.TARGETED_OPTIMIZATION, Choice.STOP, Choice.KEEP_IMPROVEMENT));
	}

	/**
	 * Verifies that keeping an improvement is explicit, ends optimization, is labelled {@code improved, not
	 * certified} and delivers the chosen attempt's frozen snapshot.
	 */
	@Test
	public void keepDeliversChosenSnapshot()
	{
		// Requirement: R-F6
		Candidate frozen = new Candidate(1, CampaignBuilder.snapshot("candidate-1"));
		Campaign campaign = new CampaignBuilder().confirmed().working(2).certificationAttempt(1).candidate(frozen).
			boundaries(boundaries()).category("formatting", 60, 0).category("safety", 100, 0).
			outcome(Outcome.INCONCLUSIVE).campaign().pending(new PendingDecision.PlateauReached()).
			outcome(Outcome.PLATEAU).build();
		requireThat(campaign.choices(), "choices").containsExactly(List.of(Choice.KEEP_IMPROVEMENT,
			Choice.ACCEPT_WITHOUT_CERTIFICATION, Choice.STOP));

		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.keep(1));
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
			pending(new PendingDecision.PlateauReached()).build();
		CampaignTransitions.apply(campaign, CampaignEvent.Choose.keep(2));
	}
}
