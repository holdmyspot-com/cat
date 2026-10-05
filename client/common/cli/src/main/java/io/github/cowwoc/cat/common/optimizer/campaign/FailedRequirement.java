package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A requirement that a trial failed, and the behavior through which it failed.
 * <p>
 * A failed deterministic check contributes the behavior that the check detects. A judge contributes one of the
 * requirement's listed behaviors, or {@code other} when none of them describes the failure.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param requirementId the identifier of the failed requirement
 * @param behavior the behavior through which the requirement failed
 */
public record FailedRequirement(String requirementId, String behavior)
{
	/**
	 * Creates a failed requirement.
	 *
	 * @param requirementId the identifier of the failed requirement
	 * @param behavior the behavior through which the requirement failed
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if any of the arguments are blank
	 */
	public FailedRequirement
	{
		requireThat(requirementId, "requirementId").isNotBlank();
		requireThat(behavior, "behavior").isNotBlank();
	}
}
