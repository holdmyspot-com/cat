package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A test case that a contract author wrote for the development part of a test, to reproduce a formal failure group of
 * a certification attempt. It joins development once it reproduces the group. Its expected outcome is the outcome of
 * no held-out test case, so it changes neither part of the confirmed split.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param attempt the index of the certification attempt whose formal failures led to the test case
 * @param testId the identifier of the prompt test
 * @param testCase the test case
 */
public record AddedTestCase(int attempt, String testId, TestCase testCase)
{
	/**
	 * Creates an added test case.
	 *
	 * @param attempt the index of the certification attempt whose formal failures led to the test case
	 * @param testId the identifier of the prompt test
	 * @param testCase the test case
	 * @throws NullPointerException if {@code testId} or {@code testCase} are null
	 * @throws IllegalArgumentException if {@code attempt} is not positive or {@code testId} is blank
	 */
	public AddedTestCase
	{
		requireThat(attempt, "attempt").isPositive();
		requireThat(testId, "testId").isNotBlank();
		requireThat(testCase, "testCase").isNotNull();
	}
}
