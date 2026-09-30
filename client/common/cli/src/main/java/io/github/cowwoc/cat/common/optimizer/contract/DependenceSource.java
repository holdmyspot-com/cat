package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Names a possible source of dependent formal outcomes.
 */
public enum DependenceSource
{
	/**
 * Shared harness session.
 */ SHARED_SESSION,
	/**
 * Provider-side cache.
 */ PROVIDER_CACHE,
	/**
 * Provider throttling.
 */ THROTTLING,
	/**
 * Evaluator memory.
 */ EVALUATOR_MEMORY,
	/**
 * Adaptive routing.
 */ ADAPTIVE_ROUTING,
	/**
 * A declared non-standard source.
 */ OTHER
}
