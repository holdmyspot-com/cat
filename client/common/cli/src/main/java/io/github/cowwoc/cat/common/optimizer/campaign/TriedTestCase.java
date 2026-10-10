package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCase;

/**
 * A test case that a reproduction round tried, with its reproduction counts.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param testCase the test case
 * @param trials the round's adopted trials of the test case
 * @param failures the trials among {@code trials} that failed the group's requirement through the group's behavior
 */
public record TriedTestCase(AddedTestCase testCase, int trials, int failures)
{
	/**
	 * Creates a tried test case.
	 *
	 * @param testCase the test case
	 * @param trials the round's adopted trials of the test case
	 * @param failures the trials among {@code trials} that failed the group's requirement through the group's
	 *   behavior
	 * @throws NullPointerException if {@code testCase} is null
	 * @throws IllegalArgumentException if {@code trials} or {@code failures} are negative, or {@code failures}
	 *   exceeds {@code trials}
	 */
	public TriedTestCase
	{
		requireThat(testCase, "testCase").isNotNull();
		requireThat(trials, "trials").isNotNegative();
		requireThat(failures, "failures").isNotNegative().isLessThanOrEqualTo(trials, "trials");
	}

	/**
	 * Returns this test case with one more adopted trial.
	 *
	 * @param failed {@code true} if the trial failed the group's requirement through the group's behavior
	 * @return the updated test case
	 */
	TriedTestCase add(boolean failed)
	{
		int added = 0;
		if (failed)
			added = 1;
		return new TriedTestCase(testCase, trials + 1, failures + added);
	}
}
