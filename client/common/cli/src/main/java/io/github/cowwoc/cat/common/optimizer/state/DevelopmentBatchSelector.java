package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.statistics.DevelopmentWilsonInterval;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses a representative category batch after every category has received initial coverage.
 */
final class DevelopmentBatchSelector
{
	/**
	 * The two batch kinds that alternate after initial coverage.
	 */
	enum Turn
	{
		COVERAGE,
		ADAPTIVE
	}

	/**
	 * Position retained between completed development batches.
	 *
	 * @param turn kind of the next batch
	 * @param coverageCursor next confirmed-order position considered for coverage
	 */
	record Progress(Turn turn, int coverageCursor)
	{
		/**
		 * Validates the next turn and confirmed-order position.
		 *
		 * @param turn kind of the next batch
		 * @param coverageCursor next confirmed-order position considered for coverage
		 */
		Progress
		{
			Objects.requireNonNull(turn, "turn");
			if (coverageCursor < 0)
				throw new IllegalArgumentException("coverageCursor must not be negative");
		}
	}

	/**
	 * Selected category and the position to retain after its batch completes.
	 *
	 * @param categoryId selected confirmed category
	 * @param turn kind of selected batch
	 * @param next position after the selected batch completes
	 */
	record Selection(String categoryId, Turn turn, Progress next)
	{
		/**
		 * Validates the selected category and next position.
		 *
		 * @param categoryId selected confirmed category
		 * @param turn kind of selected batch
		 * @param next position after the selected batch completes
		 */
		Selection
		{
			Objects.requireNonNull(categoryId, "categoryId");
			Objects.requireNonNull(turn, "turn");
			Objects.requireNonNull(next, "next");
		}
	}

	/**
	 * Prevents construction.
	 */
	private DevelopmentBatchSelector()
	{
	}

	/**
	 * Chooses from eligible categories after initial coverage is complete.
	 *
	 * @param categories unique confirmed category order
	 * @param totals current-candidate representative totals for those categories
	 * @param eligible categories without a pending admission stop
	 * @param unresolved categories that still need representative evidence
	 * @param progress retained turn and coverage cursor
	 * @return selected batch or empty when its turn has no eligible category
	 */
	static Optional<Selection> select(List<String> categories, Map<String, CategoryCounts> totals,
		Set<String> eligible, Set<String> unresolved, Progress progress)
	{
		Objects.requireNonNull(categories, "categories");
		Objects.requireNonNull(totals, "totals");
		Objects.requireNonNull(eligible, "eligible");
		Objects.requireNonNull(unresolved, "unresolved");
		Objects.requireNonNull(progress, "progress");
		if (categories.isEmpty() || progress.coverageCursor() >= categories.size())
			throw new IllegalArgumentException("progress must point into confirmed category order");
		if (!categories.containsAll(eligible) || !categories.containsAll(unresolved))
			throw new IllegalArgumentException("Categories must belong to the confirmed category order");

		if (progress.turn() == Turn.COVERAGE)
		{
			for (int offset = 0; offset < categories.size(); ++offset)
			{
				int index = (progress.coverageCursor() + offset) % categories.size();
				String category = categories.get(index);
				if (eligible.contains(category))
				{
					Progress next = new Progress(Turn.ADAPTIVE, (index + 1) % categories.size());
					return Optional.of(new Selection(category, Turn.COVERAGE, next));
				}
			}
			return Optional.empty();
		}

		String selected = null;
		double lowestBound = Double.POSITIVE_INFINITY;
		int fewestTrials = Integer.MAX_VALUE;
		for (String category : categories)
		{
			if (!eligible.contains(category) || !unresolved.contains(category))
				continue;
			CategoryCounts counts = totals.get(category);
			if (counts == null || counts.trials() == 0)
				throw new IllegalArgumentException("Adaptive category lacks initial representative outcomes: " + category);
			double lowerBound = DevelopmentWilsonInterval.calculate(counts).getLowerBound();
			if (lowerBound < lowestBound || lowerBound == lowestBound && counts.trials() < fewestTrials)
			{
				selected = category;
				lowestBound = lowerBound;
				fewestTrials = counts.trials();
			}
		}
		if (selected == null)
			return Optional.empty();
		return Optional.of(new Selection(selected, Turn.ADAPTIVE,
			new Progress(Turn.COVERAGE, progress.coverageCursor())));
	}
}
