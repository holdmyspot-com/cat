package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;

/**
 * A test case that failed a sampled trial of an earlier prompt snapshot or of the original prompt system, retained
 * for replay to check later edits.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param testCase the test case that failed
 * @param working the working candidate's latest outcome on the test case, or {@code null} if it has none
 * @param settled {@code true} if the working candidate's failing outcome on the test case was already addressed by an
 *   optimization round that ended without a promotable attempt, so the failure no longer keeps the working candidate
 *   from certification; {@code false} if the outcome is not a failure or no such round ended after it
 */
public record RegressionTestCase(DrawnTestCase testCase, Verdict working, boolean settled)
{
	/**
	 * Creates a regression test case.
	 *
	 * @param testCase the test case that failed
	 * @param working the working candidate's latest outcome on the test case, or {@code null} if it has none
	 * @param settled {@code true} if the working candidate's failing outcome on the test case was already addressed by
	 *   an optimization round that ended without a promotable attempt, so the failure no longer keeps the working
	 *   candidate from certification; {@code false} if the outcome is not a failure or no such round ended after it
	 * @throws NullPointerException if {@code testCase} is null
	 * @throws IllegalArgumentException if {@code settled} is {@code true} and {@code working} is not
	 *   {@link Verdict#FAIL}
	 */
	public RegressionTestCase
	{
		requireThat(testCase, "testCase").isNotNull();
		if (settled)
			requireThat(working, "working").withContext(settled, "settled").isEqualTo(Verdict.FAIL);
	}
}
