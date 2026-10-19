package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Map;

/**
 * One measurement of the original prompt system, called the incumbent, with its own statistical error allowance.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param index the baseline's number within the campaign, from 1
 * @param identity the conditions under which the incumbent outcomes were measured
 * @param counts the incumbent's sampled outcomes included in evidence per cell (prompt test and trial profile)
 */
public record IncumbentBaseline(int index, MeasurementIdentity identity, Map<String, CellCounts> counts)
{
	/**
	 * Creates a baseline.
	 *
	 * @param index the baseline's number within the campaign, from 1
	 * @param identity the conditions under which the incumbent outcomes were measured
	 * @param counts the incumbent's sampled outcomes included in evidence per cell (prompt test and trial profile)
	 * @throws NullPointerException if {@code identity} or {@code counts} are null
	 * @throws IllegalArgumentException if {@code index} is not positive
	 */
	public IncumbentBaseline
	{
		requireThat(index, "index").isPositive();
		requireThat(identity, "identity").isNotNull();
		requireThat(counts, "counts").isNotNull();
		counts = Map.copyOf(counts);
	}

	/**
	 * Returns a cell's outcomes included in incumbent evidence.
	 *
	 * @param cell the cell's identifier
	 * @return the counts, empty if the cell has no outcomes
	 * @throws NullPointerException if {@code cell} is null
	 */
	public CellCounts count(String cell)
	{
		requireThat(cell, "cell").isNotNull();
		return counts.getOrDefault(cell, CellCounts.EMPTY);
	}
}
