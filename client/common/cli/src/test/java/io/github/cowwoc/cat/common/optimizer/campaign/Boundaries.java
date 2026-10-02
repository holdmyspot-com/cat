package io.github.cowwoc.cat.common.optimizer.campaign;

import io.github.cowwoc.cat.common.optimizer.statistics.Comparison;
import io.github.cowwoc.cat.common.optimizer.statistics.ComparisonBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;

import java.math.BigDecimal;
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
		List<ComparisonBoundaries> comparisons = new ArrayList<>();
		for (int i = 0; i < cells; ++i)
			comparisons.add(comparison(STANDARD_CAP, 60, 40, 4));
		return decision(STANDARD_CAP, 100, 50, 5, comparisons);
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
		List<Integer> minimums = minimums(cap, base, step);
		return new DecisionBoundaries(ErrorBudget.certificationLevel(1), cap, minimums,
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
		List<Integer> minimums = minimums(cap, base, step);
		Comparison comparison = new Comparison(new BigDecimal("0.9000"), new BigDecimal("0.8000"),
			ErrorBudget.improvementLevel(1, 2));
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
