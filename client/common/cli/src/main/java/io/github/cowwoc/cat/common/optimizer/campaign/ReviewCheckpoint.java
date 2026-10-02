package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;

import java.util.Optional;

/**
 * Decides when a cell needs user review before more development trials run. A cell is a requirement category under
 * one runner profile.
 * <p>
 * Review is due when a cell of an unchanged candidate is not ready and reaches its review point: 200 representative
 * trials, or the point that the user named when extending it.
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
	 * Indicates whether a cell's review checkpoint is due.
	 *
	 * @param development the development state
	 * @param cell the cell identifier
	 * @return {@code true} if the cell reached its review point and is not ready
	 * @throws NullPointerException if any argument is null
	 */
	public static boolean isDue(Development development, String cell)
	{
		requireThat(development, "development").isNotNull();
		requireThat(cell, "category").isNotNull();
		CellCounts counts = development.count(cell);
		return counts.outcomes() >= development.reviewPoint(cell) && !ReadinessGate.isReady(counts);
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
