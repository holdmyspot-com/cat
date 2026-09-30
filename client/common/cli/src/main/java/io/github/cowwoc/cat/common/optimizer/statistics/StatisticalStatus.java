package io.github.cowwoc.cat.common.optimizer.statistics;

/**
 * The constrained result of a fixed-sample statistical evaluation.
 */
public enum StatisticalStatus
{
	/** The fixed sample has not completed in every category. */
	INCOMPLETE,
	/** Every category's lower bound strictly exceeds the threshold. */
	THRESHOLD_MET,
	/** At least one completed category's lower bound does not exceed the threshold. */
	THRESHOLD_NOT_MET
}
