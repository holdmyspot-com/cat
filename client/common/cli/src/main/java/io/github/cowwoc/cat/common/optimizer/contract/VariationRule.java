package io.github.cowwoc.cat.common.optimizer.contract;

import io.github.cowwoc.cat.common.Identifier;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One named input varied between trials, called a dimension, with its permitted values and their probabilities.
 *
 * @param dimension the placeholder name used in turns and check parameters as {@code {{dimension}}}
 * @param values the permitted values; reject their probability sum when it differs from 1 by more than 1e-9
 */
public record VariationRule(String dimension, List<VariationValue> values)
{
	/**
	 * Reject a probability sum when its distance from 1 exceeds this boundary, allowing for decimal rounding.
	 */
	private static final double SUM_TOLERANCE = 1e-9;

	/**
	 * Creates a variation rule.
	 *
	 * @param dimension the placeholder name used in turns and check parameters as {@code {{dimension}}}
	 * @param values the permitted values; reject their probability sum when it differs from 1 by more than 1e-9
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code dimension} is not lowercase words joined by underscores,
	 *   {@code values} is empty, two values share a label, or the probability sum differs from 1 by more than 1e-9
	 */
	public VariationRule
	{
		requireThat(dimension, "dimension").matches(Identifier.SNAKE_CASE);
		requireThat(values, "values").isNotEmpty();
		values = List.copyOf(values);

		Set<String> labels = new HashSet<>();
		double sum = 0;
		for (VariationValue value : values)
		{
			if (!labels.add(value.label()))
			{
				throw new IllegalArgumentException("Dimension '" + dimension + "' lists the label '" + value.label() +
					"' twice");
			}
			sum += value.probability();
		}
		if (Math.abs(sum - 1.0) > SUM_TOLERANCE)
		{
			throw new IllegalArgumentException("The probabilities of dimension '" + dimension +
				"' must sum to 1, but they sum to " + sum);
		}
	}
}
