package io.github.cowwoc.cat.common.optimization.certification;


import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests simultaneous weakest-family certification without substantive family weighting.
 */
public final class FamilyCertificationTest
{
	/**
	 * Creates the test fixture.
	 */
	public FamilyCertificationTest()
	{
	}

	/**
	 * Verifies every family uses the full candidate allowance and the smallest family bound becomes the current lower
	 * bound.
	 */
	@Test
	public void certifiesTheWeakestFamily()
	{
		FamilyCertification.Result result = FamilyCertification.evaluate(List.of(
			new FamilyCertification.Evidence("direct-instruction", 96, 96),
			new FamilyCertification.Evidence("cross-file-interaction", 120, 120)), 0.05, 0.95);

		requireThat(result.status(), "status").isEqualTo(FamilyCertification.Status.CERTIFIED);
		requireThat(result.currentLowerBound(), "currentLowerBound").isGreaterThan(0.95);
		requireThat(result.currentLowerBound(), "currentLowerBound").isEqualTo(
			result.familyBounds().get("direct-instruction"));
		requireThat(result.familyBounds().get("direct-instruction"), "directInstructionBound").isEqualTo(
			Statistics.sequentialLowerConfidenceBound(96, 96, 0.05));
	}

	/**
	 * Verifies a strong family cannot average away a weak family's lower bound.
	 */
	@Test
	public void doesNotAverageFamilyCompliance()
	{
		FamilyCertification.Result result = FamilyCertification.evaluate(List.of(
			new FamilyCertification.Evidence("strong", 200, 200),
			new FamilyCertification.Evidence("weak", 50, 100)), 0.05, 0.95);

		requireThat(result.status(), "status").isEqualTo(FamilyCertification.Status.NOT_CERTIFIED);
		requireThat(result.currentLowerBound(), "currentLowerBound").isEqualTo(result.familyBounds().get("weak"));
		requireThat(result.currentLowerBound(), "currentLowerBound").isLessThan(0.95);
	}

	/**
	 * Verifies a candidate with no family outcomes has no reportable current lower bound.
	 */
	@Test
	public void reportsUnavailableWithoutEvidence()
	{
		FamilyCertification.Result result = FamilyCertification.evaluate(List.of(), 0.05, 0.95);

		requireThat(result.status(), "status").isEqualTo(FamilyCertification.Status.EVIDENCE_UNAVAILABLE);
		requireThat(result.familyBounds(), "familyBounds").isEmpty();
	}

	/**
	 * Verifies two declarations cannot silently replace one family result.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Duplicate family identifier: repeated.*")
	public void rejectsDuplicateFamilyIdentifiers()
	{
		FamilyCertification.evaluate(List.of(new FamilyCertification.Evidence("repeated", 1, 1),
			new FamilyCertification.Evidence("repeated", 1, 1)), 0.05, 0.95);
	}
}
