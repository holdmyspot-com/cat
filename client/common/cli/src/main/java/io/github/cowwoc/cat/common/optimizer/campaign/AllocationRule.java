package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * The allocation rule of {@code design.md} section 3.3 that chose a new logical trial's category.
 */
public enum AllocationRule
{
	/**
	 * Rule 1: pending targeted work, which is a diagnostic rerun or a promotion run, including a regression run.
	 */
	TARGETED,
	/**
	 * Rule 2: the development coverage floor excluded the category with the lowest exploratory lower bound, because it
	 * was {@value Scheduler#COVERAGE_LEAD} representative assignments ahead of the least-sampled eligible category.
	 */
	COVERAGE_FLOOR,
	/**
	 * Rule 3: the development category with the lowest exploratory Wilson 95% lower bound for the working candidate.
	 */
	LOWEST_EXPLORATORY_BOUND,
	/**
	 * Rule 4: the formal category that has no trial yet, otherwise the category furthest from certifying.
	 */
	FORMAL,
	/**
	 * Rule 5: a baseline-arm trial of the incumbent.
	 */
	BASELINE_ARM;

	/**
	 * Indicates whether this rule assigns trials of a purpose.
	 *
	 * @param purpose the trial's purpose
	 * @return {@code true} if the rule can choose a trial of {@code purpose}
	 * @throws NullPointerException if {@code purpose} is null
	 */
	public boolean assigns(Purpose purpose)
	{
		return switch (purpose)
		{
			case DIAGNOSTIC, PROMOTION -> this == TARGETED;
			case DEVELOPMENT -> this == COVERAGE_FLOOR || this == LOWEST_EXPLORATORY_BOUND;
			case FORMAL -> this == FORMAL;
			case BASELINE -> this == BASELINE_ARM;
		};
	}
}
