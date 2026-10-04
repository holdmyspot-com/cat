package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Chooses whether to rerun, edit or continue sampling after a representative development failure is counted.
 * Decisions use each cell's counts; a cell is a requirement category under one runner profile.
 * <p>
 * A {@link Impact#BLOCKING blocking} failure never reaches this decision: its adoption discards the working
 * candidate's unadopted development trials and edits at once. The decision applies to
 * {@link Impact#SELF_RECOVERING self-recovering} failures and to failures recorded without an impact.
 */
public final class EditOrContinue
{
	/**
	 * Start an edit when a cell has more than this many representative development failures.
	 */
	public static final int FAILURE_LIMIT = 2;

	/**
	 * What follows a failure.
	 */
	public enum Decision
	{
		/**
		 * Rerun the current failing instance.
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
	 * @return {@link Decision#EDIT} if a rerun failed or a cell has more than {@value #FAILURE_LIMIT} failures,
	 *   {@link Decision#CONTINUE} if every failing instance passed its reruns, otherwise {@link Decision#RERUN}
	 * @throws NullPointerException if {@code development} is null or has no diagnosis in progress
	 */
	public static Decision decide(Development development)
	{
		requireThat(development, "development").isNotNull();
		requireThat(development.diagnosis(), "development.diagnosis()").isNotNull();
		Diagnosis diagnosis = development.diagnosis();
		if (diagnosis.reproduced())
			return Decision.EDIT;
		for (CellCounts counts : development.counts().values())
		{
			if (counts.failures() > FAILURE_LIMIT)
				return Decision.EDIT;
		}
		if (diagnosis.pending().isEmpty())
			return Decision.CONTINUE;
		return Decision.RERUN;
	}
}
