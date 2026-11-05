package com.holdmyspot.cat.common.optimizer.campaign.test;

import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.statistics.Comparison;
import com.holdmyspot.cat.common.optimizer.statistics.ComparisonBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.ErrorBudget;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds handwritten decision boundaries so campaign-policy tests need no statistical boundary computation.
 * <p>
 * For {@code failures} failures, the minimum certifying (or proven-better) pass count is
 * {@code min(base + step * failures, cap - failures + 1)}. The second term marks a minimum that cannot be reached
 * within the outcome cap. A count is clearly below (or proven worse) only when its failures reach the specified
 * threshold and its passes are below that minimum.
 */
public final class Boundaries
{
	/**
	 * The cap of {@link #standard(int)}.
	 */
	public static final int STANDARD_CAP = 300;
	/**
	 * A minimum pass rate below {@code 1 - PairedComparison.MARGIN}, at which certification compares each assertion
	 * with control and incumbent trials.
	 */
	public static final BigDecimal PAIRED_MINIMUM_PASS_RATE = new BigDecimal("0.90");
	/**
	 * The cap of {@link #paired(int)}.
	 */
	public static final int PAIRED_CAP = 60;
	/**
	 * The certification level of {@link #paired(int)}: an error probability of {@code 1 / 2}, at which a paired
	 * comparison passes after 22 pairs in which both trials pass.
	 */
	private static final SignificanceLevel PAIRED_LEVEL = new SignificanceLevel(BigInteger.TWO);

	/**
	 * Prevents instantiation.
	 */
	private Boundaries()
	{
	}

	/**
	 * Returns the boundaries that {@link Builder} uses by default.
	 * <p>
	 * With a cap of {@value #STANDARD_CAP} outcomes, a cell certifies at {@code 100 + 50 * failures} passes when that
	 * count is reachable within the cap, and is clearly below once it has 5 failures. It is proven better at
	 * {@code 60 + 40 * failures} passes when reachable, and proven worse at 4 or more failures only if its pass count
	 * is below the proven-better minimum.
	 *
	 * @param cells the number of cells requiring comparison boundaries
	 * @return the boundaries
	 */
	public static DecisionBoundaries standard(int cells)
	{
		return standard(cells, Contract.DEFAULT_MINIMUM_PASS_RATE);
	}

	/**
	 * Returns the boundaries of {@link #standard(int)} at another minimum pass rate.
	 *
	 * @param cells the number of cells requiring comparison boundaries
	 * @param minimumPassRate the minimum pass rate {@code p_min}
	 * @return the boundaries
	 */
	public static DecisionBoundaries standard(int cells, BigDecimal minimumPassRate)
	{
		List<ComparisonBoundaries> comparisons = new ArrayList<>();
		for (int i = 0; i < cells; ++i)
			comparisons.add(comparison(STANDARD_CAP, 60, 40, 4, minimumPassRate));
		return decision(STANDARD_CAP, 100, 50, 5, comparisons, minimumPassRate);
	}

	/**
	 * Returns boundaries at the minimum pass rate {@link #PAIRED_MINIMUM_PASS_RATE}, at which certification compares
	 * each assertion with control and incumbent trials, scaled down so that the paired comparisons need few pairs.
	 * <p>
	 * A paired comparison's e-value is exact integer arithmetic whose cost grows with its pairs and with the pairs
	 * that the cap still permits, and the driver evaluates it for every comparison at every step. These boundaries
	 * keep both small for tests whose claim is how the driver acts on comparisons rather than how many pairs they
	 * need: the certification level is {@code 1 / 2} rather than an attempt's, and the cap is {@value #PAIRED_CAP}
	 * outcomes. A cell certifies at {@code 20 + 10 * failures} passes when that count is reachable within the cap and
	 * is clearly below once it has 5 failures. It is proven better at {@code 12 + 8 * failures} passes when reachable,
	 * and proven worse at 4 or more failures only if its pass count is below the proven-better minimum.
	 *
	 * @param cells the number of cells requiring comparison boundaries
	 * @return the boundaries
	 */
	public static DecisionBoundaries paired(int cells)
	{
		return pairedDecision(20, 10, 5, cells);
	}

	/**
	 * Returns the boundaries of {@link #paired(int)} that certify at {@code 20 + 9 * failures} passes when reachable
	 * within the cap of {@value #PAIRED_CAP}. Unlike {@link #paired(int)}, 4 failures still leave certification
	 * within reach, at 56 passes, and a fifth failure puts it out of reach.
	 *
	 * @param cells the number of cells requiring comparison boundaries
	 * @param belowFailures the minimum failures for a clearly-below decision when passes are below the certifying
	 *   minimum
	 * @return the boundaries
	 */
	public static DecisionBoundaries pairedReachableAtFourFailures(int cells, int belowFailures)
	{
		return pairedDecision(20, 9, belowFailures, cells);
	}

	/**
	 * Returns boundaries at the cap and certification level of {@link #paired(int)}.
	 *
	 * @param base the passes that certify with no failures
	 * @param step the additional passes that each failure requires
	 * @param belowFailures the minimum failures for a clearly-below decision when passes are below the certifying minimum
	 * @param cells the number of cells requiring comparison boundaries
	 * @return the boundaries
	 */
	private static DecisionBoundaries pairedDecision(int base, int step, int belowFailures, int cells)
	{
		List<ComparisonBoundaries> comparisons = new ArrayList<>();
		for (int i = 0; i < cells; ++i)
			comparisons.add(comparison(PAIRED_CAP, 12, 8, 4, PAIRED_MINIMUM_PASS_RATE));
		List<Integer> minimums = minimums(PAIRED_CAP, base, step);
		return new DecisionBoundaries(PAIRED_LEVEL, PAIRED_MINIMUM_PASS_RATE, PAIRED_CAP, minimums,
			maximums(PAIRED_CAP, minimums, belowFailures), comparisons);
	}

	/**
	 * Returns boundaries that certify at {@code 100 + 49 * failures} passes when reachable within the cap of
	 * {@value #STANDARD_CAP}, with another table's comparisons and minimum pass rate. Unlike the standard boundaries,
	 * 4 failures still leave certification within reach, at 296 passes, and a fifth failure puts it out of reach.
	 *
	 * @param comparisons the boundaries whose comparisons and minimum pass rate to use
	 * @param belowFailures the minimum failures for a clearly-below decision when passes are below the certifying
	 *   minimum
	 * @return the boundaries
	 */
	public static DecisionBoundaries reachableAtFourFailures(DecisionBoundaries comparisons, int belowFailures)
	{
		return decision(STANDARD_CAP, 100, 49, belowFailures, comparisons.comparisons(),
			comparisons.minimumPassRate());
	}

	/**
	 * Returns decision boundaries.
	 *
	 * @param cap the maximum number of outcomes per cell
	 * @param base the passes that certify with no failures
	 * @param step the additional passes that each failure requires
	 * @param belowFailures the minimum failures for a clearly-below decision when passes are below the certifying minimum
	 * @param comparisons the comparison boundaries, one per cell
	 * @return the boundaries
	 */
	public static DecisionBoundaries decision(int cap, int base, int step, int belowFailures,
		List<ComparisonBoundaries> comparisons)
	{
		return decision(cap, base, step, belowFailures, comparisons, Contract.DEFAULT_MINIMUM_PASS_RATE);
	}

	/**
	 * Returns decision boundaries at a minimum pass rate.
	 *
	 * @param cap the maximum number of outcomes per cell
	 * @param base the passes that certify with no failures
	 * @param step the additional passes that each failure requires
	 * @param belowFailures the minimum failures for a clearly-below decision when passes are below the certifying minimum
	 * @param comparisons the comparison boundaries, one per cell, at the same minimum pass rate
	 * @param minimumPassRate the minimum pass rate {@code p_min}
	 * @return the boundaries
	 */
	public static DecisionBoundaries decision(int cap, int base, int step, int belowFailures,
		List<ComparisonBoundaries> comparisons, BigDecimal minimumPassRate)
	{
		List<Integer> minimums = minimums(cap, base, step);
		return new DecisionBoundaries(ErrorBudget.certificationLevel(1), minimumPassRate, cap, minimums,
			maximums(cap, minimums, belowFailures), comparisons);
	}

	/**
	 * Returns comparison boundaries.
	 *
	 * @param cap the maximum number of outcomes per cell
	 * @param base the passes that prove better with no failures
	 * @param step the additional passes that each failure requires
	 * @param worseFailures the minimum failures for a proven-worse decision when passes are below the proven-better
	 *   minimum
	 * @return the boundaries
	 */
	public static ComparisonBoundaries comparison(int cap, int base, int step, int worseFailures)
	{
		return comparison(cap, base, step, worseFailures, Contract.DEFAULT_MINIMUM_PASS_RATE);
	}

	/**
	 * Returns comparison boundaries at a minimum pass rate.
	 *
	 * @param cap the maximum number of outcomes per cell
	 * @param base the passes that prove better with no failures
	 * @param step the additional passes that each failure requires
	 * @param worseFailures the minimum failures for a proven-worse decision when passes are below the proven-better
	 *   minimum
	 * @param minimumPassRate the minimum pass rate {@code p_min}
	 * @return the boundaries
	 */
	public static ComparisonBoundaries comparison(int cap, int base, int step, int worseFailures,
		BigDecimal minimumPassRate)
	{
		List<Integer> minimums = minimums(cap, base, step);
		Comparison comparison = new Comparison(new BigDecimal("0.9000"), new BigDecimal("0.8000"),
			ErrorBudget.improvementLevel(1, 2), minimumPassRate);
		return new ComparisonBoundaries(comparison, minimums, maximums(cap, minimums, worseFailures));
	}

	/**
	 * Returns the minimum passes for each failure count.
	 *
	 * @param cap the maximum number of outcomes per cell
	 * @param base the passes required with no failures
	 * @param step the additional passes that each failure requires
	 * @return the minimums
	 */
	private static List<Integer> minimums(int cap, int base, int step)
	{
		List<Integer> minimums = new ArrayList<>(cap + 1);
		for (int failures = 0; failures <= cap; ++failures)
			minimums.add(Math.min(base + step * failures, cap - failures + 1));
		return minimums;
	}

	/**
	 * Returns the maximum passes that count as clearly below or proven worse for each failure count.
	 *
	 * @param cap the maximum number of outcomes per cell
	 * @param minimums the certifying or proven-better minimum passes at each failure count
	 * @param threshold the minimum failures for a clearly-below or proven-worse decision
	 * @return one less than each minimum at or above the failure threshold, or {@code -1} below that threshold
	 */
	private static List<Integer> maximums(int cap, List<Integer> minimums, int threshold)
	{
		List<Integer> maximums = new ArrayList<>(cap + 1);
		for (int failures = 0; failures <= cap; ++failures)
		{
			if (failures >= threshold)
				maximums.add(minimums.get(failures) - 1);
			else
				maximums.add(-1);
		}
		return maximums;
	}
}
