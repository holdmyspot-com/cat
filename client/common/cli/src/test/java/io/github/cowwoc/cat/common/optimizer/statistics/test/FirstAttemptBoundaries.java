package io.github.cowwoc.cat.common.optimizer.statistics.test;

import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;

import java.math.BigDecimal;
import java.util.List;

/**
 * Supplies the {@link DecisionBoundaries} of certification attempt 1, without comparisons, to tests in this module
 * and in modules that use its test JAR.
 * <p>
 * The table uses {@code alpha_1} and the first-attempt cap of 262, which {@code CapCalibrationTest}
 * verifies; tests receive it without running calibration. It is computed once per JVM, on first use.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FirstAttemptBoundaries
{
	/**
	 * The campaign's minimum pass rate {@code p_min}.
	 */
	private static final BigDecimal MINIMUM_PASS_RATE = new BigDecimal("0.95");

	/**
	 * The cap of certification attempt 1.
	 */
	public static final int CAP = 262;

	/**
	 * Returns the decision boundaries of certification attempt 1.
	 *
	 * @return the immutable boundaries
	 */
	public static DecisionBoundaries get()
	{
		return Holder.BOUNDARIES;
	}

	/**
	 * Prevents construction.
	 */
	private FirstAttemptBoundaries()
	{
	}

	/**
	 * Defers the computation until first use; the JVM initializes this class once, safely across threads.
	 */
	private static final class Holder
	{
		/**
		 * The boundaries.
		 */
		private static final DecisionBoundaries BOUNDARIES =
			DecisionBoundaries.compute(ErrorBudget.certificationLevel(1), MINIMUM_PASS_RATE, CAP, List.of());

		/**
		 * Prevents construction.
		 */
		private Holder()
		{
		}
	}
}
