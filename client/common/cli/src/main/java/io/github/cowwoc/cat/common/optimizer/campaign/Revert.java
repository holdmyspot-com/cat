package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The comparison that withdrew a promoted candidate and its retained development evidence.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param withdrawn the candidate that was withdrawn
 * @param cell the addressed cell that triggered withdrawal
 * @param upperBound the withdrawn candidate's development 95% Wilson upper bound in the cell
 * @param predecessorLowerBound the predecessor's development 95% Wilson lower bound in the cell
 * @param development the withdrawn candidate's retained counts, failures and test case outcomes
 */
public record Revert(Candidate withdrawn, String cell, double upperBound, double predecessorLowerBound,
	Development development)
{
	/**
	 * Creates a withdrawal record.
	 *
	 * @param withdrawn the candidate that was withdrawn
	 * @param cell the addressed cell that triggered withdrawal
	 * @param upperBound the development 95% Wilson upper bound
	 * @param predecessorLowerBound the predecessor's development 95% Wilson lower bound
	 * @param development the withdrawn candidate's retained development evidence without a predecessor or history
	 * @throws NullPointerException if {@code withdrawn}, {@code cell} or {@code development} are null
	 * @throws IllegalArgumentException if {@code cell} is blank, either probability is outside {@code [0, 1]}, the
	 *   upper bound is not below the predecessor's lower bound, or the evidence belongs to a different candidate or
	 *   contains a predecessor or revert history
	 */
	public Revert
	{
		requireThat(withdrawn, "withdrawn").isNotNull();
		requireThat(cell, "cell").isNotBlank();
		requireThat(upperBound, "upperBound").isBetween(0, true, 1, true);
		requireThat(predecessorLowerBound, "predecessorLowerBound").isBetween(0, true, 1, true);
		requireThat(upperBound, "upperBound").isLessThan(predecessorLowerBound);
		requireThat(development, "development").isNotNull();
		requireThat(development.working(), "development.working()").isEqualTo(withdrawn);
		requireThat(development.predecessor(), "development.predecessor()").isNull();
		requireThat(development.reverts(), "development.reverts()").isEmpty();
	}
}
