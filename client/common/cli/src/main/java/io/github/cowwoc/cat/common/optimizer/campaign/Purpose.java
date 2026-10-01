package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * Why a logical trial runs, which decides the counts its outcome enters.
 */
public enum Purpose
{
	/**
	 * A representative trial of the working candidate, counted in its development counts.
	 */
	DEVELOPMENT,
	/**
	 * A representative trial of the incumbent's baseline arm, counted in the baseline.
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
