package io.github.cowwoc.cat.common.optimization;

/** Immutable, fingerprinted input for one optimization operation. */
public interface OptimizationInput
{
	/**
	 * Returns the operation this input belongs to.
	 *
	 * @return the owning operation
	 */
	OperationKind operationKind();

	/**
	 * Returns the lowercase SHA-256 fingerprint of the complete canonical input.
	 *
	 * @return the input fingerprint
	 */
	String inputFingerprint();
}
