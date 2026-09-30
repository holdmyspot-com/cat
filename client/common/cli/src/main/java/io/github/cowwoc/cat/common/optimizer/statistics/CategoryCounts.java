package io.github.cowwoc.cat.common.optimizer.statistics;

/**
 * The completed outcomes observed for one material category.
 *
 * @param successes the number of compliant outcomes
 * @param trials the number of completed outcomes
 */
public record CategoryCounts(int successes, int trials)
{
	/**
	 * Validates category counts.
	 */
	public CategoryCounts
	{
		if (trials < 0)
			throw new IllegalArgumentException("trials must not be negative");
		if (successes < 0 || successes > trials)
			throw new IllegalArgumentException("successes must be between zero and trials");
	}
}
