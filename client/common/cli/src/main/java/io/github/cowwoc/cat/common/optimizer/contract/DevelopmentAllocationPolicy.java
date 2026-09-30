package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * The confirmed rule for choosing representative development categories between higher-priority stop decisions.
 */
public enum DevelopmentAllocationPolicy
{
	/**
	 * Gives each eligible category one batch of 50, then alternates evenly cycled coverage batches with adaptive
	 * batches of 50. An adaptive batch chooses the unresolved category with the smallest exploratory 95% Wilson lower
	 * bound, breaking ties by fewer observations and then confirmed category order. Diagnosis, readiness, interruption,
	 * and measurement changes take precedence over another batch.
	 */
	BALANCED_COVERAGE_ADAPTIVE(50);

	private final int batchSize;

	/**
	 * Fixes the representative assignments planned for one category batch.
	 *
	 * @param batchSize representative assignments in one uninterrupted batch
	 */
	DevelopmentAllocationPolicy(int batchSize)
	{
		this.batchSize = batchSize;
	}

	/**
	 * Returns the representative assignments planned for one category batch.
	 *
	 * @return 50 assignments for the supported allocation policy
	 */
	public int batchSize()
	{
		return batchSize;
	}
}
