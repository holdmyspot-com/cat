package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * What a failure of an assertion's built-in tool means. A passing built-in tool decides its assertion either way.
 */
public enum OnToolFailure
{
	/**
	 * The failure fails the trial, and no judge decides the assertion.
	 */
	FAIL,
	/**
	 * The failure leaves the assertion to the judge, which decides it from its statement and receives the tool's
	 * result as evidence rather than a verdict.
	 */
	JUDGE
}
