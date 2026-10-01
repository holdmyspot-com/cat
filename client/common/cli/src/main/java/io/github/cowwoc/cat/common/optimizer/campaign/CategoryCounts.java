package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;

/**
 * The adopted outcomes of one category for one snapshot and purpose.
 * <p>
 * The batches split the outcomes, in adoption order, into consecutive groups of {@value #BATCH_SIZE}. The last batch
 * may be incomplete.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param passes the number of adopted passes {@code S}
 * @param failures the number of adopted failures {@code F}
 * @param batchFailures the number of failures in each started batch, in adoption order
 */
public record CategoryCounts(int passes, int failures, List<Integer> batchFailures)
{
	/**
	 * The number of outcomes in a complete batch.
	 */
	public static final int BATCH_SIZE = 50;
	/**
	 * No outcomes.
	 */
	public static final CategoryCounts EMPTY = new CategoryCounts(0, 0, List.of());

	/**
	 * Creates counts.
	 *
	 * @param passes the number of adopted passes {@code S}
	 * @param failures the number of adopted failures {@code F}
	 * @param batchFailures the number of failures in each started batch, in adoption order
	 * @throws NullPointerException if {@code batchFailures} is null
	 * @throws IllegalArgumentException if a count is negative, the number of batches is not the number of started
	 *   batches, a batch has more failures than outcomes, or the batches do not sum to {@code failures}
	 */
	public CategoryCounts
	{
		requireThat(passes, "passes").isNotNegative();
		requireThat(failures, "failures").isNotNegative();
		requireThat(batchFailures, "batchFailures").isNotNull();
		int outcomes = passes + failures;
		int batches = (outcomes + BATCH_SIZE - 1) / BATCH_SIZE;
		requireThat(batchFailures, "batchFailures").size().isEqualTo(batches, "startedBatches");
		int sum = 0;
		for (int batch = 0; batch < batches; ++batch)
		{
			int size = Math.min(BATCH_SIZE, outcomes - batch * BATCH_SIZE);
			int batchFailure = batchFailures.get(batch);
			requireThat(batchFailure, "batchFailures[" + batch + "]").isBetween(0, true, size, true);
			sum += batchFailure;
		}
		requireThat(sum, "sum(batchFailures)").isEqualTo(failures, "failures");
		batchFailures = List.copyOf(batchFailures);
	}

	/**
	 * Returns the number of adopted outcomes.
	 *
	 * @return {@code S + F}
	 */
	public int outcomes()
	{
		return passes + failures;
	}

	/**
	 * Returns the failures of each complete batch.
	 *
	 * @return the failures, in adoption order
	 */
	public List<Integer> completedBatchFailures()
	{
		return batchFailures.subList(0, outcomes() / BATCH_SIZE);
	}

	/**
	 * Returns these counts with one more outcome adopted.
	 *
	 * @param verdict the adopted outcome
	 * @return the updated counts
	 * @throws NullPointerException if {@code verdict} is null
	 */
	public CategoryCounts add(Verdict verdict)
	{
		requireThat(verdict, "verdict").isNotNull();
		List<Integer> batches = new ArrayList<>(batchFailures);
		if (outcomes() % BATCH_SIZE == 0)
			batches.add(0);
		if (verdict == Verdict.PASS)
			return new CategoryCounts(passes + 1, failures, batches);
		batches.set(batches.size() - 1, batches.getLast() + 1);
		return new CategoryCounts(passes, failures + 1, batches);
	}
}
