package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Defines the allowed decision-stopping rule.
 */
public enum StoppingRule
{
	/**
 * Decide only after the fixed sample size has completed.
 */
	FIXED_N_NO_EARLY_DECISION,

	/**
	 * Decide after each valid trial, stopping by the frozen per-category cap.
	 */
	ANYTIME_FROZEN_CAP
}
