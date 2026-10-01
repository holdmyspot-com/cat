package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;

/**
 * A judge's output for one trial: the verdict and the evidence that decided it (design &sect;3.1).
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param verdict whether the trial complied
 * @param rationale the evidence that decided the verdict, in the judge's words
 */
public record Judgment(Verdict verdict, String rationale)
{
	/**
	 * Creates a judgment.
	 *
	 * @param verdict whether the trial complied
	 * @param rationale the evidence that decided the verdict, in the judge's words
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code rationale} is blank
	 */
	public Judgment
	{
		requireThat(verdict, "verdict").isNotNull();
		requireThat(rationale, "rationale").isNotBlank();
	}
}
