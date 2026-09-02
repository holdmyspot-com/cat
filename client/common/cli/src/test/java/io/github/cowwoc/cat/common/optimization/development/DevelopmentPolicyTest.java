package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests executable paired-comparison, readiness, and plateau decisions.
 */
public final class DevelopmentPolicyTest
{
	private final DevelopmentPolicy policy = new DevelopmentPolicy("development-v1", 6, 4, 18, 1, 3, 2, 4, 1, 3,
		0.01);

	/**
	 * Creates the test fixture.
	 */
	public DevelopmentPolicyTest()
	{
	}

	/**
	 * Verifies insufficient paired evidence requests the manifest's exact increment.
	 */
	@Test
	public void requestsMorePairs()
	{
		DevelopmentPolicy.ComparisonDecision decision = policy.compare(List.of(
			new DevelopmentPolicy.FamilyPairs("clarity", 2, 1, 1)));

		requireThat(decision.action(), "action").isEqualTo(DevelopmentPolicy.ComparisonAction.MORE_PAIRS);
		requireThat(decision.additionalPairs(), "additionalPairs").isEqualTo(2);
	}

	/**
	 * Verifies a challenger with sufficient paired wins and no material regression is promoted.
	 */
	@Test
	public void promotesChallenger()
	{
		DevelopmentPolicy.ComparisonDecision decision = policy.compare(List.of(
			new DevelopmentPolicy.FamilyPairs("clarity", 5, 0, 1),
			new DevelopmentPolicy.FamilyPairs("architecture", 4, 1, 1)));

		requireThat(decision.action(), "action").isEqualTo(DevelopmentPolicy.ComparisonAction.PROMOTE);
	}

	/**
	 * Verifies selection-aware promotion requires every family to establish the challenger advantage.
	 */
	@Test
	public void promotesOnlyWhenEveryFamilyHasEvidence()
	{
		DevelopmentPolicy.ComparisonDecision decision = policy.compare(List.of(
			new DevelopmentPolicy.FamilyPairs("clarity", 100, 0, 0),
			new DevelopmentPolicy.FamilyPairs("architecture", 100, 0, 0)), 0.025);

		requireThat(decision.action(), "action").isEqualTo(DevelopmentPolicy.ComparisonAction.PROMOTE);
	}

	/**
	 * Verifies selection-aware promotion identifies a family that establishes the loss direction.
	 */
	@Test
	public void repairsEstablishedFamilyRegression()
	{
		DevelopmentPolicy.ComparisonDecision decision = policy.compare(List.of(
			new DevelopmentPolicy.FamilyPairs("clarity", 100, 0, 0),
			new DevelopmentPolicy.FamilyPairs("architecture", 0, 100, 0)), 0.025);

		requireThat(decision.action(), "action").isEqualTo(DevelopmentPolicy.ComparisonAction.REPAIR_REGRESSION);
		requireThat(decision.affectedFamilies(), "affectedFamilies").containsExactly(List.of("architecture"));
	}

	/**
	 * Verifies a named family regression is repaired before promotion.
	 */
	@Test
	public void repairsMaterialRegression()
	{
		DevelopmentPolicy.ComparisonDecision decision = policy.compare(List.of(
			new DevelopmentPolicy.FamilyPairs("clarity", 5, 0, 1),
			new DevelopmentPolicy.FamilyPairs("architecture", 1, 4, 1)));

		requireThat(decision.action(), "action").isEqualTo(DevelopmentPolicy.ComparisonAction.REPAIR_REGRESSION);
		requireThat(decision.affectedFamilies(), "affectedFamilies").containsExactly(List.of("architecture"));
	}

	/**
	 * Verifies readiness starts formal evidence only after every family meets the frozen batch rule.
	 */
	@Test
	public void startsFormalBatchWhenReady()
	{
		DevelopmentPolicy.ReadinessDecision decision = policy.readiness(List.of(
			new DevelopmentPolicy.FamilyReadiness("clarity", 4, 4),
			new DevelopmentPolicy.FamilyReadiness("architecture", 3, 4)));

		requireThat(decision.action(), "action").isEqualTo(DevelopmentPolicy.ReadinessAction.START_FORMAL_BATCH);
	}

	/**
	 * Verifies the limiting family is named when readiness has too many failures.
	 */
	@Test
	public void continuesDevelopmentForWeakFamily()
	{
		DevelopmentPolicy.ReadinessDecision decision = policy.readiness(List.of(
			new DevelopmentPolicy.FamilyReadiness("clarity", 4, 4),
			new DevelopmentPolicy.FamilyReadiness("architecture", 2, 4)));

		requireThat(decision.action(), "action").isEqualTo(DevelopmentPolicy.ReadinessAction.CONTINUE_DEVELOPMENT);
		requireThat(decision.limitingFamilies(), "limitingFamilies").containsExactly(List.of("architecture"));
	}

	/**
	 * Verifies repeated improvements below the policy threshold produce a plateau.
	 */
	@Test
	public void detectsPlateau()
	{
		DevelopmentPolicy.PlateauDecision decision = policy.plateau(List.of(0.81, 0.815, 0.817, 0.819));

		requireThat(decision.plateau(), "plateau").isTrue();
	}
}
