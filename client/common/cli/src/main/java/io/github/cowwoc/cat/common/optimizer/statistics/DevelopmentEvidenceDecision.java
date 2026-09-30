package io.github.cowwoc.cat.common.optimizer.statistics;

import io.github.cowwoc.cat.common.optimizer.contract.DevelopmentAllocationPolicy;
import io.github.cowwoc.cat.common.optimizer.contract.DevelopmentReadiness;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Classifies one category's representative development evidence under the confirmed readiness rule.
 */
public final class DevelopmentEvidenceDecision
{
	/**
	 * The next decision supported by one category's current-candidate evidence.
	 */
	public enum Status
	{
		WEAK,
		REGRESSION,
		READY,
		REVIEW_REQUIRED,
		UNCERTAIN
	}

	/**
	 * Representative outcomes for one candidate, category, and comparable measurement period.
	 *
	 * @param total all completed representative outcomes, including a partly completed batch
	 * @param completedBatches completed representative batches in execution order
	 * @param repeatableDefect whether a diagnosed requirement failure can be reproduced
	 * @param knownRegression whether a previously passing requirement is known to have regressed
	 */
	public record CategoryEvidence(CategoryCounts total, List<CategoryCounts> completedBatches,
		boolean repeatableDefect, boolean knownRegression)
	{
		/**
		 * Copies completed batches before they are used for a later readiness decision.
		 *
		 * @param total all completed representative outcomes, including a partly completed batch
		 * @param completedBatches completed representative batches in execution order
		 * @param repeatableDefect whether a diagnosed requirement failure can be reproduced
		 * @param knownRegression whether a previously passing requirement is known to have regressed
		 */
		public CategoryEvidence
		{
			Objects.requireNonNull(total, "total");
			completedBatches = List.copyOf(completedBatches);
		}
	}

	/**
	 * Prevents construction of the decision utility.
	 */
	private DevelopmentEvidenceDecision()
	{
	}

	/**
	 * Classifies representative evidence without consuming formal allowance or changing the candidate.
	 *
	 * @param categoryId confirmed category whose minimum count applies
	 * @param evidence validated current-candidate outcomes and latest completed batch order
	 * @param readiness confirmed development thresholds
	 * @param allocation confirmed representative batch size
	 * @return weak, regression, ready, review required, or uncertain
	 * @throws NullPointerException if a required input is null
	 * @throws IllegalArgumentException if the category or batch evidence conflicts with the confirmed policy
	 */
	public static Status classify(String categoryId, CategoryEvidence evidence,
		DevelopmentReadiness readiness, DevelopmentAllocationPolicy allocation)
	{
		Objects.requireNonNull(categoryId, "categoryId");
		Objects.requireNonNull(evidence, "evidence");
		Objects.requireNonNull(readiness, "readiness");
		Objects.requireNonNull(allocation, "allocation");
		Integer minimumEvidence = readiness.minimumEvidence().get(categoryId);
		if (categoryId.isBlank() || minimumEvidence == null)
			throw new IllegalArgumentException("Category is absent from the confirmed readiness rule: " + categoryId);
		validateBatches(evidence, allocation.batchSize());

		CategoryCounts total = evidence.total();
		if (isClearlyWeak(total, evidence.repeatableDefect(), readiness))
			return Status.WEAK;
		if (evidence.knownRegression())
			return Status.REGRESSION;
		if (total.trials() >= minimumEvidence && meetsRate(total, readiness.targetRate()) &&
			hasStableRecentBatches(evidence.completedBatches(), readiness))
			return Status.READY;
		if (total.trials() >= readiness.reviewCheckpoint())
			return Status.REVIEW_REQUIRED;
		return Status.UNCERTAIN;
	}

	/**
	 * Checks the confirmed early weakness rule using only counted outcomes and a known repeatable defect.
	 *
	 * @param total representative outcomes for one category
	 * @param repeatableDefect whether a confirmed requirement failure is reproducible
	 * @param readiness confirmed weakness threshold and interval confidence level
	 * @return true when the category needs diagnosis before more representative sampling
	 */
	public static boolean isClearlyWeak(CategoryCounts total, boolean repeatableDefect,
		DevelopmentReadiness readiness)
	{
		Objects.requireNonNull(total, "total");
		Objects.requireNonNull(readiness, "readiness");
		return repeatableDefect || total.trials() > 0 &&
			BigDecimal.valueOf(DevelopmentWilsonInterval.calculate(total,
				readiness.weakIntervalConfidenceLevel()).getUpperBound()).compareTo(
				readiness.weakUpperEndpointThreshold()) < 0;
	}

	/**
	 * Rejects completed batches whose counts contradict their representative totals.
	 *
	 * @param evidence representative totals and completed batches
	 * @param batchSize expected outcomes in a completed batch
	 */
	private static void validateBatches(CategoryEvidence evidence, int batchSize)
	{
		long completedSuccesses = 0;
		long completedTrials = 0;
		for (CategoryCounts batch : evidence.completedBatches())
		{
			if (batch.trials() != batchSize)
				throw new IllegalArgumentException("Completed development batch has the wrong trial count");
			completedSuccesses += batch.successes();
			completedTrials += batch.trials();
		}
		long unbatchedSuccesses = evidence.total().successes() - completedSuccesses;
		long unbatchedTrials = evidence.total().trials() - completedTrials;
		if (unbatchedSuccesses < 0 || unbatchedTrials < 0 || unbatchedSuccesses > unbatchedTrials)
			throw new IllegalArgumentException("Completed development batches contradict representative totals");
	}

	/**
	 * Indicates whether the latest required completed batches each meet the confirmed stability rate.
	 *
	 * @param batches completed batches in execution order
	 * @param readiness confirmed batch count and minimum rate
	 * @return true when every required latest batch meets the rate
	 */
	private static boolean hasStableRecentBatches(List<CategoryCounts> batches, DevelopmentReadiness readiness)
	{
		int required = readiness.recentBatchCount();
		if (batches.size() < required)
			return false;
		for (int index = batches.size() - required; index < batches.size(); ++index)
			if (!meetsRate(batches.get(index), readiness.recentBatchMinimumRate()))
				return false;
		return true;
	}

	/**
	 * Indicates whether an observed compliance fraction reaches a confirmed threshold without rounding.
	 *
	 * @param counts completed representative outcomes
	 * @param rate required observed compliance fraction
	 * @return true when compliant outcomes meet the threshold
	 */
	private static boolean meetsRate(CategoryCounts counts, BigDecimal rate)
	{
		BigDecimal requiredSuccesses = rate.multiply(BigDecimal.valueOf(counts.trials()));
		return BigDecimal.valueOf(counts.successes()).compareTo(requiredSuccesses) >= 0;
	}
}
