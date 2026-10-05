package io.github.cowwoc.cat.common.optimizer.campaign;

import io.github.cowwoc.cat.common.optimizer.contract.Part;

/**
 * Why an assigned test instance runs, which decides the counts its outcome enters. Representative trials sample new
 * instances from the contract's distribution; targeted trials replay selected instances, or reproduce a formal failure
 * group on its round's combinations, and do not enter those counts.
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
	 * A targeted rerun of a failing development instance, which is never counted.
	 */
	DIAGNOSTIC,
	/**
	 * A targeted run of an author attempt or of the working candidate on a regression instance, which is never
	 * counted.
	 */
	PROMOTION,
	/**
	 * A trial of the working candidate on the key-value combinations of a formal failure group's current round, drawn
	 * from the development part, which counts only toward the group's reproduction.
	 */
	REPRODUCTION;

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
			case DIAGNOSTIC, PROMOTION, REPRODUCTION -> false;
		};
	}

	/**
	 * Indicates whether assigning a trial with this purpose draws its instance, rather than replaying an instance that
	 * the admission names. Their outcomes enter campaign evidence in assignment order within their adoption group, so
	 * that completion order cannot choose which outcomes enter a decision.
	 *
	 * @return {@code true} for the {@link #isRepresentative() representative} purposes and {@link #REPRODUCTION}
	 */
	public boolean drawsAtAssignment()
	{
		return switch (this)
		{
			case DEVELOPMENT, BASELINE, FORMAL, REPRODUCTION -> true;
			case DIAGNOSTIC, PROMOTION -> false;
		};
	}

	/**
	 * Returns the part of the contract whose instances trials with this purpose run.
	 *
	 * @return {@link Part#HELD_OUT} for {@link #BASELINE} and {@link #FORMAL}, and {@link Part#DEVELOPMENT} for
	 *   every other purpose
	 */
	public Part part()
	{
		return switch (this)
		{
			case BASELINE, FORMAL -> Part.HELD_OUT;
			case DEVELOPMENT, DIAGNOSTIC, PROMOTION, REPRODUCTION -> Part.DEVELOPMENT;
		};
	}
}
