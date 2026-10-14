package io.github.cowwoc.cat.common.optimizer.statistics.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.PairedComparison;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.PairTally;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;
import org.testng.annotations.Test;

import java.math.BigInteger;

/**
 * Tests {@link PairedComparison}.
 * <p>
 * The boundaries come from an independent script that evaluates both e-values in exact rationals, with
 * {@code m = 19/40} and {@code f = 1/4, 1/2, 3/4, 9/10}.
 * <table>
 *   <caption>Reference values checked by this class</caption>
 *   <tr><th>Path</th><th>Fewest pairs that decide in attempts 1, 2, 3</th></tr>
 *   <tr><td>{@code E_ctl_up}, every pair even ({@code Z = 1/2})</td><td>105, 131, 147</td></tr>
 *   <tr><td>{@code E_ctl_up}, every pair candidate ahead ({@code Z = 1})</td><td>7, 9, 10</td></tr>
 *   <tr><td>{@code E_ctl_down}, every pair control ahead ({@code Z = 0})</td><td>8, 10, 12</td></tr>
 *   <tr><td>{@code E_ctl_up}, even pairs after 1 or 2 control-ahead pairs</td><td>149 or 190 (attempt 1)</td></tr>
 *   <tr><td>{@code E_ctl_up}, candidate-ahead pairs after 2 control-ahead pairs</td><td>13 (attempt 1)</td></tr>
 *   <tr><td>{@code E_ctl_down}, control-ahead pairs after 100 even pairs</td><td>16 (attempt 1)</td></tr>
 *   <tr><td>{@code E_ctl_down}, control-ahead pairs after 10 candidate-ahead pairs</td><td>32 (attempt 1)</td></tr>
 * </table>
 */
public final class PairedComparisonTest
{
	/**
	 * Creates a test instance.
	 */
	public PairedComparisonTest()
	{
	}

	/**
	 * Verifies that no pairs decide nothing: both e-values start at 1, which compares equal to a threshold of 1, and
	 * equality never decides.
	 */
	@Test
	public void noPairsDecideNothing()
	{
		// Requirement: R-S11
		PairTally none = new PairTally(0, 0, 0);
		SignificanceLevel level = ErrorBudget.certificationLevel(1);
		requireThat(PairedComparison.passes(none, level), "passes").isFalse();
		requireThat(PairedComparison.isBelow(none, level), "isBelow").isFalse();
		requireThat(PairedComparison.compareAbove(none, BigInteger.ONE), "compareAbove").isEqualTo(0);
		requireThat(PairedComparison.compareBelow(none, BigInteger.ONE), "compareBelow").isEqualTo(0);
	}

	/**
	 * Verifies, in attempts 1 to 3, the fewest even pairs that pass the comparison, the result when the candidate and
	 * the control both pass a guard on every pair, and that one fewer does not.
	 */
	@Test
	public void evenPairsPassAtTheBoundary()
	{
		// Requirement: R-S11
		int[] fewest = {105, 131, 147};
		for (int index = 1; index <= fewest.length; ++index)
		{
			SignificanceLevel level = ErrorBudget.certificationLevel(index);
			int pairs = fewest[index - 1];
			requireThat(PairedComparison.passes(new PairTally(0, pairs, 0), level), "i" + index + "Passes").
				isTrue();
			requireThat(PairedComparison.passes(new PairTally(0, pairs - 1, 0), level), "i" + index + "OneFewer").
				isFalse();
			requireThat(PairedComparison.isBelow(new PairTally(0, pairs, 0), level),
				"i" + index + "IsBelowControl").isFalse();
		}
	}

	/**
	 * Verifies, in attempts 1 to 3, the fewest candidate-ahead pairs that pass the comparison and that one fewer does
	 * not.
	 */
	@Test
	public void candidateAheadPairsPassAtTheBoundary()
	{
		// Requirement: R-S11
		int[] fewest = {7, 9, 10};
		for (int index = 1; index <= fewest.length; ++index)
		{
			SignificanceLevel level = ErrorBudget.certificationLevel(index);
			int pairs = fewest[index - 1];
			requireThat(PairedComparison.passes(new PairTally(0, 0, pairs), level), "i" + index + "Passes").
				isTrue();
			requireThat(PairedComparison.passes(new PairTally(0, 0, pairs - 1), level), "i" + index + "OneFewer").
				isFalse();
		}
	}

	/**
	 * Verifies, in attempts 1 to 3, the fewest control-ahead pairs that prove the candidate below control and that
	 * one fewer does not.
	 */
	@Test
	public void controlAheadPairsAtBoundary()
	{
		// Requirement: R-S11
		int[] fewest = {8, 10, 12};
		for (int index = 1; index <= fewest.length; ++index)
		{
			SignificanceLevel level = ErrorBudget.certificationLevel(index);
			int pairs = fewest[index - 1];
			requireThat(PairedComparison.isBelow(new PairTally(pairs, 0, 0), level),
				"i" + index + "IsBelowControl").isTrue();
			requireThat(PairedComparison.isBelow(new PairTally(pairs - 1, 0, 0), level),
				"i" + index + "OneFewer").isFalse();
			requireThat(PairedComparison.passes(new PairTally(pairs, 0, 0), level), "i" + index + "Passes").
				isFalse();
		}
	}

	/**
	 * Verifies mixed paths in attempt 1: a pair that only the control passes delays passing, and pairs that the
	 * candidate passes delay a below-control decision.
	 */
	@Test
	public void mixedPathsDecideAtTheirBoundaries()
	{
		// Requirement: R-S11
		SignificanceLevel level = ErrorBudget.certificationLevel(1);
		requireThat(PairedComparison.passes(new PairTally(1, 149, 0), level), "oneControlAheadPasses").isTrue();
		requireThat(PairedComparison.passes(new PairTally(1, 148, 0), level), "oneControlAheadOneFewer").isFalse();
		requireThat(PairedComparison.passes(new PairTally(2, 190, 0), level), "twoControlAheadPasses").isTrue();
		requireThat(PairedComparison.passes(new PairTally(2, 189, 0), level), "twoControlAheadOneFewer").isFalse();
		requireThat(PairedComparison.passes(new PairTally(2, 0, 13), level), "afterControlAheadPasses").isTrue();
		requireThat(PairedComparison.passes(new PairTally(2, 0, 12), level), "afterControlAheadOneFewer").isFalse();
		requireThat(PairedComparison.isBelow(new PairTally(16, 100, 0), level), "afterEvenBelow").isTrue();
		requireThat(PairedComparison.isBelow(new PairTally(15, 100, 0), level), "afterEvenOneFewer").
			isFalse();
		requireThat(PairedComparison.isBelow(new PairTally(32, 0, 10), level), "afterAheadBelow").isTrue();
		requireThat(PairedComparison.isBelow(new PairTally(31, 0, 10), level), "afterAheadOneFewer").
			isFalse();
	}

	/**
	 * Verifies that the fewest further pairs count from the current tally and report a missing decision as one more
	 * than the limit.
	 */
	@Test
	public void fewestFurtherPairsCountFromTheTally()
	{
		// Requirement: R-S11
		SignificanceLevel level = ErrorBudget.certificationLevel(1);
		PairTally fifty = new PairTally(0, 50, 0);
		requireThat(PairedComparison.fewestEvenPairsToPass(fifty, level, 1000), "evenPairs").isEqualTo(55);
		requireThat(PairedComparison.fewestCandidateAheadPairsToPass(fifty, level, 1000), "aheadPairs").
			isEqualTo(4);
		requireThat(PairedComparison.fewestEvenPairsToPass(fifty, level, 54), "beyondLimit").isEqualTo(55);
		requireThat(PairedComparison.fewestEvenPairsToPass(new PairTally(0, 105, 0), level, 1000), "passed").
			isEqualTo(0);
	}
}
