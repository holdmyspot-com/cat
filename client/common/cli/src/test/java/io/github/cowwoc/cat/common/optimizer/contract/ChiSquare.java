package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.Map;

/**
 * Compares sampled counts with the expected probabilities in the generator and distribution tests.
 */
final class ChiSquare
{
	/**
	 * Reject a one-degree-of-freedom statistic at or above this 0.001 significance cutoff.
	 */
	static final double ONE_DEGREE_CUTOFF = 10.828;
	/**
	 * Reject a two-degree-of-freedom statistic at or above this 0.001 significance cutoff.
	 */
	static final double TWO_DEGREES_CUTOFF = 13.816;

	/**
	 * Computes the chi-square statistic of observed counts against expected probabilities.
	 *
	 * @param observed the count of each label, with an absent label counted as zero
	 * @param probabilities the expected positive probability of each label
	 * @param total the number of observations
	 * @return the sum of squared differences from expected counts, divided by each expected count
	 */
	static double statistic(Map<String, Integer> observed, Map<String, Double> probabilities, int total)
	{
		double statistic = 0;
		for (Map.Entry<String, Double> entry : probabilities.entrySet())
		{
			double expected = total * entry.getValue();
			double difference = observed.getOrDefault(entry.getKey(), 0) - expected;
			statistic += difference * difference / expected;
		}
		return statistic;
	}

	/**
	 * Prevents instantiation.
	 */
	private ChiSquare()
	{
	}
}
