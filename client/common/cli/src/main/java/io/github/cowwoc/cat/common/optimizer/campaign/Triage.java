package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The impact of a failed development trial and the reason for it.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param impact the failure's impact
 * @param rationale the triage session's explanation of {@code impact} or, when the triage failed, why the failure
 *   counts as {@link Impact#BLOCKING blocking}
 */
public record Triage(Impact impact, String rationale)
{
	/**
	 * Creates a triage result.
	 *
	 * @param impact the failure's impact
	 * @param rationale the triage session's explanation of {@code impact} or, when the triage failed, why the failure
	 *   counts as {@link Impact#BLOCKING blocking}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code rationale} is blank
	 */
	public Triage
	{
		requireThat(impact, "impact").isNotNull();
		requireThat(rationale, "rationale").isNotBlank();
	}
}
