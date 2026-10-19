package com.holdmyspot.cat.common.optimizer.campaign;

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
	 * An infrastructure fault that neither a prompt edit nor a test revision can correct.
	 */
	NOT_GUIDANCE,
	/**
	 * The test fails to prove its claim, or does not follow the organic test rule, so that a test revision must
	 * correct it.
	 */
	TEST_DEFECT,
	/**
	 * Confirmed requirements cannot be satisfied together and need the user's resolution.
	 */
	CONFLICT
}
