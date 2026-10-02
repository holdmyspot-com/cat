package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.util.List;

/**
 * Determines how many representative trials of the incumbent each cell needs before certification can start.
 * <p>
 * A cell is a requirement category under one runner profile. Each needs {@value #TARGET} trials. After that many
 * outcomes, a cell whose observed pass rate is below 95% and is not proven deficient needs
 * {@value #EXTENDED_TARGET}. The target is evaluated on the current counts.
 */
public final class Baseline
{
	/**
	 * Require at least this many trials in every cell before the baseline is met.
	 */
	public static final int TARGET = 50;
	/**
	 * Require this many trials in a cell below 95% observed passes that is not proven deficient.
	 */
	public static final int EXTENDED_TARGET = 100;
	/**
	 * The test that the incumbent's pass rate is below the required rate.
	 */
	private static final BettingProcess DEFICIENCY = new BettingProcess(BettingProcess.Side.BELOW,
		BettingProcess.REQUIRED_PASS_RATE);

	/**
	 * Prevents instantiation.
	 */
	private Baseline()
	{
	}

	/**
	 * Indicates whether the incumbent's pass rate in a cell is proven below 95%: the below-minimum e-value exceeds
	 * {@code K/γ_b}, where {@code K} is the number of cells and {@code γ_b} is baseline {@code b}'s error allowance.
	 *
	 * @param counts the cell's baseline counts
	 * @param baselineIndex the baseline's index {@code b}
	 * @param categories the contract's number of cells {@code K}
	 * @return {@code true} if the incumbent is proven deficient
	 * @throws NullPointerException if {@code counts} is null
	 * @throws IllegalArgumentException if {@code baselineIndex} or {@code categories} are not positive
	 */
	public static boolean isProvenDeficient(CategoryCounts counts, int baselineIndex, int categories)
	{
		requireThat(counts, "counts").isNotNull();
		requireThat(baselineIndex, "baselineIndex").isPositive();
		requireThat(categories, "categories").isPositive();
		SignificanceLevel level = ErrorBudget.incumbentLevel(baselineIndex, categories);
		return DEFICIENCY.exceeds(counts.passes(), counts.failures(), level);
	}

	/**
	 * Returns the number of trials that a cell needs, given its current counts.
	 *
	 * @param counts the cell's baseline counts
	 * @param baselineIndex the baseline's index {@code b}
	 * @param categories the contract's number of cells {@code K}
	 * @return {@value #TARGET} or {@value #EXTENDED_TARGET}
	 * @throws NullPointerException if {@code counts} is null
	 * @throws IllegalArgumentException if {@code baselineIndex} or {@code categories} are not positive
	 */
	public static int target(CategoryCounts counts, int baselineIndex, int categories)
	{
		requireThat(counts, "counts").isNotNull();
		requireThat(baselineIndex, "baselineIndex").isPositive();
		requireThat(categories, "categories").isPositive();
		int outcomes = counts.outcomes();
		if (outcomes < TARGET)
			return TARGET;
		if (20L * counts.passes() >= 19L * outcomes || isProvenDeficient(counts, baselineIndex, categories))
			return TARGET;
		return EXTENDED_TARGET;
	}

	/**
	 * Indicates whether every cell of a contract meets its target.
	 *
	 * @param baseline the baseline
	 * @param contract the contract
	 * @return {@code true} if the baseline is met
	 * @throws NullPointerException if any argument is null
	 */
	public static boolean isMet(IncumbentBaseline baseline, Contract contract)
	{
		requireThat(baseline, "baseline").isNotNull();
		requireThat(contract, "contract").isNotNull();
		List<String> cells = contract.cellIds();
		for (String cell : cells)
		{
			CategoryCounts counts = baseline.count(cell);
			if (counts.outcomes() < target(counts, baseline.index(), cells.size()))
				return false;
		}
		return true;
	}
}
