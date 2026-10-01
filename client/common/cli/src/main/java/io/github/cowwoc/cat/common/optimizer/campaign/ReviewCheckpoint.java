package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;

import java.util.Optional;

/**
 * Decides when a category's review checkpoint is due, from {@code design.md} section 2.7.
 * <p>
 * A checkpoint is due when an unresolved category of an unchanged candidate reaches its review point: 200
 * representative trials, or the point that the user named when extending it.
 */
public final class ReviewCheckpoint
{
	/**
	 * Prevents instantiation.
	 */
	private ReviewCheckpoint()
	{
	}

	/**
	 * Indicates whether a category's review checkpoint is due.
	 *
	 * @param development the development state
	 * @param category the category
	 * @return {@code true} if the category reached its review point and is not ready
	 * @throws NullPointerException if any argument is null
	 */
	public static boolean isDue(Development development, String category)
	{
		requireThat(development, "development").isNotNull();
		requireThat(category, "category").isNotNull();
		CategoryCounts counts = development.count(category);
		return counts.outcomes() >= development.reviewPoint(category) && !ReadinessGate.isReady(counts);
	}

	/**
	 * Returns the first cell, in contract order, whose review checkpoint is due.
	 *
	 * @param development the development state
	 * @param contract the contract
	 * @return the cell, or empty if none is due
	 * @throws NullPointerException if any argument is null
	 */
	public static Optional<String> due(Development development, Contract contract)
	{
		requireThat(development, "development").isNotNull();
		requireThat(contract, "contract").isNotNull();
		for (String cell : contract.cellIds())
		{
			if (isDue(development, cell))
				return Optional.of(cell);
		}
		return Optional.empty();
	}
}
