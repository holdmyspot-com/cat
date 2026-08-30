package io.github.cowwoc.cat.common.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collapses testcase attempt statuses into whole-suite trial outcomes.
 */
public final class Outcomes
{
	/**
	 * Represents one complete whole-suite trial.
	 *
	 * @param trial the trial number
	 * @param passed whether every suite testcase passed
	 */
	public record Result(int trial, boolean passed)
	{
	}

	/**
	 * Prevents construction of this static calculation owner.
	 */
	private Outcomes()
	{
	}

	/**
	 * Returns complete whole-suite outcomes in trial order.
	 *
	 * @param caseIds the required testcase identifiers
	 * @param statuses statuses indexed by trial and testcase
	 * @return each completed whole-suite outcome
	 */
	public static List<Result> complete(Set<String> caseIds, Map<Integer, Map<String, AttemptStatus>> statuses)
	{
		List<Result> results = new ArrayList<>();
		for (Map.Entry<Integer, Map<String, AttemptStatus>> entry : statuses.entrySet())
		{
			Map<String, AttemptStatus> trial = entry.getValue();
			if (trial.values().stream().anyMatch(status -> status == AttemptStatus.FAILED))
				results.add(new Result(entry.getKey(), false));
			else if (trial.keySet().containsAll(caseIds) &&
				trial.values().stream().allMatch(status -> status == AttemptStatus.PASSED))
			{
				results.add(new Result(entry.getKey(), true));
			}
		}
		return results;
	}
}
