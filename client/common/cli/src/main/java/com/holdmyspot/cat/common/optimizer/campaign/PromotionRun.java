package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;

/**
 * Replays a test case to decide whether a prompt author's prompt edits replace the working candidate.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param role why the run exists
 * @param testCase the targeted test case
 * @param trial the key of the run's logical trial, or {@code null} if it is not assigned
 * @param verdict the run's outcome, or {@code null} if it is not adopted
 */
public record PromotionRun(Role role, DrawnTestCase testCase, TrialKey trial, Verdict verdict)
{
	/**
	 * Why a promotion run exists.
	 */
	public enum Role
	{
		/**
		 * The attempt runs a test case that triggered the round. Every run must pass and its streak must be unlikely
		 * for the working candidate. An original attempt must fix at least one test case; a reduction retains every fix
		 * from its last accepted runs.
		 */
		TRIGGERING,
		/**
		 * The attempt replays a previously failing test case once. The runs of one prompt test form a group whose
		 * failures must not be unlikely for the working candidate.
		 */
		REGRESSION,
		/**
		 * The working candidate replays a previously failing test case on which it has no outcome yet.
		 */
		WORKING_REFERENCE,
		/**
		 * The attempt replays a regression test case again because its group's failures were neither absent nor
		 * unlikely for the working candidate.
		 */
		REGRESSION_RERUN,
		/**
		 * The working candidate replays the test case of the attempt's regression rerun at the same position, so that
		 * each rerun of the attempt has a paired rerun of the working candidate.
		 */
		WORKING_RERUN;

		/**
		 * Indicates whether a run of this role tests the working candidate rather than the attempt.
		 *
		 * @return {@code true} for {@link #WORKING_REFERENCE} and {@link #WORKING_RERUN}
		 */
		public boolean measuresWorking()
		{
			return this == WORKING_REFERENCE || this == WORKING_RERUN;
		}
	}

	/**
	 * Creates a run.
	 *
	 * @param role why the run exists
	 * @param testCase the targeted test case
	 * @param trial the key of the run's logical trial, or {@code null} if it is not assigned
	 * @param verdict the run's outcome, or {@code null} if it is not adopted
	 * @throws NullPointerException if {@code role} or {@code testCase} are null
	 * @throws IllegalArgumentException if {@code testCase} is not targeted, {@code trial} is not a promotion trial,
	 *   or a verdict exists for an unassigned run
	 */
	public PromotionRun
	{
		requireThat(role, "role").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		requireThat(testCase.targeted(), "testCase.targeted()").isTrue();
		if (trial != null)
			requireThat(trial.purpose(), "trial.purpose()").isEqualTo(Purpose.PROMOTION);
		else if (verdict != null)
			throw new IllegalArgumentException("A run that is not assigned cannot have a verdict: " + verdict);
	}
}
