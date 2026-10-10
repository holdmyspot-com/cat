package io.github.cowwoc.cat.common.optimizer.campaign;

import io.github.cowwoc.cat.common.optimizer.contract.Part;

/**
 * Why an assigned trial runs, which decides the counts its outcome enters. Representative trials sample new test
 * cases from the contract's distribution; targeted trials replay selected test cases, or reproduce a formal failure
 * group on its round's test cases, and do not enter those counts. Control trials replay a formal trial's test case
 * without the target prompt and enter only the control comparisons.
 */
public enum Purpose
{
	/**
	 * A representative trial of the working candidate, counted in its development counts.
	 */
	DEVELOPMENT,
	/**
	 * A sampled trial of the original prompt system, counted in its baseline measurement.
	 */
	BASELINE,
	/**
	 * A trial of a certification attempt, counted in the attempt's formal counts.
	 */
	FORMAL,
	/**
	 * A targeted rerun of a failing development test case, which is never counted.
	 */
	DIAGNOSTIC,
	/**
	 * A targeted run of a prompt author attempt or of the working candidate on a regression test case, which is never
	 * counted.
	 */
	PROMOTION,
	/**
	 * A trial of the working candidate on the test cases of a formal failure group's current round,
	 * drawn from the development part, which counts only toward the group's reproduction.
	 */
	REPRODUCTION,
	/**
	 * A trial of the incumbent with every target prompt file emptied, which runs the test case of the candidate formal
	 * trial that it pairs with and counts only toward its cell's control comparisons.
	 */
	CONTROL;

	/**
	 * Indicates whether trials with this purpose draw from a representative stream.
	 *
	 * @return {@code true} for {@link #DEVELOPMENT}, {@link #BASELINE} and {@link #FORMAL}
	 */
	public boolean isRepresentative()
	{
		return switch (this)
		{
			case DEVELOPMENT, BASELINE, FORMAL -> true;
			case DIAGNOSTIC, PROMOTION, REPRODUCTION, CONTROL -> false;
		};
	}

	/**
	 * Indicates whether assigning a trial with this purpose draws its test case, rather than replaying a test case that
	 * the admission names.
	 *
	 * @return {@code true} for the {@link #isRepresentative() representative} purposes and {@link #REPRODUCTION}
	 */
	public boolean drawsAtAssignment()
	{
		return switch (this)
		{
			case DEVELOPMENT, BASELINE, FORMAL, REPRODUCTION -> true;
			case DIAGNOSTIC, PROMOTION, CONTROL -> false;
		};
	}

	/**
	 * Indicates whether outcomes of trials with this purpose enter campaign evidence in assignment order within their
	 * adoption group, so that completion order cannot choose which outcomes enter a decision.
	 *
	 * @return {@code true} for the purposes that {@link #drawsAtAssignment() draw at assignment} and for
	 *   {@link #CONTROL}, whose {@code j}-th trial pairs with the {@code j}-th candidate formal trial
	 */
	public boolean adoptsInOrder()
	{
		return switch (this)
		{
			case DEVELOPMENT, BASELINE, FORMAL, REPRODUCTION, CONTROL -> true;
			case DIAGNOSTIC, PROMOTION -> false;
		};
	}

	/**
	 * Returns the part of the contract whose test cases trials with this purpose run.
	 *
	 * @return {@link Part#HELD_OUT} for {@link #BASELINE}, {@link #FORMAL} and {@link #CONTROL}, and
	 *   {@link Part#DEVELOPMENT} for every other purpose
	 */
	public Part part()
	{
		return switch (this)
		{
			case BASELINE, FORMAL, CONTROL -> Part.HELD_OUT;
			case DEVELOPMENT, DIAGNOSTIC, PROMOTION, REPRODUCTION -> Part.DEVELOPMENT;
		};
	}
}
