package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The requirements and test definitions used to measure trial compliance. Categories group the tests; each cell
 * pairs a category with a model and reasoning-effort profile and keeps its trial counts and decisions separate.
 *
 * @param requirements every requirement and its source, including CAT's {@link Requirement#CORE_TASK}
 * @param categories the category order used to break otherwise equal allocation choices
 * @param profiles the trial profiles and the cells, which are the statistical units
 * @param judge the judge's model, effort and instructions, fixed when the user confirms the contract
 * @param fixtures the ignored files that trials need
 * @param limitations the material interactions and conditions that the contract does not cover
 */
public record Contract(List<Requirement> requirements, List<Category> categories, TrialProfiles profiles,
	JudgeProfile judge, TrialFixtures fixtures, List<String> limitations)
{
	/**
	 * Creates a contract.
	 *
	 * @param requirements every requirement and its source
	 * @param categories the category order used to break otherwise equal allocation choices
	 * @param profiles the trial profiles and the cells, which are the statistical units
	 * @param judge the judge's model, effort and instructions, fixed when the user confirms the contract
	 * @param fixtures the ignored files that trials need
	 * @param limitations the material interactions and conditions that the contract does not cover
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code requirements} or {@code categories} are empty, two requirements or two
	 *   categories share an identifier, a test shares an identifier with a test of another category, or a test names a
	 *   requirement that the contract does not list, the contract does not list {@link Requirement#CORE_TASK} or a
	 *   test does not check it, a category has no cell, or a cell names a category that the contract does not list
	 */
	public Contract
	{
		requireThat(requirements, "requirements").isNotEmpty();
		requireThat(categories, "categories").isNotEmpty();
		requireThat(profiles, "profiles").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(fixtures, "fixtures").isNotNull();
		requireThat(limitations, "limitations").isNotNull();
		requirements = List.copyOf(requirements);
		categories = List.copyOf(categories);
		limitations = List.copyOf(limitations);
		for (String limitation : limitations)
			requireThat(limitation, "limitation").isNotBlank();

		Set<String> requirementIds = new HashSet<>();
		for (Requirement requirement : requirements)
		{
			if (!requirementIds.add(requirement.id()))
				throw new IllegalArgumentException("The requirement '" + requirement.id() + "' is listed twice");
		}
		String coreTask = Requirement.CORE_TASK.id();
		if (!requirements.contains(Requirement.CORE_TASK))
		{
			throw new IllegalArgumentException("The contract must list CAT's requirement '" + coreTask + "' as " +
				Requirement.CORE_TASK + ", but it lists " + requirements);
		}
		Set<String> categoryIds = new HashSet<>();
		Set<String> testIds = new HashSet<>();
		for (Category category : categories)
		{
			if (!categoryIds.add(category.id()))
				throw new IllegalArgumentException("The category '" + category.id() + "' is listed twice");
			for (TestDefinition test : category.tests())
			{
				if (!testIds.add(test.id()))
				{
					throw new IllegalArgumentException("The test '" + test.id() + "' appears more than once, but a " +
						"test belongs to one category");
				}
				for (String requirementId : test.requirementIds())
				{
					if (!requirementIds.contains(requirementId))
					{
						throw new IllegalArgumentException("The test '" + test.id() + "' checks the requirement '" +
							requirementId + "', which the contract does not list. Listed requirements: " +
							requirementIds);
					}
				}
				if (!test.requirementIds().contains(coreTask))
				{
					throw new IllegalArgumentException("The test '" + test.id() + "' does not check the requirement '" +
						coreTask + "', which every test checks");
				}
			}
			if (profiles.cellsOf(category.id()).isEmpty())
				throw new IllegalArgumentException("The category '" + category.id() + "' has no cell");
		}
		for (Cell cell : profiles.cells())
		{
			if (!categoryIds.contains(cell.categoryId()))
			{
				throw new IllegalArgumentException("The cell '" + cell.id() + "' belongs to the category '" +
					cell.categoryId() + "', which the contract does not list");
			}
		}
	}

	/**
	 * Finds a category.
	 *
	 * @param id a category identifier
	 * @return the category with that identifier, or empty if the contract has none
	 */
	public Optional<Category> findCategory(String id)
	{
		return categories.stream().filter(category -> category.id().equals(id)).findFirst();
	}

	/**
	 * Finds a test definition.
	 *
	 * @param id a test identifier
	 * @return the test with that identifier, or empty if the contract has none
	 */
	public Optional<TestDefinition> findTest(String id)
	{
		return categories.stream().flatMap(category -> category.tests().stream()).
			filter(test -> test.id().equals(id)).findFirst();
	}

	/**
	 * Returns the category/profile pairs whose trial counts and decisions are kept separately (design &sect;2.1).
	 *
	 * @return every cell, grouped by category in category order
	 */
	public List<Cell> cells()
	{
		return profiles.cells();
	}

	/**
	 * Returns the identifiers of the cells.
	 *
	 * @return the identifier of every cell, in cell order
	 */
	public List<String> cellIds()
	{
		return cells().stream().map(Cell::id).toList();
	}

	/**
	 * Finds a cell.
	 *
	 * @param id a cell identifier
	 * @return the cell with that identifier, or empty if the contract has none
	 * @throws NullPointerException if {@code id} is null
	 */
	public Optional<Cell> findCell(String id)
	{
		return profiles.findCell(id);
	}

	/**
	 * Returns the category of a cell.
	 *
	 * @param cell a cell of this contract
	 * @return the cell's category
	 * @throws NullPointerException if {@code cell} is null
	 * @throws IllegalArgumentException if the contract has no such category
	 */
	public Category categoryOf(Cell cell)
	{
		requireThat(cell, "cell").isNotNull();
		return findCategory(cell.categoryId()).orElseThrow(() -> new IllegalArgumentException(
			"The contract has no category '" + cell.categoryId() + "'"));
	}
}
