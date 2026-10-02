package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;

/**
 * Input-token observations of completed trial outcomes for one snapshot and cell.
 *
 * @param outcomes the completed outcomes, including those whose harness did not report usage
 * @param tokens one input-token count per outcome that reported usage, including cached input
 */
public record TrialInputs(long outcomes, List<Long> tokens)
{
	/**
	 * No trial outcome has been observed.
	 */
	public static final TrialInputs EMPTY = new TrialInputs(0, List.of());

	/**
	 * Creates observations.
	 *
	 * @param outcomes the completed outcomes
	 * @param tokens the input tokens of outcomes that reported usage
	 * @throws NullPointerException if tokens or an element is null
	 * @throws IllegalArgumentException if counts are negative or more measurements than outcomes are supplied
	 */
	public TrialInputs
	{
		requireThat(outcomes, "outcomes").isNotNegative();
		tokens = List.copyOf(tokens);
		requireThat((long) tokens.size(), "measurements").isLessThanOrEqualTo(outcomes, "outcomes");
		for (long token : tokens)
			requireThat(token, "token").isNotNegative();
	}

	/**
	 * Records one completed outcome's observed input tokens.
	 *
	 * @param input the input tokens, or {@code null} if the harness did not report usage
	 * @return the updated observations
	 * @throws IllegalArgumentException if input is negative
	 */
	public TrialInputs add(Long input)
	{
		List<Long> updated = new ArrayList<>(tokens);
		if (input != null)
			updated.add(input);
		return new TrialInputs(outcomes + 1, updated);
	}
}
