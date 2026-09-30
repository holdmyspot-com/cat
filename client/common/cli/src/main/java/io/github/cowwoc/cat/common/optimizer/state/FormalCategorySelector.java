package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.statistics.SequentialDecision;
import io.github.cowwoc.cat.common.optimizer.statistics.SequentialPlan;
import io.github.cowwoc.cat.common.optimizer.statistics.SequentialStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses the next confirmed category from one formal attempt's retained outcomes.
 */
final class FormalCategorySelector
{
	/**
	 * Prevents construction.
	 */
	private FormalCategorySelector()
	{
	}

	/**
	 * Chooses the next category under the frozen formal allocation policy.
	 *
	 * @param plan the attempt's frozen sequential procedure
	 * @param categories unique confirmed category order
	 * @param counts exact valid formal outcomes for those categories
	 * @return next category, or empty when the attempt has a terminal decision
	 */
	static Optional<String> select(SequentialPlan plan, List<String> categories,
		Map<String, CategoryCounts> counts)
	{
		if (categories == null || categories.isEmpty() || categories.stream().anyMatch(
			category -> category == null || category.isBlank()))
		{
			throw new IllegalArgumentException("Confirmed category order must contain non-blank names");
		}
		Set<String> categorySet = Set.copyOf(categories);
		if (categorySet.size() != categories.size())
			throw new IllegalArgumentException("Confirmed category order must contain unique names");
		SequentialDecision decision = SequentialDecision.evaluate(plan, categorySet, counts);
		if (decision.status() != SequentialStatus.CONTINUE)
			return Optional.empty();

		for (String category : categories)
		{
			if (counts.get(category).trials() == 0)
				return Optional.of(category);
		}

		String selected = null;
		double lowestBound = Double.POSITIVE_INFINITY;
		int fewestTrials = Integer.MAX_VALUE;
		for (String category : categories)
		{
			if (decision.categoryStatuses().get(category) != SequentialStatus.CONTINUE)
				continue;
			double lowerBound = decision.bounds().get(category).lower();
			int trials = counts.get(category).trials();
			if (lowerBound < lowestBound || (lowerBound == lowestBound && trials < fewestTrials))
			{
				selected = category;
				lowestBound = lowerBound;
				fewestTrials = trials;
			}
		}
		return Optional.ofNullable(selected);
	}
}
