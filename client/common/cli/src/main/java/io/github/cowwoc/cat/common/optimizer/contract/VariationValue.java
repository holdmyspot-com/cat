package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * One permitted value of a variation dimension, with the probability at which the generator draws it.
 *
 * @param label the short name that reports and assignment records use for the value
 * @param text the text that replaces the dimension's placeholder in each turn
 * @param probability the frozen probability of drawing this value, in the range (0, 1]
 */
public record VariationValue(String label, String text, double probability)
{
	/**
	 * Creates a variation value.
	 *
	 * @param label the short name that reports and assignment records use for the value
	 * @param text the text that replaces the dimension's placeholder in each turn
	 * @param probability the frozen probability of drawing this value, in the range (0, 1]
	 * @throws NullPointerException if {@code label} or {@code text} are null
	 * @throws IllegalArgumentException if {@code label} is blank or {@code probability} is outside (0, 1]
	 */
	public VariationValue
	{
		requireThat(label, "label").isNotBlank();
		requireThat(text, "text").isNotNull();
		requireThat(probability, "probability").isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
	}
}
