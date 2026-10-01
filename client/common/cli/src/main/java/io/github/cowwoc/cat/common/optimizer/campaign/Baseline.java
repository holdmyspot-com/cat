package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.util.List;

/**
 * The targets of the incumbent's baseline arm, from {@code design.md} section 2.5.
 * <p>
 * Every category needs {@value #TARGET} representative trials. A category whose observed rate is below 95% and that
 * is not proven deficient needs {@value #EXTENDED_TARGET}. The rule is evaluated on the current counts.
 */
public final class Baseline
{
	/**
	 * The trials that every category needs.
	 */
	public static final int TARGET = 50;
	/**
	 * The trials that a category below 95% and not proven deficient needs.
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
	 * Indicates whether the incumbent is proven deficient in a category: the below-minimum e-value exceeds
	 * {@code K/γ_b}.
	 *
	 * @param counts the category's baseline counts
	 * @param baselineIndex the baseline's index {@code b}
	 * @param categories the contract's number of categories {@code K}
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
	 * Returns the number of trials that a category needs, given its current counts.
	 *
	 * @param counts the category's baseline counts
	 * @param baselineIndex the baseline's index {@code b}
	 * @param categories the contract's number of categories {@code K}
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
