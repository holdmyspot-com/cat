package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * Why an assigned test instance runs, which decides the counts its outcome enters. Representative trials sample new
 * instances from the contract's distribution; targeted trials replay selected instances and do not enter those counts.
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
	PROMOTION;

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
			case DIAGNOSTIC, PROMOTION -> false;
		};
	}
}
