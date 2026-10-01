package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.ABOVE;
import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.BELOW;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.util.List;

/**
 * One category's precomputed proven-better and proven-worse boundaries in a certification attempt, so that each
 * decision is a lookup.
 * <p>
 * Element {@code F} of each list describes the states with {@code F} failures, up to the attempt's cap {@code n}.
 *
 * @param comparison                the category's frozen comparison
 * @param minimumProvenBetterPasses for each {@code F}, the fewest passes that prove the candidate better, or
 *                                  {@code n - F + 1} if no state within the cap does
 * @param maximumProvenWorsePasses  for each {@code F}, the most passes that prove the candidate worse, or -1 if no
 *                                  state does
 */
public record ComparisonBoundaries(Comparison comparison, List<Integer> minimumProvenBetterPasses,
	List<Integer> maximumProvenWorsePasses)
{
	/**
	 * Creates the boundaries.
	 *
	 * @param comparison                the category's frozen comparison
	 * @param minimumProvenBetterPasses for each {@code F}, the fewest passes that prove the candidate better, or
	 *                                  {@code n - F + 1} if no state within the cap does
	 * @param maximumProvenWorsePasses  for each {@code F}, the most passes that prove the candidate worse, or -1 if
	 *                                  no state does
	 * @throws NullPointerException     if any of the arguments are null, or if a list contains null
	 * @throws IllegalArgumentException if the lists are empty or have different sizes, if an element is out of range,
	 *                                  or if a state would be both proven better and proven worse
	 */
	public ComparisonBoundaries
	{
		requireThat(comparison, "comparison").isNotNull();
		requireThat(minimumProvenBetterPasses, "minimumProvenBetterPasses").isNotEmpty();
		int cap = minimumProvenBetterPasses.size() - 1;
		BoundaryLists.requireValid(minimumProvenBetterPasses, "minimumProvenBetterPasses", maximumProvenWorsePasses,
			"maximumProvenWorsePasses", cap);
		minimumProvenBetterPasses = List.copyOf(minimumProvenBetterPasses);
		maximumProvenWorsePasses = List.copyOf(maximumProvenWorsePasses);
	}

	/**
	 * Computes the boundaries of one category.
	 *
	 * @param comparison the category's frozen comparison
	 * @param cap        the attempt's cap
	 * @return the boundaries
	 */
	static ComparisonBoundaries compute(Comparison comparison, int cap)
	{
		List<Integer> minimums;
		if (comparison.incumbentUpper().compareTo(BigDecimal.ONE) == 0)
			minimums = BoundaryLists.unreachableMinimums(cap);
		else
		{
			minimums = BoundaryLists.computeMinimums(new BettingProcess(ABOVE, comparison.incumbentUpper()),
				comparison.level(), cap);
		}
		List<Integer> maximums;
		if (comparison.incumbentLower().signum() == 0)
			maximums = BoundaryLists.unreachableMaximums(cap);
		else
		{
			maximums = BoundaryLists.computeMaximums(new BettingProcess(BELOW, comparison.incumbentLower()),
				comparison.level(), cap);
		}
		return new ComparisonBoundaries(comparison, minimums, maximums);
	}

	/**
	 * Returns the largest number of outcomes that the boundaries cover.
	 *
	 * @return the attempt's cap
	 */
	public int cap()
	{
		return minimumProvenBetterPasses.size() - 1;
	}

	/**
	 * Indicates whether a state proves the candidate better than the incumbent.
	 *
	 * @param passes   the candidate's adopted passes in the attempt
	 * @param failures the candidate's adopted failures in the attempt
	 * @return {@code true} if the state is proven better
	 * @throws IllegalArgumentException if {@code passes} or {@code failures} are negative, or if their sum exceeds
	 *                                  the cap
	 */
	public boolean isProvenBetter(int passes, int failures)
	{
		BoundaryLists.requireState(passes, failures, cap());
		return passes >= minimumProvenBetterPasses.get(failures);
	}

	/**
	 * Indicates whether a state proves the candidate worse than the incumbent.
	 *
	 * @param passes   the candidate's adopted passes in the attempt
	 * @param failures the candidate's adopted failures in the attempt
	 * @return {@code true} if the state is proven worse
	 * @throws IllegalArgumentException if {@code passes} or {@code failures} are negative, or if their sum exceeds
	 *                                  the cap
	 */
	public boolean isProvenWorse(int passes, int failures)
	{
		BoundaryLists.requireState(passes, failures, cap());
		return passes <= maximumProvenWorsePasses.get(failures);
	}
}
