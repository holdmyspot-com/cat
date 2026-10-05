package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * What development learns of a certification failure, whose instance is held out: the failure's cell, test and failed
 * requirements with their behaviors, without its instance, reply or rationale.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cellId the identifier of the cell in which the failure occurred
 * @param testId the identifier of the test definition that the failed instance instantiates
 * @param failedRequirements the requirements that the failure violated, each with its behavior
 */
public record FailureSummary(String cellId, String testId, List<FailedRequirement> failedRequirements)
{
	/**
	 * Creates a summary.
	 *
	 * @param cellId the identifier of the cell in which the failure occurred
	 * @param testId the identifier of the test definition that the failed instance instantiates
	 * @param failedRequirements the requirements that the failure violated, each with its behavior
	 * @throws NullPointerException if any of the arguments are null or {@code failedRequirements} contains null
	 * @throws IllegalArgumentException if {@code cellId} or {@code testId} are blank
	 */
	public FailureSummary
	{
		requireThat(cellId, "cellId").isNotBlank();
		requireThat(testId, "testId").isNotBlank();
		requireThat(failedRequirements, "failedRequirements").isNotNull();
		failedRequirements = List.copyOf(failedRequirements);
	}
}
