package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-category lower bounds and their constrained fixed-sample status.
 *
 * @param status the fixed-sample status
 * @param lowerBounds the lower confidence bound for every category
 */
public record StatisticalDecision(StatisticalStatus status, Map<String, Double> lowerBounds)
{
	/**
	 * Validates an immutable statistical decision.
	 */
	public StatisticalDecision
	{
		requireThat(status, "status").isNotNull();
		requireThat(lowerBounds, "lowerBounds").isNotNull();
		lowerBounds = Map.copyOf(lowerBounds);
	}

	/**
	 * Evaluates all categories against a fixed sample plan.
	 *
	 * @param plan the predeclared sample plan
	 * @param requiredCategories every category in the confirmed compliance contract
	 * @param categoryCounts completed outcomes by material category
	 * @return the per-category bounds and constrained status
	 * @throws IllegalArgumentException if the count keys differ from the confirmed categories
	 */
	public static StatisticalDecision evaluate(FixedSamplePlan plan, Set<String> requiredCategories,
		Map<String, CategoryCounts> categoryCounts)
	{
		requireThat(plan, "plan").isNotNull();
		requireThat(requiredCategories, "requiredCategories").isNotNull();
		requireThat(categoryCounts, "categoryCounts").isNotNull();
		if (requiredCategories.isEmpty())
			throw new IllegalArgumentException("requiredCategories must not be empty");
		for (String category : requiredCategories)
		{
			if (category == null || category.isBlank())
				throw new IllegalArgumentException("Every required category must have a non-blank name");
		}
		if (!requiredCategories.equals(categoryCounts.keySet()))
			throw new IllegalArgumentException("categoryCounts must contain every confirmed category exactly once");

		Map<String, Double> lowerBounds = new LinkedHashMap<>();
		boolean incomplete = false;
		boolean thresholdMet = true;
		for (Map.Entry<String, CategoryCounts> entry : categoryCounts.entrySet())
		{
			CategoryCounts counts = entry.getValue();
			if (counts == null)
				throw new IllegalArgumentException("Every category must have counts");
			String category = entry.getKey();
			if (counts.trials() > plan.sampleSize())
				throw new IllegalArgumentException("Category " + category + " exceeds the fixed sample size");

			double lowerBound = 0.0;
			if (counts.trials() > 0)
			{
				lowerBound = BinomialLowerBound.oneSided(counts.successes(), counts.trials(), plan.alpha());
			}
			lowerBounds.put(category, lowerBound);
			if (counts.trials() < plan.sampleSize())
				incomplete = true;
			if (counts.trials() == plan.sampleSize() && counts.successes() < plan.successCutoff())
				thresholdMet = false;
		}
		if (incomplete)
			return new StatisticalDecision(StatisticalStatus.INCOMPLETE, lowerBounds);
		if (thresholdMet)
			return new StatisticalDecision(StatisticalStatus.THRESHOLD_MET, lowerBounds);
		return new StatisticalDecision(StatisticalStatus.THRESHOLD_NOT_MET, lowerBounds);
	}
}
