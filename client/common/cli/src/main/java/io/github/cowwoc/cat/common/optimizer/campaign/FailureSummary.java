package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * What development learns of a certification failure, whose instance is held out: the failure's cell, test and failed
 * requirements, without its instance, reply or rationale.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cellId the identifier of the cell in which the failure occurred
 * @param testId the identifier of the test definition that the failed instance instantiates
 * @param failedRequirements the identifiers of the requirements that the failure violated
 */
public record FailureSummary(String cellId, String testId, List<String> failedRequirements)
{
	/**
	 * Creates a summary.
	 *
	 * @param cellId the identifier of the cell in which the failure occurred
	 * @param testId the identifier of the test definition that the failed instance instantiates
	 * @param failedRequirements the identifiers of the requirements that the failure violated
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
