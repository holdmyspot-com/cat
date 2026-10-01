package io.github.cowwoc.cat.common.optimizer.statistics;

import java.util.List;

/**
 * Supplies the {@link DecisionBoundaries} of certification attempt 1, without comparisons, to tests in this module
 * and in modules that use its test JAR.
 * <p>
 * The table uses {@code alpha_1} and the {@code design.md} &sect;2.4 cap of 262, which {@code CapCalibrationTest}
 * verifies; tests receive it without running calibration. It is computed once per JVM, on first use.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FirstAttemptBoundaries
{
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
			DecisionBoundaries.compute(ErrorBudget.certificationLevel(1), CAP, List.of());

		/**
		 * Prevents construction.
		 */
		private Holder()
		{
		}
	}
}
