package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Instant;

/**
 * A trial attempt that ended without a pass or fail outcome. The trial runs again with the same test case.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param endedAt when the attempt ended
 * @param failure why the attempt ended
 * @param detail what the harness or CAT reported about the failure
 */
public record InfrastructureAttempt(Instant endedAt, TrialAttemptFailure failure, String detail)
{
	/**
	 * Creates an attempt.
	 *
	 * @param endedAt when the attempt ended
	 * @param failure why the attempt ended
	 * @param detail what the harness or CAT reported about the failure
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code detail} is blank
	 */
	public InfrastructureAttempt
	{
		requireThat(endedAt, "endedAt").isNotNull();
		requireThat(failure, "failure").isNotNull();
		requireThat(detail, "detail").isNotBlank();
	}
}
