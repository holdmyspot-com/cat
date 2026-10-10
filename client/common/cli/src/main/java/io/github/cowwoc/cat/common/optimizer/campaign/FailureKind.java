package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * The prompt author's classification of a kind of failure: the boundary that caused it.
 */
public enum FailureKind
{
	/**
	 * Existing guidance did not reach or trigger the decision.
	 */
	ROUTING,
	/**
	 * Guidance is missing, ambiguous or contradictory.
	 */
	GUIDANCE,
	/**
	 * A test or contract defect, or an infrastructure fault, that no prompt edit can correct.
	 */
	NOT_GUIDANCE,
	/**
	 * Confirmed requirements cannot be satisfied together and need the user's resolution.
	 */
	CONFLICT
}
