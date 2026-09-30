package io.github.cowwoc.cat.common.optimizer.loading;

/**
 * Identifies how production loading included a source.
 */
public enum LoadMode
{
	/**
 * The source is loaded without a condition.
 */
	DIRECT,
	/**
 * The source is loaded by a declared condition.
 */
	CONDITIONAL,
	/**
 * The source is loaded on demand.
 */
	LAZY
}
