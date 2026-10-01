package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A group of tests that needs its own claim, because averaging it with other categories could hide a weak area.
 *
 * @param id the identifier that is unique within the contract
 * @param rationale why this group needs a separate claim
 * @param riskRank the rank that breaks allocation ties
 * @param tests the tests in this category, which the benchmark weights equally
 */
public record Category(String id, String rationale, RiskRank riskRank, List<TestDefinition> tests)
{
	/**
	 * Creates a category.
	 *
	 * @param id the identifier that is unique within the contract
	 * @param rationale why this group needs a separate claim
	 * @param riskRank the rank that breaks allocation ties
	 * @param tests the tests in this category, which the benchmark weights equally
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if a text is blank, {@code tests} is empty, or two tests share an identifier
	 */
	public Category
	{
		requireThat(id, "id").isNotBlank();
		requireThat(rationale, "rationale").isNotBlank();
		requireThat(riskRank, "riskRank").isNotNull();
		requireThat(tests, "tests").isNotEmpty();
		tests = List.copyOf(tests);

		Set<String> ids = new HashSet<>();
		for (TestDefinition test : tests)
		{
			if (!ids.add(test.id()))
				throw new IllegalArgumentException("Category '" + id + "' lists the test '" + test.id() + "' twice");
		}
	}

	/**
	 * Returns the probability that the benchmark draws each test of this category.
	 *
	 * @return one divided by the number of tests
	 */
	public double testProbability()
	{
		return 1.0 / tests.size();
	}
}
