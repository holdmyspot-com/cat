package io.github.cowwoc.cat.common.optimizer.report;

import io.github.cowwoc.cat.common.optimizer.statistics.Comparison;
import io.github.cowwoc.cat.common.optimizer.statistics.ComparisonBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds small handwritten decision boundaries whose values can be checked by hand, so that report tests need no
 * betting computation.
 * <p>
 * A cell certifies at {@code 100 + 50·F} passes and is clearly below once it has 6 failures. A cell is proven better
 * than the incumbent at {@code 60 + 40·F} passes and proven worse once it has 8 failures. The cap is
 * {@value #CAP}, and every comparison freezes {@code UI = 0.9000} and {@code LI = 0.8000}.
 */
final class ReportBoundaries
{
	/**
	 * The cap.
	 */
	static final int CAP = 300;

	/**
	 * Prevents instantiation.
	 */
	private ReportBoundaries()
	{
	}

	/**
	 * Returns the boundaries of attempt 1 for the two cells of the fixture contract.
	 *
	 * @return the boundaries
	 */
	static DecisionBoundaries boundaries()
	{
		return boundaries(2);
	}

	/**
	 * Returns the boundaries of attempt 1 for a number of cells, each with the same comparison.
	 *
	 * @param cells the number of cells
	 * @return the boundaries
	 */
	static DecisionBoundaries boundaries(int cells)
	{
		List<Integer> certify = minimums(100, 50);
		List<Integer> below = new ArrayList<>();
		for (int failures = 0; failures <= CAP; ++failures)
		{
			if (failures >= 6)
				below.add(certify.get(failures) - 1);
			else
				below.add(-1);
		}
		Comparison frozen = new Comparison(new BigDecimal("0.9000"), new BigDecimal("0.8000"),
			ErrorBudget.improvementLevel(1, cells));
		List<Integer> better = minimums(60, 40);
		List<Integer> worse = new ArrayList<>();
		for (int failures = 0; failures <= CAP; ++failures)
		{
			if (failures >= 8)
				worse.add(better.get(failures) - 1);
			else
				worse.add(-1);
		}
		ComparisonBoundaries comparison = new ComparisonBoundaries(frozen, better, worse);
		return new DecisionBoundaries(ErrorBudget.certificationLevel(1), CAP, certify, below,
			Collections.nCopies(cells, comparison));
	}

	/**
	 * Returns the minimum passes for each failure count.
	 *
	 * @param base the passes required with no failures
	 * @param step the additional passes that each failure requires
	 * @return the minimums, with {@code cap - failures + 1} where no state within the cap qualifies
	 */
	private static List<Integer> minimums(int base, int step)
	{
		List<Integer> minimums = new ArrayList<>(CAP + 1);
		for (int failures = 0; failures <= CAP; ++failures)
			minimums.add(Math.min(base + step * failures, CAP - failures + 1));
		return minimums;
	}
}
