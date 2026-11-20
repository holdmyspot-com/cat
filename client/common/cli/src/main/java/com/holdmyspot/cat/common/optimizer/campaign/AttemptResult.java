package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.util.List;

/**
 * Original promotion evidence retained after winner selection and minimality.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param round the round's first reserved attempt number
 * @param working the working snapshot all attempts received
 * @param number the prompt author's launch-order attempt number
 * @param cell the representative failing cell
 * @param snapshot the prompt author's original snapshot, equal to working when no edit was made
 * @param replays the original promotion replays, including the working candidate's reference replays and paired
 *   replays; a rejected attempt's replays that never started have no verdict
 * @param changedLines the number of added and removed lines
 * @param accepted indicates whether the promotion rule accepted this edited attempt
 * @param winner indicates whether this attempt was selected for minimality and promotion
 */
public record AttemptResult(int round, Sha256 working, int number, String cell, Sha256 snapshot,
	List<PromotionReplay> replays, int changedLines, boolean accepted, boolean winner)
{
	/**
	 * Creates retained evidence.
	 *
	 * @param round the round identity
	 * @param working the starting snapshot
	 * @param number the attempt number
	 * @param cell the addressed cell
	 * @param snapshot the original attempt snapshot
	 * @param replays its promotion evidence
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
		requireThat(cell, "cell").isNotBlank();
		requireThat(snapshot, "snapshot").isNotNull();
		replays = List.copyOf(replays);
		requireThat(changedLines, "changedLines").isNotNegative();
		if (winner)
			requireThat(accepted, "accepted").isTrue();
	}

	/**
	 * Returns the passing promotion replays.
	 *
	 * @return the attempt's own passes, including its paired replays and excluding the working candidate's replays
	 */
	public long passingReplays()
	{
		return AttemptSelection.passingReplays(replays);
	}

	/**
	 * Returns the promotion replay count.
	 *
	 * @return the attempt's own replays that have an outcome, excluding the working candidate's replays
	 */
	public long totalReplays()
	{
		return AttemptSelection.judgedReplays(replays);
	}

	/**
	 * Indicates whether the attempt was rejected before all of its planned replays ran.
	 *
	 * @return {@code true} if the attempt is rejected and one of its own planned replays has no outcome
	 */
	public boolean rejectedEarly()
	{
		return !accepted && AttemptSelection.judgedReplays(replays) < AttemptSelection.plannedReplays(replays);
	}
}
