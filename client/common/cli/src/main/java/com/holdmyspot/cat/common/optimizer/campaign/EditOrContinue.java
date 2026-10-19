package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;

/**
 * Chooses whether to rerun, edit or continue sampling after a representative development failure is counted.
 * Decisions use each cell's counts; a cell is a prompt test under one runner profile.
 * <p>
 * A {@link Impact#BLOCKING blocking} failure never reaches this decision: its adoption discards the working
 * candidate's unadopted development trials and edits at once. The decision applies to
 * {@link Impact#SELF_RECOVERING self-recovering} failures and to failures recorded without an impact.
 */
public final class EditOrContinue
{
	/**
	 * What follows a failure.
	 */
	public enum Decision
	{
		/**
		 * Rerun the current failing test case.
		 */
		RERUN,
		/**
		 * Start a development round, because the defect is reproducible or a cell exceeds the failure limit.
		 */
		EDIT,
		/**
		 * Resume representative sampling with at most one active candidate trial at a time.
		 */
		CONTINUE
	}

	/**
	 * Prevents instantiation.
	 */
	private EditOrContinue()
	{
	}

	/**
	 * Decides what follows the diagnosis in progress.
	 *
	 * @param development the development state, with a diagnosis in progress
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return {@link Decision#EDIT} if a rerun failed or a cell has more than {@link #failureLimit(BigDecimal)}
	 *   failures, {@link Decision#CONTINUE} if every failing test case passed its reruns, otherwise
	 *   {@link Decision#RERUN}
	 * @throws NullPointerException if any of the arguments are null, or if {@code development} has no diagnosis in
	 *   progress
	 */
	public static Decision decide(Development development, BigDecimal minimumPassRate)
	{
		requireThat(development, "development").isNotNull();
		requireThat(development.diagnosis(), "development.diagnosis()").isNotNull();
		Diagnosis diagnosis = development.diagnosis();
		if (diagnosis.reproduced())
			return Decision.EDIT;
		int limit = failureLimit(minimumPassRate);
		for (CellCounts counts : development.counts().values())
		{
			if (counts.failures() > limit)
				return Decision.EDIT;
		}
		if (diagnosis.pending().isEmpty())
			return Decision.CONTINUE;
		return Decision.RERUN;
	}

	/**
	 * Returns the most representative development failures that a cell may have before an edit starts, for the minimum
	 * pass rate {@code p_min}: {@code floor(40 (1 - p_min))}. A cell with more failures cannot meet the commit gate's
	 * observed pass rate by the review checkpoint.
	 *
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return the limit
	 * @throws NullPointerException if {@code minimumPassRate} is null
	 */
	public static int failureLimit(BigDecimal minimumPassRate)
	{
		requireThat(minimumPassRate, "minimumPassRate").isNotNull();
		return BigDecimal.ONE.subtract(minimumPassRate).multiply(BigDecimal.valueOf(40)).intValue();
	}
}
