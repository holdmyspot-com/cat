package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.statistics.DevelopmentWilsonInterval;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses representative development batches after every category has its first batch.
 */
final class FollowupDevelopmentPolicy
{
	/** The type of the next batch. */
	enum Turn
	{
		COVERAGE,
		ADAPTIVE
	}

	/**
	 * One chosen batch and the position to retain for the choice after it completes.
	 *
	 * @param categoryId selected category
	 * @param turn selected coverage or adaptive turn
	 * @param nextCoverageCursor next confirmed category position to consider for coverage
	 * @param nextTurn type of the following batch
	 */
	record Selection(String categoryId, Turn turn, int nextCoverageCursor, Turn nextTurn)
	{
	}

	/** Prevents construction of the policy utility. */
	private FollowupDevelopmentPolicy()
	{
	}

	/**
	 * Chooses a batch from current-candidate representative outcomes and caller-admitted categories.
	 *
	 * @param categories confirmed category order
	 * @param eligibleCategories categories without a scheduling stop
	 * @param unresolvedCategories eligible categories needing further representative evidence
	 * @param counts all current-candidate representative outcomes by category
	 * @param batchSize completed first-batch outcomes required per category
	 * @param coverageCursor next confirmed category position to consider for coverage
	 * @param turn type of the next batch
	 * @return a choice, or empty when no unresolved eligible category remains
	 */
	static Optional<Selection> select(List<String> categories, Set<String> eligibleCategories,
		Set<String> unresolvedCategories, Map<String, CategoryCounts> counts, int batchSize,
		int coverageCursor, Turn turn)
	{
		Objects.requireNonNull(categories, "categories");
		Objects.requireNonNull(eligibleCategories, "eligibleCategories");
		Objects.requireNonNull(unresolvedCategories, "unresolvedCategories");
		Objects.requireNonNull(counts, "counts");
		Objects.requireNonNull(turn, "turn");
		Set<String> confirmedCategories = Set.copyOf(categories);
		if (categories.isEmpty() || confirmedCategories.size() != categories.size() ||
			categories.stream().anyMatch(String::isBlank) || batchSize <= 0 ||
			coverageCursor < 0 || coverageCursor >= categories.size() ||
			!confirmedCategories.equals(counts.keySet()) ||
			counts.values().stream().anyMatch(value -> value == null || value.trials() < batchSize) ||
			!confirmedCategories.containsAll(eligibleCategories) ||
			!eligibleCategories.containsAll(unresolvedCategories))
			throw new IllegalArgumentException("Follow-up development evidence or category position is invalid");
		if (unresolvedCategories.isEmpty())
			return Optional.empty();
		if (turn == Turn.COVERAGE)
		{
			for (int offset = 0; offset < categories.size(); ++offset)
			{
				int index = (coverageCursor + offset) % categories.size();
				String category = categories.get(index);
				if (eligibleCategories.contains(category))
					return Optional.of(new Selection(category, turn, (index + 1) % categories.size(),
						Turn.ADAPTIVE));
			}
			throw new IllegalArgumentException("An unresolved category must be eligible for coverage");
		}
		String selected = null;
		double lowestBound = Double.POSITIVE_INFINITY;
		int fewestTrials = Integer.MAX_VALUE;
		for (String category : categories)
		{
			if (!unresolvedCategories.contains(category))
				continue;
			CategoryCounts categoryCounts = counts.get(category);
			double lowerBound = DevelopmentWilsonInterval.calculate(categoryCounts).getLowerBound();
			if (selected == null || Double.compare(lowerBound, lowestBound) < 0 ||
				Double.compare(lowerBound, lowestBound) == 0 && categoryCounts.trials() < fewestTrials)
			{
				selected = category;
				lowestBound = lowerBound;
				fewestTrials = categoryCounts.trials();
			}
		}
		return Optional.of(new Selection(selected, turn, coverageCursor, Turn.COVERAGE));
	}
}
