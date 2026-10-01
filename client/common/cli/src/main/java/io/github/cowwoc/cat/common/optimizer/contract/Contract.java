package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The confirmed definition of compliance that every trial measures.
 *
 * @param requirements every requirement and its source
 * @param categories the categories, in the order that breaks allocation ties last
 * @param judge the frozen judge profile
 * @param fixtures the ignored files that trials need
 * @param limitations the material interactions and conditions that the contract does not cover
 */
public record Contract(List<Requirement> requirements, List<Category> categories, JudgeProfile judge,
	TrialFixtures fixtures, List<String> limitations)
{
	/**
	 * Creates a contract.
	 *
	 * @param requirements every requirement and its source
	 * @param categories the categories, in the order that breaks allocation ties last
	 * @param judge the frozen judge profile
	 * @param fixtures the ignored files that trials need
	 * @param limitations the material interactions and conditions that the contract does not cover
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code requirements} or {@code categories} are empty, two requirements or two
	 *   categories share an identifier, a test shares an identifier with a test of another category, or a test names a
	 *   requirement that the contract does not list
	 */
	public Contract
	{
		requireThat(requirements, "requirements").isNotEmpty();
		requireThat(categories, "categories").isNotEmpty();
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
}
