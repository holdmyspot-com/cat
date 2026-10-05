package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

/**
 * An instance that failed a sampled trial of an earlier prompt snapshot or of the original prompt system, retained
 * for replay to check later edits.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param instance the instance that failed
 * @param working the working candidate's latest outcome on the instance, or {@code null} if it has none
 * @param settled {@code true} if the working candidate's failing outcome on the instance was already addressed by an
 *   optimization round that ended without a promotable attempt, so the failure no longer keeps the working candidate
 *   from certification; {@code false} if the outcome is not a failure or no such round ended after it
 */
public record RegressionInstance(TrialInstance instance, Verdict working, boolean settled)
{
	/**
	 * Creates a regression instance.
	 *
	 * @param instance the instance that failed
	 * @param working the working candidate's latest outcome on the instance, or {@code null} if it has none
	 * @param settled {@code true} if the working candidate's failing outcome on the instance was already addressed by
	 *   an optimization round that ended without a promotable attempt, so the failure no longer keeps the working
	 *   candidate from certification; {@code false} if the outcome is not a failure or no such round ended after it
	 * @throws NullPointerException if {@code instance} is null
	 * @throws IllegalArgumentException if {@code settled} is {@code true} and {@code working} is not
	 *   {@link Verdict#FAIL}
	 */
	public RegressionInstance
	{
		requireThat(instance, "instance").isNotNull();
		if (settled)
			requireThat(working, "working").withContext(settled, "settled").isEqualTo(Verdict.FAIL);
	}
}
