package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Duration;

/**
 * A scenario's {@code ## Expected Behavior} section: what a compliant agent does across the turns, and how long it
 * needs.
 *
 * @param description what a compliant agent does across the turns, or that an obligated action does not occur when
 *                    the prompt test leaves its trigger absent
 * @param timeLimit   how long a compliant agent needs, with margin, in whole seconds
 */
public record ExpectedBehavior(String description, Duration timeLimit)
{
	/**
	 * Creates an expected behavior.
	 *
	 * @param description what a compliant agent does across the turns, or that an obligated action does not occur
	 *                    when the prompt test leaves its trigger absent
	 * @param timeLimit   how long a compliant agent needs, with margin, in whole seconds
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code description} is blank, or {@code timeLimit} is not positive or
	 *                                  includes fractional seconds
	 */
	public ExpectedBehavior
	{
		requireThat(description, "description").isNotBlank();
		requireThat(timeLimit, "timeLimit").isGreaterThan(Duration.ZERO);
		requireThat(timeLimit.getNano(), "timeLimit.getNano()").isEqualTo(0);
	}
}
