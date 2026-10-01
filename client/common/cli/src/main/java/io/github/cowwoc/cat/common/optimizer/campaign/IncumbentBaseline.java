package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Map;

/**
 * One measurement of the incumbent, with its own share of the incumbent budget.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param index the baseline's number within the campaign, from 1
 * @param counts the incumbent's representative outcomes per category
 */
public record IncumbentBaseline(int index, Map<String, CategoryCounts> counts)
{
	/**
	 * Creates a baseline.
	 *
	 * @param index the baseline's number within the campaign, from 1
	 * @param counts the incumbent's representative outcomes per category
	 * @throws NullPointerException if {@code counts} is null
	 * @throws IllegalArgumentException if {@code index} is not positive
	 */
	public IncumbentBaseline
	{
		requireThat(index, "index").isPositive();
		requireThat(counts, "counts").isNotNull();
		counts = Map.copyOf(counts);
	}

	/**
	 * Returns a category's outcomes.
	 *
	 * @param category the category
	 * @return the outcomes, empty if the category has none
	 * @throws NullPointerException if {@code category} is null
	 */
	public CategoryCounts count(String category)
	{
		requireThat(category, "category").isNotNull();
		return counts.getOrDefault(category, CategoryCounts.EMPTY);
	}
}
