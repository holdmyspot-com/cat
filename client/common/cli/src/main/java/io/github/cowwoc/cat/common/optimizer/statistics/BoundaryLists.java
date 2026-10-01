package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computes and validates the per-failure-count pass boundaries that {@link DecisionBoundaries} and
 * {@link ComparisonBoundaries} store.
 * <p>
 * For a cap {@code n}, a list holds one element for each failure count {@code F} from 0 to {@code n}. A list of
 * minimums holds, for each {@code F}, the fewest passes {@code S} at which an increasing e-value exceeds its
 * threshold, or {@code n - F + 1} if no {@code S <= n - F} does. A list of maximums holds the most passes at which a
 * decreasing e-value exceeds its threshold, or -1 if none does.
 */
final class BoundaryLists
{
	/**
	 * Returns the fewest exceeding passes for each failure count.
	 *
	 * @param process an {@link BettingProcess.Side#ABOVE} process
	 * @param level   the level
	 * @param cap     the largest number of outcomes
	 * @return the minimums
	 */
	static List<Integer> computeMinimums(BettingProcess process, SignificanceLevel level, int cap)
	{
		List<Integer> minimums = new ArrayList<>(cap + 1);
		for (int failures = 0; failures <= cap; ++failures)
			minimums.add(process.findMinimumExceedingPasses(failures, level, cap - failures));
		return List.copyOf(minimums);
	}

	/**
	 * Returns the most exceeding passes for each failure count.
	 *
	 * @param process a {@link BettingProcess.Side#BELOW} process
	 * @param level   the level
	 * @param cap     the largest number of outcomes
	 * @return the maximums
	 */
	static List<Integer> computeMaximums(BettingProcess process, SignificanceLevel level, int cap)
	{
		List<Integer> maximums = new ArrayList<>(cap + 1);
		for (int failures = 0; failures <= cap; ++failures)
			maximums.add(process.findMaximumExceedingPasses(failures, level, cap - failures));
		return List.copyOf(maximums);
	}

	/**
	 * Returns a list of no-decision values: the minimums of an e-value that never exceeds its threshold.
	 *
	 * @param cap the largest number of outcomes
	 * @return {@code n - F + 1} for each failure count {@code F}
	 */
	static List<Integer> unreachableMinimums(int cap)
	{
		List<Integer> minimums = new ArrayList<>(cap + 1);
		for (int failures = 0; failures <= cap; ++failures)
			minimums.add(cap - failures + 1);
		return List.copyOf(minimums);
	}

	/**
	 * Returns a list of no-decision values: the maximums of an e-value that never exceeds its threshold.
	 *
	 * @param cap the largest number of outcomes
	 * @return -1 for each failure count
	 */
	static List<Integer> unreachableMaximums(int cap)
	{
		return Collections.nCopies(cap + 1, -1);
	}

	/**
	 * Validates a pair of boundary lists.
	 *
	 * @param minimums     the minimums of the increasing e-value
	 * @param minimumsName the name of {@code minimums}
	 * @param maximums     the maximums of the decreasing e-value
	 * @param maximumsName the name of {@code maximums}
	 * @param cap          the largest number of outcomes
	 * @throws NullPointerException     if any of the lists are null or contain null
	 * @throws IllegalArgumentException if the lists do not have {@code cap + 1} elements, if an element is out of
	 *                                  range, or if a state would both exceed the minimum and fall below the maximum
	 */
	static void requireValid(List<Integer> minimums, String minimumsName, List<Integer> maximums,
		String maximumsName, int cap)
	{
		requireThat(minimums, minimumsName).size().isEqualTo(cap + 1);
		requireThat(maximums, maximumsName).size().isEqualTo(cap + 1);
		for (int failures = 0; failures <= cap; ++failures)
		{
			int minimum = minimums.get(failures);
			int maximum = maximums.get(failures);
			String minimumName = minimumsName + "[" + failures + "]";
			String maximumName = maximumsName + "[" + failures + "]";
			requireThat(minimum, minimumName).isBetween(0, true, cap - failures + 1, true);
			requireThat(maximum, maximumName).isBetween(-1, true, cap - failures, true).
				isLessThan(minimum, minimumName);
		}
	}

	/**
	 * Validates that a category state lies within the cap.
	 *
	 * @param passes   the number of passes
	 * @param failures the number of failures
	 * @param cap      the largest number of outcomes
	 * @throws IllegalArgumentException if {@code passes} or {@code failures} are negative, or if their sum exceeds
	 *                                  {@code cap}
	 */
	static void requireState(int passes, int failures, int cap)
	{
		requireThat(passes, "passes").isBetween(0, true, cap, true);
		requireThat(failures, "failures").isBetween(0, true, cap - passes, true);
	}

	/**
	 * Prevents construction.
	 */
	private BoundaryLists()
	{
	}
}
