package com.holdmyspot.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A certification attempt's precomputed decision table, computed once at commit and persisted with the attempt, so
 * that every later decision is a lookup.
 * <p>
 * Element {@code F} of each list describes the states with {@code F} failures, for {@code F} from 0 to the cap
 * {@code n}. A state {@code (S, F)} certifies when {@code E_up(S, F) > 1 / alpha_i} at the null rate
 * {@code minimumPassRate}, and is clearly below when {@code E_down(S, F) > 1 / alpha_i}. Each cell, one
 * test under one trial profile, has comparison boundaries that use its own frozen incumbent bounds at
 * {@code K / beta_i} (see {@link Comparison}).
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param level                     the attempt's certification level {@code alpha_i}
 * @param minimumPassRate           the pass probability that a certified cell exceeds
 * @param cap                       the attempt's per-cell cap
 * @param minimumCertifyingPasses   for each {@code F}, the fewest passes that certify, or {@code n - F + 1} if no
 *                                  state within the cap does
 * @param maximumClearlyBelowPasses for each {@code F}, the most passes that are clearly below, or -1 if no state is
 * @param comparisons               the comparison boundaries of each cell, in the contract's cell order
 */
public record DecisionBoundaries(SignificanceLevel level, BigDecimal minimumPassRate, int cap,
	List<Integer> minimumCertifyingPasses,
	List<Integer> maximumClearlyBelowPasses, List<ComparisonBoundaries> comparisons)
{
	/**
	 * Creates a table.
	 *
	 * @param level                     the attempt's certification level {@code alpha_i}
	 * @param minimumPassRate           the pass probability that a certified cell exceeds
	 * @param cap                       the attempt's per-cell cap
	 * @param minimumCertifyingPasses   for each {@code F}, the fewest passes that certify, or {@code n - F + 1} if no
	 *                                  state within the cap does
	 * @param maximumClearlyBelowPasses for each {@code F}, the most passes that are clearly below, or -1 if no state
	 *                                  is
	 * @param comparisons               the comparison boundaries of each cell, in the contract's cell order
	 * @throws NullPointerException     if any of the arguments are null, or if a list contains null
	 * @throws IllegalArgumentException if {@code minimumPassRate} is not between 0 and 1, exclusive, if
	 *                                  {@code cap} is not positive, if a list does not have {@code cap + 1}
	 *                                  elements, if an element is out of range, if a state would both certify and be
	 *                                  clearly below, or if a cell's boundaries cover a different cap or a
	 *                                  different minimum pass rate
	 */
	public DecisionBoundaries
	{
		requireThat(level, "level").isNotNull();
		requireThat(minimumPassRate, "minimumPassRate").isGreaterThan(BigDecimal.ZERO).
			isLessThan(BigDecimal.ONE);
		requireThat(cap, "cap").isPositive();
		BoundaryLists.requireValid(minimumCertifyingPasses, "minimumCertifyingPasses", maximumClearlyBelowPasses,
			"maximumClearlyBelowPasses", cap);
		requireThat(comparisons, "comparisons").isNotNull();
		for (int cell = 0; cell < comparisons.size(); ++cell)
		{
			ComparisonBoundaries comparison = comparisons.get(cell);
			requireThat(comparison.cap(), "comparisons[" + cell + "].cap()").isEqualTo(cap, "cap");
			requireThat(comparison.comparison().minimumPassRate(),
				"comparisons[" + cell + "].comparison().minimumPassRate()").
				isEqualTo(minimumPassRate, "minimumPassRate");
		}
		minimumCertifyingPasses = List.copyOf(minimumCertifyingPasses);
		maximumClearlyBelowPasses = List.copyOf(maximumClearlyBelowPasses);
		comparisons = List.copyOf(comparisons);
	}

	/**
	 * Computes a certification attempt's decision table.
	 *
	 * @param certificationLevel the attempt's certification level {@code alpha_i}
	 * @param minimumPassRate    the campaign's minimum pass rate {@code p_min}, the pass probability that a certified
	 *                           cell exceeds
	 * @param cap                the attempt's per-cell cap
	 * @param comparisons        each cell's frozen comparison, in the contract's cell order
	 * @return the table
	 * @throws NullPointerException     if any of the arguments are null, or if {@code comparisons} contains null
	 * @throws IllegalArgumentException if {@code minimumPassRate} is not strictly between 0 and 1, if {@code cap} is
	 *                                  not positive, or if a comparison uses a different minimum pass rate
	 */
	public static DecisionBoundaries compute(SignificanceLevel certificationLevel, BigDecimal minimumPassRate,
		int cap, List<Comparison> comparisons)
	{
		requireThat(certificationLevel, "certificationLevel").isNotNull();
		requireThat(minimumPassRate, "minimumPassRate").isGreaterThan(BigDecimal.ZERO).isLessThan(BigDecimal.ONE);
		requireThat(cap, "cap").isPositive();
		requireThat(comparisons, "comparisons").isNotNull();

		List<Integer> minimums = BoundaryLists.computeMinimums(BettingProcess.above(minimumPassRate),
			certificationLevel, cap);
		List<Integer> maximums = BoundaryLists.computeMaximums(BettingProcess.below(minimumPassRate,
			minimumPassRate), certificationLevel, cap);
		List<ComparisonBoundaries> comparisonBoundaries = new ArrayList<>(comparisons.size());
		for (Comparison comparison : comparisons)
			comparisonBoundaries.add(ComparisonBoundaries.compute(comparison, cap));
		return new DecisionBoundaries(certificationLevel, minimumPassRate, cap, minimums, maximums,
			comparisonBoundaries);
	}

	/**
	 * Returns the fewest passes that certify when no outcome failed, without a cap.
	 *
	 * @param certificationLevel the attempt's certification level {@code alpha_i}
	 * @param minimumPassRate    the campaign's minimum pass rate {@code p_min}
	 * @return the all-pass minimum
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code minimumPassRate} is not strictly between 0 and 1
	 */
	public static int allPassMinimum(SignificanceLevel certificationLevel, BigDecimal minimumPassRate)
	{
		requireThat(certificationLevel, "certificationLevel").isNotNull();
		BettingProcess certification = BettingProcess.above(minimumPassRate);
		int maximumPasses = 1;
		while (!certification.exceeds(maximumPasses, 0, certificationLevel))
			maximumPasses *= 2;
		return certification.findMinimumExceedingPasses(0, certificationLevel, maximumPasses);
	}

	/**
	 * Indicates whether a cell state certifies.
	 *
	 * @param passes   the cell's adopted passes in the attempt
	 * @param failures the cell's adopted failures in the attempt
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
	 * Indicates whether a cell state is clearly below the minimum pass rate.
	 *
	 * @param passes   the cell's adopted passes in the attempt
	 * @param failures the cell's adopted failures in the attempt
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
