package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;

import java.util.List;

/**
 * The pending reruns and results used to decide whether a prompt author should investigate sampled development
 * failures.
 * <p>
 * Each failing test case reruns one at a time. Treat {@value #RERUNS} passes as clearing that test case and one failure
 * as reproducing its defect.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param pending the failing test cases still to rerun, the first being the current one
 * @param passedReruns the passing reruns of the current test case
 * @param rerun the key of the current test case's assigned rerun, or {@code null} if none is assigned
 * @param reproduced {@code true} if a rerun failed, which shows that the defect is reproducible
 */
public record Diagnosis(List<DrawnTestCase> pending, int passedReruns, TrialKey rerun, boolean reproduced)
{
	/**
	 * The passing reruns that clear a test case.
	 */
	public static final int RERUNS = 3;

	/**
	 * Creates a diagnosis.
	 *
	 * @param pending the failing test cases still to rerun, the first being the current one
	 * @param passedReruns the passing reruns of the current test case
	 * @param rerun the key of the current test case's assigned rerun, or {@code null} if none is assigned
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
