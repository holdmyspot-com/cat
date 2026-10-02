package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The value that a random draw selected for one named input varied between trials.
 *
 * @param dimension the dimension name
 * @param label the label of the selected value
 * @param probability the probability of selecting that value
 */
public record VariationChoice(String dimension, String label, double probability)
{
	/**
	 * Creates a choice.
	 *
	 * @param dimension the dimension name
	 * @param label the label of the selected value
	 * @param probability the probability of selecting that value
	 * @throws NullPointerException if {@code dimension} or {@code label} are null
	 * @throws IllegalArgumentException if {@code dimension} or {@code label} are blank, or {@code probability} is
	 *   outside (0, 1]
	 */
	public VariationChoice
	{
		requireThat(dimension, "dimension").isNotBlank();
		requireThat(label, "label").isNotBlank();
		VariationValue.requireProbability(probability);
	}
}
