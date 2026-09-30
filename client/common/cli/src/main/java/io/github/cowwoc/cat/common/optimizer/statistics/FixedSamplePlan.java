package io.github.cowwoc.cat.common.optimizer.statistics;

import io.github.cowwoc.cat.common.optimizer.contract.ContractValidator;
import io.github.cowwoc.cat.common.optimizer.contract.FormalProcedure;

/**
 * A fixed number of trials and the confidence test applied after all of them complete.
 *
 * @param alpha the one-sided false-positive allowance
 * @param threshold the exact compliance probability that bounds must exceed
 * @param sampleSize the required number of trials per category
 * @param successCutoff the smallest number of successes that strictly exceeds the threshold, or one above the sample
 * 	size when no outcome can do so
 */
public record FixedSamplePlan(RationalProbability alpha, RationalProbability threshold, int sampleSize,
	int successCutoff)
{
	/**
	 * Selects the fixed plan specified by a formal procedure and lineage candidate index.
	 *
	 * @param procedure the reviewed formal procedure
	 * @param candidateIndex the one-based formal candidate index within a lineage
	 * @return the fixed sample plan for each category
	 */
	public static FixedSamplePlan forProcedure(FormalProcedure procedure, int candidateIndex)
	{
		ContractValidator.validateProcedure(procedure);
		return forPower(LineageAllowance.forCandidate(candidateIndex),
			RationalProbability.fromDecimalExact(procedure.threshold()),
			RationalProbability.fromDecimalExact(procedure.planningRate()),
			RationalProbability.fromDecimalExact(procedure.minimumPower()));
	}

	/**
	 * Validates a fixed sample plan.
	 */
	public FixedSamplePlan
	{
		validatePlanParameters(alpha, threshold);
		if (sampleSize <= 0 || sampleSize == Integer.MAX_VALUE)
			throw new IllegalArgumentException("sampleSize must be positive and leave room for a no-pass cutoff");
		if (successCutoff != findSignificantCutoff(alpha, threshold, sampleSize))
			throw new IllegalArgumentException("successCutoff must be the smallest strictly significant cutoff");
	}

	/**
	 * Returns the smallest fixed all-pass plan whose lower bound strictly exceeds a threshold.
	 *
	 * @param alpha the one-sided false-positive allowance
	 * @param threshold the exact compliance probability that bounds must exceed
	 * @return the smallest all-pass fixed sample plan
	 */
	public static FixedSamplePlan forAllPass(RationalProbability alpha, RationalProbability threshold)
	{
		validatePlanParameters(alpha, threshold);

		for (int sampleSize = 1; ; ++sampleSize)
		{
			if (BinomialLowerBound.strictlyExceeds(sampleSize, sampleSize, alpha, threshold))
				return new FixedSamplePlan(alpha, threshold, sampleSize, sampleSize);
		}
	}

	/**
	 * Selects a fixed plan for a specified compliance rate and minimum planning power.
	 *
	 * @param alpha the one-sided false-positive allowance
	 * @param threshold the compliance probability the lower bound must exceed
	 * @param expectedCompliance the success probability used for power planning
	 * @param minimumPower the minimum probability of certification at the expected compliance rate
	 * @return the selected fixed sample plan
	 */
	public static FixedSamplePlan forPower(RationalProbability alpha, RationalProbability threshold,
		RationalProbability expectedCompliance, RationalProbability minimumPower)
	{
		validatePlanParameters(alpha, threshold);
		if (expectedCompliance == null || expectedCompliance.compareTo(threshold) <= 0)
			throw new IllegalArgumentException("expectedCompliance must exceed threshold");
		if (minimumPower == null || minimumPower.numerator().signum() <= 0 ||
			minimumPower.numerator().compareTo(minimumPower.denominator()) >= 0)
		{
			throw new IllegalArgumentException("minimumPower must be between zero and one");
		}

		for (int sampleSize = 1; sampleSize > 0; ++sampleSize)
		{
			BinomialTail atThreshold = new BinomialTail(sampleSize, threshold);
			BinomialTail atExpectedCompliance = new BinomialTail(sampleSize, expectedCompliance);
			boolean powerPossible = true;
			for (int cutoff = 1; cutoff <= sampleSize && powerPossible; ++cutoff)
			{
				atThreshold.advance();
				atExpectedCompliance.advance();
				powerPossible = atExpectedCompliance.compareTo(minimumPower) >= 0;
				if (powerPossible && atThreshold.compareTo(alpha) < 0)
					return new FixedSamplePlan(alpha, threshold, sampleSize, cutoff);
			}
		}
		throw new IllegalArgumentException("No fixed sample plan fits a representable sample size");
	}

	/**
	 * Finds the smallest success count whose exact upper-tail probability is below the allowance.
	 *
	 * @param alpha the one-sided false-positive allowance
	 * @param threshold the compliance probability the lower bound must exceed
	 * @param sampleSize the fixed number of trials
	 * @return the smallest passing cutoff, or one above the sample size if none can pass
	 */
	private static int findSignificantCutoff(RationalProbability alpha, RationalProbability threshold, int sampleSize)
	{
		BinomialTail tail = new BinomialTail(sampleSize, threshold);
		for (int cutoff = 1; cutoff <= sampleSize; ++cutoff)
		{
			tail.advance();
			if (tail.compareTo(alpha) < 0)
				return cutoff;
		}
		return sampleSize + 1;
	}

	/**
	 * Validates the false-positive allowance and compliance threshold shared by plan factories.
	 *
	 * @param alpha the one-sided false-positive allowance
	 * @param threshold the compliance probability the lower bound must exceed
	 */
	private static void validatePlanParameters(RationalProbability alpha, RationalProbability threshold)
	{
		if (alpha == null || alpha.numerator().signum() <= 0 ||
			alpha.numerator().compareTo(alpha.denominator()) >= 0)
		{
			throw new IllegalArgumentException("alpha must be between zero and one");
		}
		if (threshold == null || threshold.numerator().signum() <= 0 ||
			threshold.numerator().compareTo(threshold.denominator()) >= 0)
		{
			throw new IllegalArgumentException("threshold must be between zero and one");
		}
	}
}
