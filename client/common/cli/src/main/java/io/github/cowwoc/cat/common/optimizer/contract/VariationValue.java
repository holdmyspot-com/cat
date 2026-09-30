package io.github.cowwoc.cat.common.optimizer.contract;

import java.math.BigDecimal;

/**
 * One approved concrete value and its exact sampling probability.
 *
 * @param value concrete template value
 * @param probability exact sampling probability
 */
public record VariationValue(String value, BigDecimal probability)
{
	/**
 * Validates a variation value.
 */
	public VariationValue
	{
		ContractValues.nonBlank(value, "variation value");
		if (probability == null || probability.signum() <= 0)
			throw new IllegalArgumentException("Variation probability must be positive");
	}
}
