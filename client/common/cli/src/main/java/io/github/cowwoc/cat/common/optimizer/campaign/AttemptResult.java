package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.List;

/**
 * Original promotion evidence retained after winner selection and minimality.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param round the round's first reserved attempt number
 * @param working the working snapshot all attempts received
 * @param number the author's launch-order attempt number
 * @param cell the representative failing cell
 * @param snapshot the author's original snapshot, equal to working when no edit was made
 * @param runs the original promotion runs, including working reference runs
 * @param changedLines the number of added and removed lines
 * @param accepted whether the promotion rule accepted this edited attempt
 * @param winner whether this attempt was selected for minimality and promotion
 */
public record AttemptResult(int round, Sha256 working, int number, String cell, Sha256 snapshot,
	List<PromotionRun> runs, int changedLines, boolean accepted, boolean winner)
{
	/**
	 * Creates retained evidence.
	 *
	 * @param round the round identity
	 * @param working the starting snapshot
	 * @param number the attempt number
	 * @param cell the addressed cell
	 * @param snapshot the original attempt snapshot
	 * @param runs its promotion evidence
	 * @param changedLines its added and removed line count
	 * @param accepted whether the promotion rule accepted it
	 * @param winner whether it won
	 * @throws NullPointerException if a required value or element is null
	 * @throws IllegalArgumentException if a number is invalid or a rejected attempt is marked as winner
	 */
	public AttemptResult
	{
		requireThat(round, "round").isPositive();
		requireThat(working, "working").isNotNull();
		requireThat(number, "number").isGreaterThanOrEqualTo(round);
		requireThat(cell, "category").isNotBlank();
		requireThat(snapshot, "snapshot").isNotNull();
		runs = List.copyOf(runs);
		requireThat(changedLines, "changedLines").isNotNegative();
		if (winner)
			requireThat(accepted, "accepted").isTrue();
	}

	/**
	 * Returns the passing promotion runs.
	 *
	 * @return the attempt's own passes, excluding working reference runs
	 */
	public long passingRuns()
	{
		return AttemptSelection.passingRuns(runs);
	}

	/**
	 * Returns the planned promotion run count.
	 *
	 * @return the attempt's own run count, excluding working reference runs
	 */
	public long totalRuns()
	{
		return AttemptSelection.plannedRuns(runs);
	}
}
