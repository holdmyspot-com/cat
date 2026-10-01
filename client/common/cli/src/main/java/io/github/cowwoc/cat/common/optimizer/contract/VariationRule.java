package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A dimension along which the instances of a test definition vary, with the probability of each value.
 *
 * @param dimension the placeholder name that turns use as {@code {{dimension}}}
 * @param values the permitted values, whose probabilities sum to 1
 */
public record VariationRule(String dimension, List<VariationValue> values)
{
	/**
	 * The names that a placeholder can have.
	 */
	static final Pattern DIMENSION_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
	/**
	 * How far the probabilities may sum from 1, which absorbs the rounding of decimal probabilities.
	 */
	private static final double SUM_TOLERANCE = 1e-9;

	/**
	 * Creates a variation rule.
	 *
	 * @param dimension the placeholder name that turns use as {@code {{dimension}}}
	 * @param values the permitted values, whose probabilities sum to 1
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code dimension} is not an identifier, {@code values} is empty, two
	 *   values share a label, or the probabilities do not sum to 1
	 */
	public VariationRule
	{
		requireThat(dimension, "dimension").matches(DIMENSION_NAME);
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
