package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * A parameter that defines the claim of a certification, together with the contract.
 */
public enum CertificationParameter
{
	/**
	 * The pass probability that each certified cell exceeds.
	 */
	REQUIRED_PASS_RATE,
	/**
	 * The certification level {@code α_i}: the attempt's probability of certifying a cell whose pass probability does
	 * not exceed the required pass rate.
	 */
	CERTIFICATION_LEVEL
}
