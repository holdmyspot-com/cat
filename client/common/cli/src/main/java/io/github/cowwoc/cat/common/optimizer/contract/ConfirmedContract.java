package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.List;

/**
 * An immutable user-confirmed compliance contract for one lineage.
 *
 * @param schemaVersion supported schema discriminator
 * @param contractId canonical contract identity
 * @param sourceProposalId reviewed proposal identity
 * @param promptSystem production prompt system
 * @param requirements extracted requirements
 * @param categories material categories
 * @param tests approved test definitions
 * @param developmentAllocationPolicy confirmed category-selection rule for representative development batches
 * @param developmentReadiness development readiness rule
 * @param developmentRoundPolicy confirmed limits for diagnosis and candidate generation within a round
 * @param formalProcedure frozen formal procedure
 * @param evaluator frozen evaluator authority
 * @param limitations retained limitations
 */
public record ConfirmedContract(int schemaVersion, String contractId, String sourceProposalId,
	PromptSystem promptSystem, List<Requirement> requirements, List<Category> categories, List<TestDefinition> tests,
	DevelopmentAllocationPolicy developmentAllocationPolicy, DevelopmentReadiness developmentReadiness,
	DevelopmentRoundPolicy developmentRoundPolicy,
	FormalProcedure formalProcedure, EvaluatorDefinition evaluator,
	List<String> limitations)
{
	/**
 * The only supported confirmed-contract schema.
 */
	public static final int SCHEMA_VERSION = 6;

	/**
 * Validates and defensively copies the confirmed graph.
 */
	public ConfirmedContract
	{
		if (schemaVersion != SCHEMA_VERSION)
			throw new IllegalArgumentException("Unsupported contract schema version: " + schemaVersion);
		ContractValues.nonBlank(contractId, "contract id");
		ContractValues.nonBlank(sourceProposalId, "source proposal id");
		if (developmentAllocationPolicy == null)
			throw new IllegalArgumentException("Development allocation policy is required");
		if (developmentRoundPolicy == null)
			throw new IllegalArgumentException("Development round policy is required");
		if (promptSystem == null || developmentReadiness == null || formalProcedure == null || evaluator == null)
			throw new IllegalArgumentException("Confirmed contract is incomplete");
		requirements = List.copyOf(requirements);
		categories = List.copyOf(categories);
		tests = List.copyOf(tests);
		limitations = List.copyOf(limitations);
	}
}
