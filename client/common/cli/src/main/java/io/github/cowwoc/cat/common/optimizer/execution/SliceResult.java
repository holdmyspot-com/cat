package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.report.StatusReport;

/**
 * What one slice returns to the host agent.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param status the campaign's status after the slice
 * @param delivery the delivery that the slice attempted, or {@code null} if it attempted none
 */
public record SliceResult(StatusReport status, DeliveryReport delivery)
{
	/**
	 * Creates a result.
	 *
	 * @param status the campaign's status after the slice
	 * @param delivery the delivery that the slice attempted, or {@code null} if it attempted none
	 * @throws NullPointerException if {@code status} is null
	 */
	public SliceResult
	{
		requireThat(status, "status").isNotNull();
	}
}
