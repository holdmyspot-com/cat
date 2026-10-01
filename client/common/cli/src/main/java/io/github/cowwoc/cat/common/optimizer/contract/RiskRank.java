package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Orders a category by how risky its failure is, so that allocation ties go to the riskier category.
 *
 * <p>
 * Position 1 is the riskiest. Two categories may share a position; allocation then falls back to category order.
 *
 * @param position the 1-based position, where a lower value is riskier
 */
public record RiskRank(int position) implements Comparable<RiskRank>
{
	/**
	 * Creates a risk rank.
	 *
	 * @param position the 1-based position, where a lower value is riskier
	 * @throws IllegalArgumentException if {@code position} is less than 1
	 */
	public RiskRank
	{
		requireThat(position, "position").isGreaterThanOrEqualTo(1);
	}

	@Override
	public int compareTo(RiskRank other)
	{
		return Integer.compare(position, other.position);
	}
}
