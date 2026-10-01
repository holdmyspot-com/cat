package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;

/**
 * The outcome of a delivery that a slice attempted.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param result what the delivery did
 * @param applicability for a delivered candidate, whether its result still describes the current prompt content and
 *   measurement conditions; {@code null} for a delivery that did not complete
 */
public record DeliveryReport(DeliveryResult result, Applicability applicability)
{
	/**
	 * Creates a report.
	 *
	 * @param result what the delivery did
	 * @param applicability for a delivered candidate, whether its result still describes the current prompt content
	 *   and measurement conditions; {@code null} for a delivery that did not complete
	 * @throws NullPointerException if {@code result} is null, or if {@code applicability} is null for a delivered
	 *   candidate
	 * @throws IllegalArgumentException if {@code applicability} is set for a delivery that did not complete
	 */
	public DeliveryReport
	{
		requireThat(result, "result").isNotNull();
		if (result instanceof DeliveryResult.Delivered)
			requireThat(applicability, "applicability").isNotNull();
		else
			requireThat(applicability, "applicability").isNull();
	}
}
