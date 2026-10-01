package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Decides what follows an adopted representative development failure, from {@code design.md} section 2.7.
 */
public final class EditOrContinue
{
	/**
	 * The most failures that a category may have and still meet the gate by its review checkpoint.
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
		 * Start a development round, because the defect is reproducible or a category can no longer meet the gate.
		 */
		EDIT,
		/**
		 * Resume representative sampling with the window at 1.
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
	 * @return {@link Decision#EDIT} if a rerun failed or a category has more than {@value #FAILURE_LIMIT} failures,
	 *   {@link Decision#CONTINUE} if every failing instance passed its reruns, otherwise {@link Decision#RERUN}
	 * @throws NullPointerException if {@code development} is null
	 * @throws IllegalArgumentException if no diagnosis is in progress
	 */
	public static Decision decide(Development development)
	{
		requireThat(development, "development").isNotNull();
		requireThat(development.diagnosis(), "development.diagnosis()").isNotNull();
		Diagnosis diagnosis = development.diagnosis();
		if (diagnosis.reproduced())
			return Decision.EDIT;
		for (CategoryCounts counts : development.counts().values())
		{
			if (counts.failures() > FAILURE_LIMIT)
				return Decision.EDIT;
		}
		if (diagnosis.pending().isEmpty())
			return Decision.CONTINUE;
		return Decision.RERUN;
	}
}
