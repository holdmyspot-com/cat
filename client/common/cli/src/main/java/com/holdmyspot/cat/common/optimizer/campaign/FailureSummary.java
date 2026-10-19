package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * What development learns of a certification failure, whose test case is held out: the failure's cell, test and failed
 * assertions with their behaviors, without its test case, reply or rationale.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cellId the identifier of the cell in which the failure occurred
 * @param testId the identifier of the prompt test that the failed test case belongs to
 * @param failedAssertions the assertions that the failure failed, each with its behavior
 */
public record FailureSummary(String cellId, String testId, List<FailedAssertion> failedAssertions)
{
	/**
	 * Creates a summary.
	 *
	 * @param cellId the identifier of the cell in which the failure occurred
	 * @param testId the identifier of the prompt test that the failed test case belongs to
	 * @param failedAssertions the assertions that the failure failed, each with its behavior
	 * @throws NullPointerException if any of the arguments are null or {@code failedAssertions} contains null
	 * @throws IllegalArgumentException if {@code cellId} or {@code testId} are blank
	 */
	public FailureSummary
	{
		requireThat(cellId, "cellId").isNotBlank();
		requireThat(testId, "testId").isNotBlank();
		requireThat(failedAssertions, "failedAssertions").isNotNull();
		failedAssertions = List.copyOf(failedAssertions);
	}
}
