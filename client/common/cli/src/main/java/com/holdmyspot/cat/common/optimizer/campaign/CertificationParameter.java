package com.holdmyspot.cat.common.optimizer.campaign;

/**
 * A parameter that defines the claim of a certification, together with the contract.
 */
public enum CertificationParameter
{
	/**
	 * The pass probability that each certified cell exceeds.
	 */
	MINIMUM_PASS_RATE,
	/**
	 * The maximum deficit from 1 at which control and incumbent comparisons are implied.
	 */
	CONTROL_MARGIN,
	/**
	 * The certification level {@code α_i}: the attempt's probability of certifying a cell whose pass probability does
	 * not exceed the minimum pass rate.
	 */
	CERTIFICATION_LEVEL
}
