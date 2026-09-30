package io.github.cowwoc.cat.common.optimizer.statistics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Current overall and per-category decisions for one formal attempt.
 */
public final class SequentialDecision
{
	private final SequentialStatus status;
	private final Map<String, SequentialStatus> categoryStatuses;
	private final Map<String, SequentialBounds> bounds;

	/**
	 * Evaluates all confirmed categories under one frozen plan.
	 *
	 * @param plan the attempt's frozen statistical plan
	 * @param requiredCategories every confirmed material category
	 * @param counts valid logical outcomes for each confirmed category
	 * @return the whole-attempt and per-category decisions
	 * @throws IllegalArgumentException if the categories and counts differ or an input is invalid
	 */
	public static SequentialDecision evaluate(SequentialPlan plan, Set<String> requiredCategories,
		Map<String, CategoryCounts> counts)
	{
		if (plan == null || requiredCategories == null || counts == null)
			throw new IllegalArgumentException("Plan, confirmed categories, and counts must be present");
		if (requiredCategories.isEmpty() || requiredCategories.stream().anyMatch(
			category -> category == null || category.isBlank()))
		{
			throw new IllegalArgumentException("Confirmed categories must contain non-blank names");
		}
		if (!requiredCategories.equals(counts.keySet()))
			throw new IllegalArgumentException("Counts must contain every confirmed category exactly once");

		Map<String, SequentialStatus> statuses = new LinkedHashMap<>();
		Map<String, SequentialBounds> bounds = new LinkedHashMap<>();
		boolean allCertified = true;
		boolean belowMinimum = false;
		boolean capExhausted = false;
		for (Map.Entry<String, CategoryCounts> entry : counts.entrySet())
		{
			if (entry.getValue() == null)
				throw new IllegalArgumentException("Every confirmed category must have counts");
			SequentialStatus status = plan.decide(entry.getValue());
			statuses.put(entry.getKey(), status);
			bounds.put(entry.getKey(), plan.bounds(entry.getValue()));
			allCertified &= status == SequentialStatus.CERTIFIED;
			belowMinimum |= status == SequentialStatus.BELOW_MINIMUM;
			capExhausted |= status == SequentialStatus.CAP_EXHAUSTED;
		}
		SequentialStatus overall = SequentialStatus.CONTINUE;
		if (belowMinimum)
			overall = SequentialStatus.BELOW_MINIMUM;
		else if (capExhausted)
			overall = SequentialStatus.CAP_EXHAUSTED;
		else if (allCertified)
			overall = SequentialStatus.CERTIFIED;
		return new SequentialDecision(overall, statuses, bounds);
	}

	/**
	 * Copies one evaluated result so later caller changes cannot alter it.
	 *
	 * @param status the whole-attempt disposition
	 * @param categoryStatuses one status per confirmed category
	 * @param bounds directional limits for the same categories
	 */
	private SequentialDecision(SequentialStatus status, Map<String, SequentialStatus> categoryStatuses,
		Map<String, SequentialBounds> bounds)
	{
		if (status == null || categoryStatuses == null || bounds == null ||
			!categoryStatuses.keySet().equals(bounds.keySet()))
		{
			throw new IllegalArgumentException("Decision, category statuses, and matching bounds are required");
		}
		this.status = status;
		this.categoryStatuses = Map.copyOf(categoryStatuses);
		this.bounds = Map.copyOf(bounds);
	}

	/**
	 * Returns the whole-attempt statistical disposition.
	 *
	 * @return the current overall status
	 */
	public SequentialStatus status()
	{
		return status;
	}

	/**
	 * Returns the current status of every confirmed category.
	 *
	 * @return immutable per-category statuses
	 */
	public Map<String, SequentialStatus> categoryStatuses()
	{
		return categoryStatuses;
	}

	/**
	 * Returns the current directional bounds for every confirmed category.
	 *
	 * @return immutable per-category bounds
	 */
	public Map<String, SequentialBounds> bounds()
	{
		return bounds;
	}
}
