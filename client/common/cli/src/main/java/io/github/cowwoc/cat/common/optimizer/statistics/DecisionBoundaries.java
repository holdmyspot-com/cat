package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.REQUIRED_PASS_RATE;
import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.ABOVE;
import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.BELOW;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;

/**
 * A certification attempt's precomputed decision table, computed once at commit and persisted with the attempt, so
 * that every later decision is a lookup.
 * <p>
 * Element {@code F} of each list describes the states with {@code F} failures, for {@code F} from 0 to the cap
 * {@code n}. A state {@code (S, F)} certifies when {@code E_up(S, F) > 1 / alpha_i} at null rate 0.95, and is
 * clearly below when {@code E_down(S, F) > 1 / alpha_i}. Each category's comparison boundaries use its own frozen
 * incumbent bounds at {@code K / beta_i}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param level                     the attempt's certification level {@code alpha_i}
 * @param cap                       the attempt's per-category cap
 * @param minimumCertifyingPasses   for each {@code F}, the fewest passes that certify, or {@code n - F + 1} if no
 *                                  state within the cap does
 * @param maximumClearlyBelowPasses for each {@code F}, the most passes that are clearly below, or -1 if no state is
 * @param comparisons               the comparison boundaries of each category, in the contract's category order
 */
public record DecisionBoundaries(SignificanceLevel level, int cap, List<Integer> minimumCertifyingPasses,
	List<Integer> maximumClearlyBelowPasses, List<ComparisonBoundaries> comparisons)
{
	/**
	 * Creates a table.
	 *
	 * @param level                     the attempt's certification level {@code alpha_i}
	 * @param cap                       the attempt's per-category cap
	 * @param minimumCertifyingPasses   for each {@code F}, the fewest passes that certify, or {@code n - F + 1} if no
	 *                                  state within the cap does
	 * @param maximumClearlyBelowPasses for each {@code F}, the most passes that are clearly below, or -1 if no state
	 *                                  is
	 * @param comparisons               the comparison boundaries of each category, in the contract's category order
	 * @throws NullPointerException     if any of the arguments are null, or if a list contains null
	 * @throws IllegalArgumentException if {@code cap} is not positive, if a list does not have {@code cap + 1}
	 *                                  elements, if an element is out of range, if a state would both certify and be
	 *                                  clearly below, or if a category's boundaries cover a different cap
	 */
	public DecisionBoundaries
	{
		requireThat(level, "level").isNotNull();
		requireThat(cap, "cap").isPositive();
		BoundaryLists.requireValid(minimumCertifyingPasses, "minimumCertifyingPasses", maximumClearlyBelowPasses,
			"maximumClearlyBelowPasses", cap);
		requireThat(comparisons, "comparisons").isNotNull();
		for (int category = 0; category < comparisons.size(); ++category)
			requireThat(comparisons.get(category).cap(), "comparisons[" + category + "].cap()").isEqualTo(cap, "cap");
		minimumCertifyingPasses = List.copyOf(minimumCertifyingPasses);
		maximumClearlyBelowPasses = List.copyOf(maximumClearlyBelowPasses);
		comparisons = List.copyOf(comparisons);
	}

	/**
	 * Computes a certification attempt's decision table.
	 *
	 * @param certificationLevel the attempt's certification level {@code alpha_i}
	 * @param cap                the attempt's per-category cap
	 * @param comparisons        each category's frozen comparison, in the contract's category order
	 * @return the table
	 * @throws NullPointerException     if any of the arguments are null, or if {@code comparisons} contains null
	 * @throws IllegalArgumentException if {@code cap} is not positive
	 */
	public static DecisionBoundaries compute(SignificanceLevel certificationLevel, int cap,
		List<Comparison> comparisons)
	{
		requireThat(certificationLevel, "certificationLevel").isNotNull();
		requireThat(cap, "cap").isPositive();
		requireThat(comparisons, "comparisons").isNotNull();

		List<Integer> minimums = BoundaryLists.computeMinimums(new BettingProcess(ABOVE, REQUIRED_PASS_RATE),
			certificationLevel, cap);
		List<Integer> maximums = BoundaryLists.computeMaximums(new BettingProcess(BELOW, REQUIRED_PASS_RATE),
			certificationLevel, cap);
		List<ComparisonBoundaries> categories = new ArrayList<>(comparisons.size());
		for (Comparison comparison : comparisons)
			categories.add(ComparisonBoundaries.compute(comparison, cap));
		return new DecisionBoundaries(certificationLevel, cap, minimums, maximums, categories);
	}

	/**
	 * Indicates whether a category state certifies.
	 *
	 * @param passes   the category's adopted passes in the attempt
	 * @param failures the category's adopted failures in the attempt
	 * @return {@code true} if the state certifies
	 * @throws IllegalArgumentException if {@code passes} or {@code failures} are negative, or if their sum exceeds
	 *                                  {@code cap}
	 */
	public boolean certifies(int passes, int failures)
	{
		BoundaryLists.requireState(passes, failures, cap);
		return passes >= minimumCertifyingPasses.get(failures);
	}

	/**
	 * Indicates whether a category state is clearly below the required pass rate.
	 *
	 * @param passes   the category's adopted passes in the attempt
	 * @param failures the category's adopted failures in the attempt
	 * @return {@code true} if the state is clearly below
	 * @throws IllegalArgumentException if {@code passes} or {@code failures} are negative, or if their sum exceeds
	 *                                  {@code cap}
	 */
	public boolean isClearlyBelow(int passes, int failures)
	{
		BoundaryLists.requireState(passes, failures, cap);
		return passes <= maximumClearlyBelowPasses.get(failures);
	}
}
