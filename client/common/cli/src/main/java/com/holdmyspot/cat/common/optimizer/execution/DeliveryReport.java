package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Applicability;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult;
import com.holdmyspot.cat.common.optimizer.report.CertifiedEnvironment;

/**
 * The outcome of a delivery that a slice attempted.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param result        what the delivery did
 * @param applicability for a delivered candidate, whether its result still describes the current prompt content and
 *                      measurement conditions; {@code null} for a delivery that did not complete
 * @param environment   for a delivered certification, the environment that it was measured in; {@code null} for an
 *                      uncertified delivery, a kept incumbent's prompt tests or a delivery that did not complete
 */
public record DeliveryReport(DeliveryResult result, Applicability applicability, CertifiedEnvironment environment)
{
	/**
	 * Creates a report.
	 *
	 * @param result        what the delivery did
	 * @param applicability for a delivered candidate, whether its result still describes the current prompt content
	 *                      and measurement conditions; {@code null} for a delivery that did not complete
	 * @param environment   for a delivered certification, the environment that it was measured in; {@code null} for
	 *                      an uncertified delivery, a kept incumbent's prompt tests or a delivery that did not complete
	 * @throws NullPointerException     if {@code result} is null, or if {@code applicability} is null for a delivered
	 *                                  candidate
	 * @throws IllegalArgumentException if {@code applicability} or {@code environment} are set for a delivery that
	 *                                  did not complete
	 */
	public DeliveryReport
	{
		requireThat(result, "result").isNotNull();
		if (result instanceof DeliveryResult.Delivered)
			requireThat(applicability, "applicability").isNotNull();
		else
		{
			requireThat(applicability, "applicability").isNull();
			requireThat(environment, "environment").isNull();
		}
	}

	/**
	 * Creates a report of a delivery that delivered no certification.
	 *
	 * @param result        what the delivery did
	 * @param applicability for a delivered candidate, whether its result still describes the current prompt content
	 *                      and measurement conditions; {@code null} for a delivery that did not complete
	 * @throws NullPointerException     if {@code result} is null, or if {@code applicability} is null for a delivered
	 *                                  candidate
	 * @throws IllegalArgumentException if {@code applicability} is set for a delivery that did not complete
	 */
	public DeliveryReport(DeliveryResult result, Applicability applicability)
	{
		this(result, applicability, null);
	}
}
