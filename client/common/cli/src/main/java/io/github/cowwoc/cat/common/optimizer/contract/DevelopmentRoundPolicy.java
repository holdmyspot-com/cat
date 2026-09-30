package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Confirmed limits for one development round's work in each failing category.
 *
 * @param diagnosticVariationLimitPerCategory maximum targeted variations before a candidate edit in one category
 * @param generationAttemptLimitPerCategory maximum isolated candidate-generation attempts in one category
 */
public record DevelopmentRoundPolicy(int diagnosticVariationLimitPerCategory,
	int generationAttemptLimitPerCategory)
{
	/**
	 * Requires positive finite limits for diagnosis and candidate generation.
	 *
	 * @param diagnosticVariationLimitPerCategory maximum targeted variations before a candidate edit in one category
	 * @param generationAttemptLimitPerCategory maximum isolated candidate-generation attempts in one category
	 * @throws IllegalArgumentException if either limit is not positive
	 */
	public DevelopmentRoundPolicy
	{
		if (diagnosticVariationLimitPerCategory < 1 || generationAttemptLimitPerCategory < 1)
			throw new IllegalArgumentException("Development round limits must be positive");
	}
}
