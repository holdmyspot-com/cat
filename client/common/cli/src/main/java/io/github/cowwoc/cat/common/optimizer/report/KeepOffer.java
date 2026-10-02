package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.Interval;

import java.util.List;

/**
 * One {@code keep improvement} choice, with the evidence that qualifies it: the best qualifying certification attempt
 * of one measurement identity.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param attempt the certification index of the attempt whose frozen snapshot the choice delivers
 * @param historical {@code true} if the attempt was measured under an earlier measurement identity
 * @param differing the names of the conditions that differ from the current identity, empty if it is current
 * @param weakestLowerBound the 95% Wilson lower bound on the attempt's weakest cell, which ranks it within its
 *   identity
 * @param cells the evidence of each cell
 */
public record KeepOffer(int attempt, boolean historical, List<String> differing, double weakestLowerBound,
	List<CellEvidence> cells)
{
	/**
	 * The evidence that one cell of an offered attempt gives.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param cell the cell's identifier
	 * @param passes the attempt's adopted passes
	 * @param trials the attempt's adopted outcomes
	 * @param state {@code proven better}, {@code proven worse} or {@code undecided} at {@code β_i / K}
	 * @param incumbentUpper the incumbent's frozen upper bound {@code UI(k)}
	 * @param incumbentLower the incumbent's frozen lower bound {@code LI(k)}
	 * @param difference the difference interval between the candidate's and the incumbent's pass probabilities
	 * @param note a warning that a cell that is not proven better might still be slightly worse, or
	 *   {@code null} if none applies
	 */
	public record CellEvidence(String cell, int passes, int trials, String state, double incumbentUpper,
		double incumbentLower, Interval difference, String note)
	{
		/**
		 * Creates the evidence of a cell.
		 *
		 * @param cell the cell's identifier
		 * @param passes the attempt's adopted passes
		 * @param trials the attempt's adopted outcomes
		 * @param state {@code proven better}, {@code proven worse} or {@code undecided}
		 * @param incumbentUpper the incumbent's frozen upper bound {@code UI(k)}
		 * @param incumbentLower the incumbent's frozen lower bound {@code LI(k)}
		 * @param difference the difference interval
		 * @param note a warning that the cell might still be slightly worse, or {@code null} if none applies
		 * @throws NullPointerException if {@code cell}, {@code state} or {@code difference} are null
		 * @throws IllegalArgumentException if {@code trials} is negative or {@code passes} is outside
		 *   [0, {@code trials}]
		 */
		public CellEvidence
		{
			requireThat(cell, "cell").isNotNull();
			requireThat(trials, "trials").isNotNegative();
			requireThat(passes, "passes").isBetween(0, true, trials, true);
			requireThat(state, "state").isNotNull();
			requireThat(difference, "difference").isNotNull();
		}
	}

	/**
	 * Creates an offer.
	 *
	 * @param attempt the certification index of the attempt whose frozen snapshot the choice delivers
	 * @param historical {@code true} if the attempt was measured under an earlier measurement identity
	 * @param differing the names of the conditions that differ from the current identity
	 * @param weakestLowerBound the 95% Wilson lower bound on the attempt's weakest cell
	 * @param cells the evidence of each cell
	 * @throws NullPointerException if {@code differing} or {@code cells} are null
	 * @throws IllegalArgumentException if {@code attempt} is not positive
	 */
	public KeepOffer
	{
		requireThat(attempt, "attempt").isPositive();
		requireThat(differing, "differing").isNotNull();
		requireThat(cells, "cells").isNotNull();
		differing = List.copyOf(differing);
		cells = List.copyOf(cells);
	}
}
