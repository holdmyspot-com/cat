package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The confirmed development-evidence readiness rule.
 *
 * @param targetRate required observed compliance rate
 * @param minimumEvidence minimum evidence by category
 * @param recentBatchMinimumRate required compliance rate in each of the latest completed batches
 * @param recentBatchCount number of latest completed batches required for readiness
 * @param weakUpperEndpointThreshold a category is weak when its exploratory upper endpoint is below this rate
 * @param weakIntervalConfidenceLevel confidence level of the exploratory Wilson interval used for weakness
 * @param reviewCheckpoint representative outcome count that prompts review while a category remains unresolved
 * @param comparisonPolicy candidate comparison policy
 */
public record DevelopmentReadiness(BigDecimal targetRate, Map<String, Integer> minimumEvidence,
	BigDecimal recentBatchMinimumRate, int recentBatchCount, BigDecimal weakUpperEndpointThreshold,
	BigDecimal weakIntervalConfidenceLevel, int reviewCheckpoint, ComparisonPolicy comparisonPolicy)
{
	/**
 * Validates and defensively copies the readiness rule.
 *
 * @param targetRate required observed compliance rate
 * @param minimumEvidence minimum evidence by category
 * @param recentBatchMinimumRate required compliance rate in each of the latest completed batches
 * @param recentBatchCount number of latest completed batches required for readiness
 * @param weakUpperEndpointThreshold a category is weak when its exploratory upper endpoint is below this rate
 * @param weakIntervalConfidenceLevel confidence level of the exploratory Wilson interval used for weakness
 * @param reviewCheckpoint representative outcome count that prompts review while a category remains unresolved
 * @param comparisonPolicy candidate comparison policy
 */
	public DevelopmentReadiness
	{
		requireThat(targetRate, "targetRate").isGreaterThan(BigDecimal.ZERO).
			isLessThanOrEqualTo(BigDecimal.ONE);
		requireThat(recentBatchMinimumRate, "recentBatchMinimumRate").isGreaterThan(BigDecimal.ZERO).
			isLessThanOrEqualTo(BigDecimal.ONE);
		requireThat(recentBatchCount, "recentBatchCount").isGreaterThan(0);
		requireThat(weakUpperEndpointThreshold, "weakUpperEndpointThreshold").isGreaterThan(BigDecimal.ZERO).
			isLessThanOrEqualTo(targetRate);
		requireThat(weakIntervalConfidenceLevel, "weakIntervalConfidenceLevel").isGreaterThan(BigDecimal.ZERO).
			isLessThan(BigDecimal.ONE);
		requireThat(reviewCheckpoint, "reviewCheckpoint").isGreaterThan(0);
		requireThat(comparisonPolicy, "comparisonPolicy").isNotNull();
		minimumEvidence = Map.copyOf(minimumEvidence);
	}
}
