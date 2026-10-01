package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

/**
 * One targeted run that decides whether an author attempt is promoted.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param role why the run exists
 * @param instance the targeted instance
 * @param trial the key of the run's logical trial, or {@code null} if it is not assigned
 * @param verdict the run's outcome, or {@code null} if it is not adopted
 */
public record PromotionRun(Role role, TrialInstance instance, TrialKey trial, Verdict verdict)
{
	/**
	 * Why a promotion run exists.
	 */
	public enum Role
	{
		/**
		 * The attempt runs an instance that triggered the round; every such run must pass.
		 */
		TRIGGERING,
		/**
		 * The attempt runs a regression instance; it must not fail where the working candidate passes.
		 */
		REGRESSION,
		/**
		 * The working candidate runs a regression instance on which it has no outcome yet.
		 */
		WORKING_REFERENCE
	}

	/**
	 * Creates a run.
	 *
	 * @param role why the run exists
	 * @param instance the targeted instance
	 * @param trial the key of the run's logical trial, or {@code null} if it is not assigned
	 * @param verdict the run's outcome, or {@code null} if it is not adopted
	 * @throws NullPointerException if {@code role} or {@code instance} are null
	 * @throws IllegalArgumentException if {@code instance} is not targeted, {@code trial} is not a promotion trial,
	 *   or a verdict exists for an unassigned run
	 */
	public PromotionRun
	{
		requireThat(role, "role").isNotNull();
		requireThat(instance, "instance").isNotNull();
		requireThat(instance.targeted(), "instance.targeted()").isTrue();
		if (trial != null)
			requireThat(trial.purpose(), "trial.purpose()").isEqualTo(Purpose.PROMOTION);
		else if (verdict != null)
			throw new IllegalArgumentException("A run that is not assigned cannot have a verdict: " + verdict);
	}
}
