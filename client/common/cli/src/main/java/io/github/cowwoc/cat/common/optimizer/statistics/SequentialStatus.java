package io.github.cowwoc.cat.common.optimizer.statistics;

/**
 * The result of a repeated look at one formal category.
 */
public enum SequentialStatus
{
	/**
	 * The category needs another valid trial within its cap.
	 */
	CONTINUE,
	/**
	 * The lower bound exceeds the confirmed minimum.
	 */
	CERTIFIED,
	/**
	 * The upper bound is below the confirmed minimum.
	 */
	BELOW_MINIMUM,
	/**
	 * The category reached its cap without a boundary crossing.
	 */
	CAP_EXHAUSTED
}
