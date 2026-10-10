package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.util.List;

/**
 * Determines how many representative trials of the incumbent each cell needs before certification can start.
 * <p>
 * A cell is a prompt test under one runner profile. Each cell of the certification scope needs
 * {@value #TARGET} trials. After that many outcomes, a cell whose observed pass rate is below the campaign's minimum
 * pass rate {@code p_min} and is not proven deficient needs {@value #EXTENDED_TARGET}. The target is evaluated on the
 * current counts.
 */
public final class Baseline
{
	/**
	 * Require at least this many trials in every cell before the baseline is met.
	 */
	public static final int TARGET = 50;
	/**
	 * Require this many trials in a cell whose observed pass rate is below the minimum pass rate and that is not proven
	 * deficient.
	 */
	public static final int EXTENDED_TARGET = 100;

	/**
	 * Prevents instantiation.
	 */
	private Baseline()
	{
	}

	/**
	 * Indicates whether the incumbent's pass rate in a cell is proven below the minimum pass rate: the below-minimum
	 * e-value exceeds {@code K/γ_b}, where {@code K} is the number of cells in the certification scope and {@code γ_b} is
	 * baseline {@code b}'s error allowance.
	 *
	 * @param counts the cell's baseline counts
	 * @param baselineIndex the baseline's index {@code b}
	 * @param cells the number of cells in the certification scope, {@code K}
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return {@code true} if the incumbent is proven deficient
	 * @throws NullPointerException if {@code counts} or {@code minimumPassRate} are null
	 * @throws IllegalArgumentException if {@code baselineIndex} or {@code cells} are not positive, or if
	 *   {@code minimumPassRate} is not strictly between 0 and 1
	 */
	public static boolean isProvenDeficient(CellCounts counts, int baselineIndex, int cells,
		BigDecimal minimumPassRate)
	{
		requireThat(counts, "counts").isNotNull();
		requireThat(baselineIndex, "baselineIndex").isPositive();
		requireThat(cells, "cells").isPositive();
		SignificanceLevel level = ErrorBudget.incumbentLevel(baselineIndex, cells);
		return BettingProcess.below(minimumPassRate, minimumPassRate).
			exceeds(counts.passes(), counts.failures(), level);
	}

	/**
	 * Returns the number of trials that a cell needs, given its current counts.
	 *
	 * @param counts the cell's baseline counts
	 * @param baselineIndex the baseline's index {@code b}
	 * @param cells the number of cells in the certification scope, {@code K}
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return {@value #TARGET} or {@value #EXTENDED_TARGET}
	 * @throws NullPointerException if {@code counts} or {@code minimumPassRate} are null
	 * @throws IllegalArgumentException if {@code baselineIndex} or {@code cells} are not positive, or if
	 *   {@code minimumPassRate} is not strictly between 0 and 1
	 */
	public static int target(CellCounts counts, int baselineIndex, int cells, BigDecimal minimumPassRate)
	{
		requireThat(counts, "counts").isNotNull();
		requireThat(baselineIndex, "baselineIndex").isPositive();
		requireThat(cells, "cells").isPositive();
		int outcomes = counts.outcomes();
		if (outcomes < TARGET)
			return TARGET;
		boolean meetsMinimum = BigDecimal.valueOf(counts.passes()).
			compareTo(minimumPassRate.multiply(BigDecimal.valueOf(outcomes))) >= 0;
		if (meetsMinimum || isProvenDeficient(counts, baselineIndex, cells, minimumPassRate))
			return TARGET;
		return EXTENDED_TARGET;
	}

	/**
	 * Indicates whether every cell of a contract's certification scope meets its target.
	 *
	 * @param baseline the baseline
	 * @param contract the confirmed contract
	 * @return {@code true} if the baseline is met
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the contract has no certification scope
	 */
	public static boolean isMet(IncumbentBaseline baseline, Contract contract)
	{
		requireThat(baseline, "baseline").isNotNull();
		requireThat(contract, "contract").isNotNull();
		List<String> cells = CellTier.scopeCellIds(contract);
		for (String cell : cells)
		{
			CellCounts counts = baseline.count(cell);
			if (counts.outcomes() < target(counts, baseline.index(), cells.size(), contract.minimumPassRate()))
				return false;
		}
		return true;
	}
}
