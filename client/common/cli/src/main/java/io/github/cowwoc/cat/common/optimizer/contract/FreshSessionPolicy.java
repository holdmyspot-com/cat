package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Defines the required formal-session isolation.
 */
public enum FreshSessionPolicy
{
	/**
 * Each logical trial starts a fresh session except confirmed multi-turn tests.
 */
	FRESH_PER_LOGICAL_TRIAL
}
