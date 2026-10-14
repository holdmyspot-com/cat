package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * The scheduler rule that chose a new trial's cell: a prompt test under one model and reasoning profile.
 */
public enum AllocationRule
{
	/**
	 * Rule 1: replay a failing test case for diagnosis or to check whether a prompt author's edits replace the working
	 * candidate, or reproduce a formal failure group on its round's test cases.
	 */
	TARGETED,
	/**
	 * Rule 2: choose another eligible cell because the cell with the lowest Wilson 95% lower bound is at least
	 * {@value Scheduler#COVERAGE_LEAD} assignments ahead of the least-sampled eligible cell, or because a model's
	 * lowest-effort cell has priority among cells within that limit.
	 */
	COVERAGE_FLOOR,
	/**
	 * Rule 3: choose the development cell with the lowest Wilson 95% lower bound for the working candidate.
	 */
	LOWEST_EXPLORATORY_BOUND,
	/**
	 * Rule 4: choose a certification cell with no trial yet, otherwise the cell furthest from certifying.
	 */
	FORMAL,
	/**
	 * Rule 5: in a cell whose minimum-pass-rate part certified, pair a candidate formal trial with a control trial or
	 * an incumbent trial, then add the further candidate formal trials that the cell's comparisons need.
	 */
	PAIRED,
	/**
	 * Rule 6: sample a trial to measure the original prompt system, called the incumbent.
	 */
	BASELINE;

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
			case DIAGNOSTIC, PROMOTION, REPRODUCTION -> this == TARGETED;
			case DEVELOPMENT -> this == COVERAGE_FLOOR || this == LOWEST_EXPLORATORY_BOUND;
			case FORMAL -> this == FORMAL || this == PAIRED;
			case CONTROL, INCUMBENT -> this == PAIRED;
			case BASELINE -> this == BASELINE;
		};
	}
}
