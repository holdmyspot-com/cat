package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Map;

/**
 * One measurement of the original prompt system, called the incumbent, with its own statistical error allowance.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param index the baseline's number within the campaign, from 1
 * @param counts the incumbent's sampled outcomes included in evidence per cell (category and trial profile)
 */
public record IncumbentBaseline(int index, Map<String, CategoryCounts> counts)
{
	/**
	 * Creates a baseline.
	 *
	 * @param index the baseline's number within the campaign, from 1
	 * @param counts the incumbent's sampled outcomes included in evidence per cell (category and trial profile)
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
	 * Returns a cell's outcomes included in incumbent evidence.
	 *
	 * @param category the cell's identifier
	 * @return the counts, empty if the cell has no outcomes
	 * @throws NullPointerException if {@code category} is null
	 */
	public CategoryCounts count(String category)
	{
		requireThat(category, "category").isNotNull();
		return counts.getOrDefault(category, CategoryCounts.EMPTY);
	}
}
