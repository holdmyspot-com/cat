package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Instant;

/**
 * The scheduler's rule, adopted-outcome count and time when it assigned a trial. Retries retain this decision.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param rule the allocation rule that chose the trial
 * @param evidenceRevision the campaign's {@link Campaign#evidenceRevision() evidence revision} that the decision
 *   used, which is the number of outcomes the campaign had adopted
 * @param assignedAt when the trial was assigned
 */
public record Allocation(AllocationRule rule, long evidenceRevision, Instant assignedAt)
{
	/**
	 * Creates an allocation decision.
	 *
	 * @param rule the allocation rule that chose the trial
	 * @param evidenceRevision the campaign's evidence revision that the decision used
	 * @param assignedAt when the trial was assigned
	 * @throws NullPointerException if {@code rule} or {@code assignedAt} are null
	 * @throws IllegalArgumentException if {@code evidenceRevision} is negative
	 */
	public Allocation
	{
		requireThat(rule, "rule").isNotNull();
		requireThat(evidenceRevision, "evidenceRevision").isNotNegative();
		requireThat(assignedAt, "assignedAt").isNotNull();
	}
}
