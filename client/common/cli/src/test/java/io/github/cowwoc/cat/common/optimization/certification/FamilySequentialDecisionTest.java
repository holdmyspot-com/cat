package io.github.cowwoc.cat.common.optimization.certification;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests family-specific formal sampling and terminal certification decisions.
 */
public final class FamilySequentialDecisionTest
{
	private static final FamilySequentialDecision.Policy POLICY = new FamilySequentialDecision.Policy(0.95, 300);
	private static final CandidateAllowanceLedger.Assignment ALLOWANCE = new CandidateAllowanceLedger.Assignment(
		"candidate-a", 1, new BigDecimal("0.025"), Instant.parse("2026-09-01T12:00:00Z"), "ROOT", "entry-a");

	/**
	 * Creates the test fixture.
	 */
	public FamilySequentialDecisionTest()
	{
	}

	/**
	 * Verifies a resolved family stops receiving trials while an unresolved family remains scheduled.
	 */
	@Test
	public void schedulesTheLimitingFamily()
	{
		FamilySequentialDecision.Result result = FamilySequentialDecision.evaluate(List.of(
			new FamilyCertification.Evidence("resolved", 180, 180),
			new FamilyCertification.Evidence("unresolved", 4, 4)), ALLOWANCE, POLICY);

		requireThat(result.action(), "action").isEqualTo(FamilySequentialDecision.Action.SAMPLE_FAMILY);
		requireThat(result.familyId().orElseThrow(), "familyId").isEqualTo("unresolved");
		requireThat(result.certification().status(), "certification.status()").
			isEqualTo(FamilyCertification.Status.NOT_CERTIFIED);
	}

	/**
	 * Verifies unequal family counts can certify when every family crosses its own boundary.
	 */
	@Test
	public void certifiesUnequalFamilyTrialCounts()
	{
		FamilySequentialDecision.Result result = FamilySequentialDecision.evaluate(List.of(
			new FamilyCertification.Evidence("family-a", 180, 180),
			new FamilyCertification.Evidence("family-b", 220, 220)), ALLOWANCE, POLICY);

		requireThat(result.action(), "action").isEqualTo(FamilySequentialDecision.Action.CERTIFIED);
		requireThat(result.familyId(), "familyId").isEmpty();
		requireThat(result.certification().currentLowerBound(), "currentLowerBound").isGreaterThan(0.95);
	}

	/**
	 * Verifies no lower bound is reported before the new contract has any formal outcome.
	 */
	@Test
	public void reportsUnavailableBeforeFormalEvidence()
	{
		FamilySequentialDecision.Result result = FamilySequentialDecision.evaluate(List.of(
			new FamilyCertification.Evidence("family-a", 0, 0),
			new FamilyCertification.Evidence("family-b", 0, 0)), ALLOWANCE, POLICY);

		requireThat(result.action(), "action").isEqualTo(FamilySequentialDecision.Action.SAMPLE_FAMILY);
		requireThat(result.certification().status(), "certification.status()").
			isEqualTo(FamilyCertification.Status.EVIDENCE_UNAVAILABLE);
	}

	/**
	 * Verifies a family that cannot cross within the policy cap terminates as futile.
	 */
	@Test
	public void rejectsAStatisticallyFutileCandidate()
	{
		FamilySequentialDecision.Result result = FamilySequentialDecision.evaluate(List.of(
			new FamilyCertification.Evidence("family-a", 50, 100),
			new FamilyCertification.Evidence("family-b", 100, 100)), ALLOWANCE,
			new FamilySequentialDecision.Policy(0.95, 100));

		requireThat(result.action(), "action").isEqualTo(FamilySequentialDecision.Action.FUTILE);
		requireThat(result.familyId().orElseThrow(), "familyId").isEqualTo("family-a");
	}
}
