package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.List;

/**
 * A separately certified material use category.
 *
 * @param id stable category identity
 * @param rationale reason separate treatment is required
 * @param requirementIds requirements in the category
 */
public record Category(String id, String rationale, List<String> requirementIds)
{
	/**
 * Validates and defensively copies a category.
 */
	public Category
	{
		ContractValues.nonBlank(id, "category id");
		ContractValues.nonBlank(rationale, "category rationale");
		requirementIds = List.copyOf(requirementIds);
	}
}
