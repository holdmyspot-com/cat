package io.github.cowwoc.cat.common.optimizer.campaign;

import io.github.cowwoc.cat.common.optimizer.statistics.Comparison;
import io.github.cowwoc.cat.common.optimizer.statistics.ComparisonBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds small handwritten decision boundaries, so that tests of the campaign policies need no betting
 * computation.
 * <p>
 * A table certifies (or proves better) when {@code passes >= base + step·F}, capped at the unreachable value
 * {@code cap − F + 1}, and is clearly below (or proven worse) at any pass count once {@code F} reaches a threshold.
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
	 * Returns the boundaries that {@link CampaignBuilder} uses by default.
	 * <p>
	 * With a cap of {@value #STANDARD_CAP}: a category certifies at {@code 100 + 50·F} passes and is clearly below
	 * once it has 5 failures. It is proven better at {@code 60 + 40·F} passes and proven worse once it has 4
	 * failures.
	 *
	 * @param categories the number of categories
	 * @return the boundaries
	 */
	public static DecisionBoundaries standard(int categories)
	{
		List<ComparisonBoundaries> comparisons = new ArrayList<>();
		for (int i = 0; i < categories; ++i)
			comparisons.add(comparison(STANDARD_CAP, 60, 40, 4));
		return decision(STANDARD_CAP, 100, 50, 5, comparisons);
	}

	/**
	 * Returns decision boundaries.
	 *
	 * @param cap the cap
	 * @param base the passes that certify with no failures
	 * @param step the additional passes that each failure requires
	 * @param belowFailures the failures at which a category is clearly below
	 * @param comparisons the comparison boundaries, one per category
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
	 * @param cap the cap
	 * @param base the passes that prove better with no failures
	 * @param step the additional passes that each failure requires
	 * @param worseFailures the failures at which a category is proven worse
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
	 * @param cap the cap
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
	 * Returns the maximum passes for each failure count.
	 *
	 * @param cap the cap
	 * @param minimums the minimums
	 * @param threshold the failures from which every pass count is at or below the maximum
	 * @return the maximums
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
