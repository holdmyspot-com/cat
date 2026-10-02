package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The judged outcome of one targeted diagnostic rerun of a failing instance.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param verdict the rerun's label
 * @param rationale the judge's explanation of the label or, when no judge ran, the record of the rerun's
 *   deterministic checks
 */
public record DiagnosticRerun(Verdict verdict, String rationale)
{
	/**
	 * Creates a rerun outcome.
	 *
	 * @param verdict the rerun's label
	 * @param rationale the judge's explanation of the label or, when no judge ran, the record of the rerun's
	 *   deterministic checks
	 * @throws NullPointerException if any of the arguments are null
	 */
	public DiagnosticRerun
	{
		requireThat(verdict, "verdict").isNotNull();
		requireThat(rationale, "rationale").isNotNull();
	}
}
