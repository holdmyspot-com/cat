package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.DependenceCondition;
import io.github.cowwoc.cat.common.optimizer.contract.DependenceDisposition;
import io.github.cowwoc.cat.common.optimizer.contract.DependenceSource;
import io.github.cowwoc.cat.common.optimizer.contract.FormalAssumptions;
import io.github.cowwoc.cat.common.optimizer.contract.FormalMethod;
import io.github.cowwoc.cat.common.optimizer.contract.FormalProcedure;
import io.github.cowwoc.cat.common.optimizer.contract.FreshSessionPolicy;
import io.github.cowwoc.cat.common.optimizer.contract.OutcomeModel;
import io.github.cowwoc.cat.common.optimizer.contract.StoppingRule;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests selection of a fixed sample plan from the reviewed formal-procedure values.
 */
public final class FixedSamplePlanContractTest
{
	/**
	 * Verifies the default procedure selects the recorded first and second lineage plans.
	 */
	@Test
	public void selectsDefaultLineagePlans()
	{
		FormalProcedure procedure = procedure("0.99", "0.90");
		FixedSamplePlan first = FixedSamplePlan.forProcedure(procedure, 1);
		FixedSamplePlan second = FixedSamplePlan.forProcedure(procedure, 2);

		requireThat(first.sampleSize(), "firstSampleSize").isEqualTo(173);
		requireThat(first.successCutoff(), "firstSuccessCutoff").isEqualTo(170);
		requireThat(second.sampleSize(), "secondSampleSize").isEqualTo(234);
		requireThat(second.successCutoff(), "secondSuccessCutoff").isEqualTo(230);
	}

	/**
	 * Verifies a user-revised decimal rate and power reach the exact planner without binary rounding.
	 */
	@Test
	public void usesRevisedPlanningValuesExactly()
	{
		FixedSamplePlan actual = FixedSamplePlan.forProcedure(procedure("0.985", "0.85"), 1);
		FixedSamplePlan expected = FixedSamplePlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(197, 200), probability(17, 20));

		requireThat(actual, "revisedPlan").isEqualTo(expected);
		requireThat(actual.sampleSize(), "revisedSampleSize").isNotEqualTo(173);
	}

	/**
	 * Verifies a formal candidate index cannot reset or omit the lineage allowance.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsZeroCandidateIndex()
	{
		FixedSamplePlan.forProcedure(procedure("0.99", "0.90"), 0);
	}

	/**
	 * Verifies the planner rejects a procedure whose planning rate cannot distinguish the threshold.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsUnvalidatedProcedure()
	{
		FixedSamplePlan.forProcedure(procedure("0.95", "0.90"), 1);
	}

	/**
	 * Creates a reviewed fixed-sample procedure with all required dependence disclosures.
	 *
	 * @param planningRate exact decimal planning rate
	 * @param minimumPower exact decimal minimum power
	 * @return formal procedure
	 */
	private static FormalProcedure procedure(String planningRate, String minimumPower)
	{
		List<DependenceCondition> conditions = List.of(DependenceSource.SHARED_SESSION,
			DependenceSource.PROVIDER_CACHE, DependenceSource.THROTTLING,
			DependenceSource.EVALUATOR_MEMORY, DependenceSource.ADAPTIVE_ROUTING).stream().map(source ->
			new DependenceCondition(source, DependenceDisposition.CONTROLLED, "test-evidence-" + source)).toList();
		FormalAssumptions assumptions = new FormalAssumptions(OutcomeModel.IID_BERNOULLI_WITHIN_CATEGORY,
			StoppingRule.FIXED_N_NO_EARLY_DECISION, conditions);
		return new FormalProcedure(FormalMethod.FIXED_SAMPLE_EXACT, new BigDecimal("0.95"),
			new BigDecimal(planningRate), new BigDecimal(minimumPower), "0.05/(i*(i+1))",
			FreshSessionPolicy.FRESH_PER_LOGICAL_TRIAL, assumptions);
	}

	/**
	 * Creates a reduced exact rational probability.
	 *
	 * @param numerator numerator
	 * @param denominator denominator
	 * @return exact probability
	 */
	private static RationalProbability probability(long numerator, long denominator)
	{
		return new RationalProbability(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
	}
}
