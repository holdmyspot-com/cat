package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.List;

/**
 * The frozen assumptions that make the formal procedure interpretable.
 *
 * @param outcomeModel outcome distribution model
 * @param stoppingRule allowed decision stopping rule
 * @param dependenceConditions retained dependence conditions
 */
public record FormalAssumptions(OutcomeModel outcomeModel, StoppingRule stoppingRule,
	List<DependenceCondition> dependenceConditions)
{
	/**
 * Validates and defensively copies formal assumptions.
 */
	public FormalAssumptions
	{
		if (outcomeModel == null || stoppingRule == null)
			throw new IllegalArgumentException("Formal assumptions are incomplete");
		dependenceConditions = List.copyOf(dependenceConditions);
	}
}
