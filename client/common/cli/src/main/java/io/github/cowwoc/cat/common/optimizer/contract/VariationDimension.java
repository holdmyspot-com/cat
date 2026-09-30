package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.List;

/**
 * A confirmed incidental-value sampling dimension.
 *
 * @param id stable dimension identity
 * @param placeholder template placeholder
 * @param values approved values and probabilities
 */
public record VariationDimension(String id, String placeholder, List<VariationValue> values)
{
	/**
 * Validates and defensively copies a variation dimension.
 */
	public VariationDimension
	{
		ContractValues.nonBlank(id, "variation dimension id");
		ContractValues.nonBlank(placeholder, "variation placeholder");
		values = List.copyOf(values);
	}
}
