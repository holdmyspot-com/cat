package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A candidate that the campaign decided to deliver to the base worktree.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param candidate the candidate to deliver
 * @param attempt the certification attempt whose frozen snapshot is delivered, or 0 for an uncertified working
 *   candidate
 * @param label the label that the delivered result carries
 */
public record DeliveryRequest(Candidate candidate, int attempt, Outcome label)
{
	/**
	 * Creates a request.
	 *
	 * @param candidate the candidate to deliver
	 * @param attempt the certification attempt whose frozen snapshot is delivered, or 0 for an uncertified working
	 *   candidate
	 * @param label the label that the delivered result carries
	 * @throws NullPointerException if {@code candidate} or {@code label} are null
	 * @throws IllegalArgumentException if {@code attempt} is negative
	 */
	public DeliveryRequest
	{
		requireThat(candidate, "candidate").isNotNull();
		requireThat(attempt, "attempt").isNotNegative();
		requireThat(label, "label").isNotNull();
	}
}
