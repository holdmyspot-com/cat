package io.github.cowwoc.cat.common.optimizer.api;

/**
 * Separates validation, development, and formal evidence purposes.
 */
public enum EvidencePurpose
{
	/**
	 * Contract validation before candidate development.
	 */
	VALIDATION,
	/**
	 * Selection-biased candidate development.
	 */
	DEVELOPMENT,
	/**
	 * Predeclared formal candidate measurement.
	 */
	FORMAL
}
