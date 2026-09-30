package io.github.cowwoc.cat.common.optimizer.contract;

import io.github.cowwoc.cat.common.optimizer.candidate.DeliveryWriteAuthority;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates the complete contract graph and all of its cross references.
 */
public final class ContractValidator
{
	/**
	 * Prevents construction.
	 */
	private ContractValidator()
	{
	}

	/**
	 * Validates a proposal graph.
	 *
	 * @param proposal graph to validate
	 */
	public static void validateProposal(ContractProposal proposal)
	{
		if (proposal == null)
			throw new IllegalArgumentException("Proposal is missing");
		validate(proposal.promptSystem(), proposal.requirements(), proposal.categories(), proposal.tests(),
			proposal.developmentReadiness(), proposal.formalProcedure(), proposal.evaluator());
		if (proposal.formalProcedure().method() != FormalMethod.ANYTIME_BERNOULLI)
			throw new IllegalArgumentException("New contracts require the anytime Bernoulli procedure");
	}

	/**
	 * Validates a persisted proposal, including an earlier fixed-sample method kept for historical results.
	 *
	 * @param proposal persisted proposal graph
	 */
	static void validateStoredProposal(ContractProposal proposal)
	{
		if (proposal == null)
			throw new IllegalArgumentException("Proposal is missing");
		validate(proposal.promptSystem(), proposal.requirements(), proposal.categories(), proposal.tests(),
			proposal.developmentReadiness(), proposal.formalProcedure(), proposal.evaluator());
	}

	/**
	 * Validates a confirmed-contract graph.
	 *
	 * @param contract graph to validate
	 */
	public static void validateConfirmed(ConfirmedContract contract)
	{
		if (contract == null)
			throw new IllegalArgumentException("Confirmed contract is missing");
		validate(contract.promptSystem(), contract.requirements(), contract.categories(), contract.tests(),
			contract.developmentReadiness(), contract.formalProcedure(), contract.evaluator());
	}

	/**
	 * Validates common proposal and confirmed-contract fields.
	 *
	 * @param system the prompt-system paths and source identities
	 * @param requirements the complete normative requirements
	 * @param categories the categories that cover those requirements
	 * @param tests the confirmed test definitions
	 * @param readiness the development-readiness policy
	 * @param procedure the formal statistical procedure
	 * @param evaluator the frozen semantic evaluator definition
	 */
	private static void validate(PromptSystem system, List<Requirement> requirements,
		List<Category> categories, List<TestDefinition> tests, DevelopmentReadiness readiness,
		FormalProcedure procedure, EvaluatorDefinition evaluator)
	{
		validatePromptSystem(system);
		unique(requirements.stream().map(Requirement::id).toList(), "requirement");
		unique(categories.stream().map(Category::id).toList(), "category");
		unique(tests.stream().map(TestDefinition::id).toList(), "test");

		Set<String> requirementIds = Set.copyOf(requirements.stream().map(Requirement::id).toList());
		Set<java.nio.file.Path> promptPaths = Set.copyOf(system.sources().stream().map(PromptSource::path).toList());
		for (Requirement requirement : requirements)
			if (!promptPaths.contains(requirement.source().path()))
				throw new IllegalArgumentException("Requirement source is not an observed prompt source");
		Set<String> categoryIds = Set.copyOf(categories.stream().map(Category::id).toList());
		validateCategories(categories, requirementIds);
		validateTests(tests, categoryIds, requirementIds);
		validateReadiness(readiness, categoryIds);
		validateProcedure(procedure);
		if (evaluator == null)
			throw new IllegalArgumentException("Evaluator is missing");
	}

	/**
	 * Validates target, source, and editable-root relationships.
	 *
	 * @param system the prompt-system paths and source identities
	 */
	private static void validatePromptSystem(PromptSystem system)
	{
		if (system.sources().isEmpty() || system.sources().stream().noneMatch(
			source -> source.path().equals(system.targetPath())))
		{
			throw new IllegalArgumentException("Target source is missing");
		}
		unique(system.sources().stream().map(source -> source.path().toString()).toList(), "prompt source");
		PromptSource targetSource = system.sources().stream().filter(source ->
			source.path().equals(system.targetPath())).findFirst().orElseThrow();
		if (!new DeliveryWriteAuthority(system.editableRoots()).permits(targetSource.resolvedSourcePath()))
			throw new IllegalArgumentException("Target is outside editable roots");
	}

	/**
	 * Validates category coverage.
	 *
	 * @param categories the categories to validate
	 * @param requirementIds every confirmed requirement identifier
	 */
	private static void validateCategories(List<Category> categories, Set<String> requirementIds)
	{
		Set<String> coveredRequirements = new HashSet<>();
		for (Category category : categories)
		{
			nonEmpty(category.requirementIds(), "Category has no requirements");
			references(category.requirementIds(), requirementIds, "category requirement");
			coveredRequirements.addAll(category.requirementIds());
		}
		if (!coveredRequirements.containsAll(requirementIds))
			throw new IllegalArgumentException("Every requirement must have category coverage");
	}

	/**
	 * Validates test coverage and variation definitions.
	 *
	 * @param tests the test definitions to validate
	 * @param categoryIds every confirmed category identifier
	 * @param requirementIds every confirmed requirement identifier
	 */
	private static void validateTests(List<TestDefinition> tests, Set<String> categoryIds,
		Set<String> requirementIds)
	{
		for (TestDefinition test : tests)
		{
			if (!categoryIds.contains(test.categoryId()))
				throw new IllegalArgumentException("Test references unknown category: " + test.categoryId());
			nonEmpty(test.requirementIds(), "Test has no requirements");
			references(test.requirementIds(), requirementIds, "test requirement");
			validateVariations(test);
		}
		for (String categoryId : categoryIds)
		{
			if (tests.stream().noneMatch(test -> test.categoryId().equals(categoryId)))
				throw new IllegalArgumentException("Category has no applicable test: " + categoryId);
		}
	}

	/**
	 * Validates the frozen development readiness rule.
	 *
	 * @param readiness the readiness policy to validate
	 * @param categoryIds every confirmed category identifier
	 */
	private static void validateReadiness(DevelopmentReadiness readiness, Set<String> categoryIds)
	{
		boolean hasInvalidMinimum = readiness.minimumEvidence().values().stream().anyMatch(
			value -> value == null || value <= 0);
		if (!readiness.minimumEvidence().keySet().equals(categoryIds) || hasInvalidMinimum)
		{
			throw new IllegalArgumentException("Readiness evidence must be positive for every category");
		}
	}

	/**
	 * Validates the frozen formal procedure and its constrained assumptions.
	 *
	 * @param procedure the formal procedure to validate
	 */
	public static void validateProcedure(FormalProcedure procedure)
	{
		if (procedure.threshold().compareTo(new BigDecimal("0.95")) != 0 ||
			!procedure.allowanceFormula().equals("0.05/(i*(i+1))") ||
			procedure.freshSessionPolicy() != FreshSessionPolicy.FRESH_PER_LOGICAL_TRIAL)
		{
			throw new IllegalArgumentException("Formal procedure conflicts with the certification claim");
		}
		if (procedure.planningRate().compareTo(procedure.threshold()) <= 0 ||
			procedure.planningRate().compareTo(BigDecimal.ONE) > 0)
			throw new IllegalArgumentException("Formal planning rate must exceed the threshold and be at most one");
		if (procedure.minimumPower().compareTo(BigDecimal.ZERO) <= 0 ||
			procedure.minimumPower().compareTo(BigDecimal.ONE) >= 0)
			throw new IllegalArgumentException("Formal minimum power must be above zero and below one");
		FormalAssumptions assumptions = procedure.assumptions();
		boolean fixed = procedure.method() == FormalMethod.FIXED_SAMPLE_EXACT &&
			assumptions.stoppingRule() == StoppingRule.FIXED_N_NO_EARLY_DECISION;
		boolean anytime = procedure.method() == FormalMethod.ANYTIME_BERNOULLI &&
			assumptions.stoppingRule() == StoppingRule.ANYTIME_FROZEN_CAP;
		if (assumptions.outcomeModel() != OutcomeModel.IID_BERNOULLI_WITHIN_CATEGORY || !(fixed || anytime))
		{
			throw new IllegalArgumentException("Formal assumptions are invalid");
		}
		Set<DependenceSource> sources = EnumSet.noneOf(DependenceSource.class);
		for (DependenceCondition condition : assumptions.dependenceConditions())
			if (!sources.add(condition.source()))
				throw new IllegalArgumentException("Duplicate dependence source: " + condition.source());
		for (DependenceSource required : List.of(DependenceSource.SHARED_SESSION,
			DependenceSource.PROVIDER_CACHE, DependenceSource.THROTTLING,
			DependenceSource.EVALUATOR_MEMORY, DependenceSource.ADAPTIVE_ROUTING))
		{
			if (!sources.contains(required))
				throw new IllegalArgumentException("Missing dependence source: " + required);
		}
	}

	/**
	 * Validates approved incidental variation sampling.
	 *
	 * @param test the test definition whose variation dimensions are validated
	 */
	private static void validateVariations(TestDefinition test)
	{
		Set<String> placeholders = new HashSet<>();
		Set<String> dimensionIds = new HashSet<>();
		for (VariationDimension dimension : test.variationDimensions())
		{
			if (!dimensionIds.add(dimension.id()))
				throw new IllegalArgumentException("Duplicate variation dimension id: " + dimension.id());
			if (!placeholders.add(dimension.placeholder()))
				throw new IllegalArgumentException("Duplicate variation placeholder: " + dimension.placeholder());
			nonEmpty(dimension.values(), "Variation dimension has no values");
			unique(dimension.values().stream().map(VariationValue::value).toList(), "variation value");
			BigDecimal total = dimension.values().stream().map(VariationValue::probability).
				reduce(BigDecimal.ZERO, BigDecimal::add);
			if (total.compareTo(BigDecimal.ONE) != 0)
				throw new IllegalArgumentException("Variation probabilities must sum to one");
		}
		Set<String> templatePlaceholders = new HashSet<>();
		for (String turn : test.turnTemplates())
			templatePlaceholders.addAll(placeholders(turn));
		if (!templatePlaceholders.equals(placeholders))
			throw new IllegalArgumentException("Template placeholders must exactly match variation placeholders");
	}

	/**
	 * Extracts all double-brace placeholders from a template.
	 *
	 * @param template task template
	 * @return placeholder set
	 */
	private static Set<String> placeholders(String template)
	{
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{\\{[^{}]+}}").matcher(template);
		Set<String> result = new HashSet<>();
		while (matcher.find())
			result.add(matcher.group());
		return result;
	}

	/**
	 * Rejects duplicated or blank identifiers.
	 *
	 * @param ids the identifiers to validate
	 * @param type the domain type named in a validation failure
	 */
	private static void unique(List<String> ids, String type)
	{
		if (ids.stream().anyMatch(value -> value == null || value.isBlank()) ||
			new HashSet<>(ids).size() != ids.size())
		{
			throw new IllegalArgumentException("Duplicate or blank " + type + " id");
		}
	}

	/**
	 * Rejects an empty collection.
	 *
	 * @param values the collection that must contain at least one value
	 * @param message the validation failure message
	 */
	private static void nonEmpty(List<?> values, String message)
	{
		if (values.isEmpty())
			throw new IllegalArgumentException(message);
	}

	/**
	 * Rejects references not present in the supplied identifiers.
	 *
	 * @param values the referenced identifiers
	 * @param known the complete set of allowed identifiers
	 * @param name the relationship named in a validation failure
	 */
	private static void references(List<String> values, Set<String> known, String name)
	{
		if (!known.containsAll(values))
			throw new IllegalArgumentException("Unknown " + name);
	}
}
