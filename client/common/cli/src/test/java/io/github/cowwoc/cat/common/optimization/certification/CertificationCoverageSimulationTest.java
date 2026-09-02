package io.github.cowwoc.cat.common.optimization.certification;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

/**
 * Tests the adversarial lineage-wide false-certification simulation.
 */
public final class CertificationCoverageSimulationTest
{
	/**
	 * Creates the test fixture.
	 */
	public CertificationCoverageSimulationTest()
	{
	}

	/**
	 * Verifies repeated candidate selection at the null boundary retains a one-sided upper error bound at or below 5%.
	 */
	@Test
	public void controlsRepeatedCandidateSelection()
	{
		CertificationCoverageSimulation.Parameters parameters = new CertificationCoverageSimulation.Parameters(
			20_000, 5, 300, 0.95, 0.95, 0.05, 0.05, 20_260_901L);

		CertificationCoverageSimulation.Result result = CertificationCoverageSimulation.run(parameters);

		requireThat(result.oneSidedUpperFalseCertificationRate(), "oneSidedUpperFalseCertificationRate").
			isLessThanOrEqualTo(0.05);
		requireThat(result.assignedAllowance(), "assignedAllowance").isLessThan(0.05);
		requireThat(result.falseCertifications(), "falseCertifications").isGreaterThanOrEqualTo(0);
	}

	/**
	 * Verifies a lower-compliance adversary cannot produce a higher simulated false-certification rate for the same seed.
	 */
	@Test
	public void worsensNoMoreBelowTheBoundary()
	{
		CertificationCoverageSimulation.Result boundary = CertificationCoverageSimulation.run(
			new CertificationCoverageSimulation.Parameters(5_000, 3, 250, 0.95, 0.95, 0.05, 0.05, 41));
		CertificationCoverageSimulation.Result below = CertificationCoverageSimulation.run(
			new CertificationCoverageSimulation.Parameters(5_000, 3, 250, 0.90, 0.95, 0.05, 0.05, 41));

		requireThat(below.falseCertifications(), "below.falseCertifications()").
			isLessThanOrEqualTo(boundary.falseCertifications());
	}
}
