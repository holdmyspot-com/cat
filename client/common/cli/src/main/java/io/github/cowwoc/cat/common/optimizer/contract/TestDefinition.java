package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.List;

/**
 * A fixed semantic test definition with approved incidental variation.
 *
 * @param id stable test identity
 * @param categoryId covered material category
 * @param requirementIds covered requirements
 * @param applicabilityPredicate explicit applicability condition
 * @param turnTemplates ordered fixed task templates
 * @param expectedOutcome expected compliant behavior
 * @param sessionMode required session mode
 * @param requiredHostTools required host tools
 * @param variationDimensions approved incidental variations
 */
public record TestDefinition(String id, String categoryId, List<String> requirementIds, String applicabilityPredicate,
	List<String> turnTemplates, String expectedOutcome, SessionMode sessionMode, List<String> requiredHostTools,
	List<VariationDimension> variationDimensions)
{
	/**
 * Validates and defensively copies a test definition.
 */
	public TestDefinition
	{
		ContractValues.nonBlank(id, "test id"); ContractValues.nonBlank(categoryId, "test category");
		ContractValues.nonBlank(applicabilityPredicate, "applicability predicate");
		if (sessionMode == null) throw new IllegalArgumentException("Session mode is missing");
		turnTemplates = List.copyOf(turnTemplates);
		if (turnTemplates.isEmpty() || turnTemplates.stream().anyMatch(value -> value == null || value.isBlank()) ||
			(sessionMode == SessionMode.SINGLE_TURN && turnTemplates.size() != 1) ||
			(sessionMode == SessionMode.MULTI_TURN && turnTemplates.size() < 2))
			throw new IllegalArgumentException("Turn templates are invalid for session mode");
		ContractValues.nonBlank(expectedOutcome, "expected outcome");
		requirementIds = List.copyOf(requirementIds); requiredHostTools = List.copyOf(requiredHostTools);
		variationDimensions = List.copyOf(variationDimensions);
	}
}
