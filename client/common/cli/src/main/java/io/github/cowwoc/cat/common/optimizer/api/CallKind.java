package io.github.cowwoc.cat.common.optimizer.api;

/**
 * Identifies whether a model call creates or evaluates a candidate.
 */
public enum CallKind
{
	/**
	 * Generates a candidate prompt change.
	 */
	CANDIDATE,
	/**
	 * Evaluates a frozen candidate.
	 */
	EVALUATOR
}
