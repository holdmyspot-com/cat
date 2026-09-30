package io.github.cowwoc.cat.common.optimizer.state;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses each confirmed category's first representative batch in order.
 */
final class InitialCoveragePolicy
{
	/**
	 * Counts assignments and terminal semantic outcomes in one category's first batch.
	 *
	 * @param assigned assignments retained for the batch
	 * @param completed assignments with a counted semantic outcome
	 */
	record Progress(int assigned, int completed)
	{
		/**
		 * Validates that outcomes cannot outnumber assignments.
		 *
		 * @param assigned assignments retained for the batch
		 * @param completed assignments with a counted semantic outcome
		 */
		Progress
		{
			if (assigned < 0 || completed < 0 || completed > assigned)
				throw new IllegalArgumentException("Initial coverage counts are inconsistent");
		}
	}

	/**
	 * Identifies the current first-coverage batch.
	 *
	 * @param categoryId confirmed category
	 * @param batchNumber zero-based position in confirmed category order
	 */
	record Selection(String categoryId, int batchNumber)
	{
		/**
		 * Validates the selected category and batch number.
		 *
		 * @param categoryId confirmed category
		 * @param batchNumber zero-based position in confirmed category order
		 */
		Selection
		{
			if (categoryId == null || categoryId.isBlank() || batchNumber < 0)
				throw new IllegalArgumentException("Initial coverage selection is invalid");
		}
	}

	private static final Progress EMPTY = new Progress(0, 0);

	/** Prevents construction of the policy utility. */
	private InitialCoveragePolicy()
	{
	}

	/**
	 * Selects the first category without a complete first batch.
	 *
	 * @param categories unique confirmed category order
	 * @param progress counts for each category's first batch, omitting categories with no assignments
	 * @param batchSize assignments required per category
	 * @return the current first-coverage batch, or empty after all first batches finish
	 */
	static Optional<Selection> select(List<String> categories, Map<String, Progress> progress, int batchSize)
	{
		Objects.requireNonNull(categories, "categories");
		Objects.requireNonNull(progress, "progress");
		if (categories.isEmpty() || batchSize <= 0 || categories.stream().anyMatch(id -> id == null || id.isBlank()))
			throw new IllegalArgumentException("Initial coverage categories or batch size are invalid");
		Set<String> distinct = new HashSet<>(categories);
		if (distinct.size() != categories.size() || !distinct.containsAll(progress.keySet()) ||
			progress.values().stream().anyMatch(value -> value == null || value.assigned() > batchSize))
			throw new IllegalArgumentException("Initial coverage progress is invalid");
		for (int index = 0; index < categories.size(); ++index)
		{
			Progress current = progress.getOrDefault(categories.get(index), EMPTY);
			if (current.completed() == batchSize)
				continue;
			for (int later = index + 1; later < categories.size(); ++later)
				if (progress.getOrDefault(categories.get(later), EMPTY).assigned() != 0)
					throw new IllegalArgumentException("Initial coverage progressed past an incomplete batch");
			return Optional.of(new Selection(categories.get(index), index));
		}
		return Optional.empty();
	}
}
