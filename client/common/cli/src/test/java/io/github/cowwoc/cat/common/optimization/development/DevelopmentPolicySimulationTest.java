package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests deterministic simulation selection of the development policy.
 */
public final class DevelopmentPolicySimulationTest
{
	/**
	 * Creates the test fixture.
	 */
	public DevelopmentPolicySimulationTest()
	{
	}

	/**
	 * Verifies the selector rejects a cheap unsafe readiness rule and retains the least-cost eligible policy.
	 */
	@Test
	public void selectsLeastCostEligiblePolicy()
	{
		DevelopmentPolicy safe = new DevelopmentPolicy("safe-v1", 8, 4, 40, 1, 3, 3, 20, 1, 3, 0.01);
		DevelopmentPolicy reckless = new DevelopmentPolicy("reckless-v1", 2, 2, 4, 1, 1, 4, 2, 1, 1, 0.001);

		DevelopmentPolicySimulation.Result result = DevelopmentPolicySimulation.select(List.of(reckless, safe),
			parameters(0.70));

		requireThat(result.status(), "status").isEqualTo(DevelopmentPolicySimulation.Status.SELECTED);
		requireThat(result.selectedPolicy().orElseThrow().identifier(), "selectedPolicy").isEqualTo("safe-v1");
		DevelopmentPolicySimulation.Metrics recklessMetrics = result.metrics().stream().
			filter(metrics -> metrics.policyIdentifier().equals("reckless-v1")).findFirst().orElseThrow();
		requireThat(recklessMetrics.eligible(), "recklessEligible").isFalse();
	}

	/**
	 * Verifies unmet simulation constraints block selection rather than inventing policy thresholds.
	 */
	@Test
	public void blocksWhenNoPolicyMeetsTheContract()
	{
		DevelopmentPolicy policy = new DevelopmentPolicy("candidate-v1", 8, 4, 20, 1, 4, 3, 12, 1, 3, 0.01);

		DevelopmentPolicySimulation.Result result = DevelopmentPolicySimulation.select(List.of(policy),
			parameters(0.9999));

		requireThat(result.status(), "status").isEqualTo(DevelopmentPolicySimulation.Status.BLOCKED);
		requireThat(result.selectedPolicy(), "selectedPolicy").isEmpty();
		requireThat(result.metrics().getFirst().eligibilityFailures(), "eligibilityFailures").isNotEmpty();
	}

	/**
	 * Creates the fixed untouched simulation contract.
	 *
	 * @param minimumImprovementPromotionRate the required improved-candidate promotion rate
	 * @return the parameters
	 */
	private static DevelopmentPolicySimulation.Parameters parameters(double minimumImprovementPromotionRate)
	{
		return new DevelopmentPolicySimulation.Parameters(5_000, 2, 0.85, 0.99, 0.65, 0.99, 0.85,
			minimumImprovementPromotionRate, 0.05, 0.75, 0.30, 20_260_901L);
	}
}
