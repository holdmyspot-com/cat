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
 */
public record RegressionInstance(TrialInstance instance, Verdict working)
{
	/**
	 * Creates a regression instance.
	 *
	 * @param instance the instance that failed
	 * @param working the working candidate's latest outcome on the instance, or {@code null} if it has none
	 * @throws NullPointerException if {@code instance} is null
	 */
	public RegressionInstance
	{
		requireThat(instance, "instance").isNotNull();
	}
}
