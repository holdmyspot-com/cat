package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

import java.util.List;

/**
 * The targeted reruns that follow a representative development failure.
 * <p>
 * Reruns run one at a time. Each failing instance reruns until it passes {@value #RERUNS} times or fails once.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param pending the failing instances still to rerun, the first being the current one
 * @param passedReruns the passing reruns of the current instance
 * @param rerun the key of the current instance's assigned rerun, or {@code null} if none is assigned
 * @param reproduced {@code true} if a rerun failed, which shows that the defect is reproducible
 */
public record Diagnosis(List<TrialInstance> pending, int passedReruns, TrialKey rerun, boolean reproduced)
{
	/**
	 * The passing reruns that clear an instance.
	 */
	public static final int RERUNS = 3;

	/**
	 * Creates a diagnosis.
	 *
	 * @param pending the failing instances still to rerun, the first being the current one
	 * @param passedReruns the passing reruns of the current instance
	 * @param rerun the key of the current instance's assigned rerun, or {@code null} if none is assigned
	 * @param reproduced {@code true} if a rerun failed
	 * @throws NullPointerException if {@code pending} is null
	 * @throws IllegalArgumentException if {@code passedReruns} is outside [0, {@value #RERUNS}) or {@code rerun} is
	 *   not a diagnostic trial
	 */
	public Diagnosis
	{
		requireThat(pending, "pending").isNotNull();
		requireThat(passedReruns, "passedReruns").isBetween(0, true, RERUNS, false);
		if (rerun != null)
			requireThat(rerun.purpose(), "rerun.purpose()").isEqualTo(Purpose.DIAGNOSTIC);
		pending = List.copyOf(pending);
	}
}
