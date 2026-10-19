package com.holdmyspot.cat.common.optimizer.statistics;

import java.util.function.DoublePredicate;

/**
 * Finds, in floating-point arithmetic, the point in {@code [0, 1]} where a condition that holds below the point stops
 * holding.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class Bisection
{
	/**
	 * The number of bisection steps, which narrows the bracket to {@code 2^-100}.
	 */
	private static final int STEPS = 100;

	/**
	 * Prevents instantiation.
	 */
	private Bisection()
	{
	}

	/**
	 * Returns the final bisection bracket around the point in {@code [0, 1]} where {@code holds} stops holding.
	 *
	 * @param holds a condition on a value in {@code [0, 1]} that holds below the point and not above it
	 * @return the bracket; its lower end satisfies {@code holds} unless it is 0, and its upper end does not unless it
	 *   is 1
	 */
	static Interval bracket(DoublePredicate holds)
	{
		double low = 0;
		double high = 1;
		for (int step = 0; step < STEPS; ++step)
		{
			double middle = (low + high) / 2;
			if (holds.test(middle))
				low = middle;
			else
				high = middle;
		}
		return new Interval(low, high);
	}
}
