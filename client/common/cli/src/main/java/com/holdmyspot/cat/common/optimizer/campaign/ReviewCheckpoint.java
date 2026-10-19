package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Contract;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Decides when a cell needs user review before more development trials run. A cell is a prompt test under
 * one runner profile.
 * <p>
 * Review is due when a cell of an unchanged candidate does not meet the commit gate and reaches its review point: 200
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
	 * Indicates whether a cell's review checkpoint is due.
	 *
	 * @param development the development state
	 * @param cell the cell identifier
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return {@code true} if the cell reached its review point and does not meet the commit gate
	 * @throws NullPointerException if any argument is null
	 */
	public static boolean isDue(Development development, String cell, BigDecimal minimumPassRate)
	{
		requireThat(development, "development").isNotNull();
		requireThat(cell, "cell").isNotNull();
		CellCounts counts = development.count(cell);
		return counts.outcomes() >= development.reviewPoint(cell) && !CommitGate.isMetInCell(counts, minimumPassRate);
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
			if (isDue(development, cell, contract.minimumPassRate()))
				return Optional.of(cell);
		}
		return Optional.empty();
	}
}
