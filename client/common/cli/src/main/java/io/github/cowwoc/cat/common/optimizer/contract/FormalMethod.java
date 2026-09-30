package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Defines the formal statistical method.
 */
public enum FormalMethod
{
	/**
 * A fixed-size exact binomial procedure.
 */
	FIXED_SAMPLE_EXACT,

	/**
	 * A Bernoulli confidence sequence evaluated after each valid trial.
	 */
	ANYTIME_BERNOULLI
}
