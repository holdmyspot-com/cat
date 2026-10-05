package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.FailedRequirement;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;

import java.util.List;

/**
 * A judge's output for one trial: the verdict, the evidence that decided it, and the behavior through which each
 * failed requirement failed.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param verdict whether the trial complied
 * @param rationale the evidence that decided the verdict, in the judge's words
 * @param failures each requirement that the trial failed, with its behavior; empty when the trial passed
 */
public record Judgment(Verdict verdict, String rationale, List<FailedRequirement> failures)
{
	/**
	 * Creates a judgment.
	 *
	 * @param verdict whether the trial complied
	 * @param rationale the evidence that decided the verdict, in the judge's words
	 * @param failures each requirement that the trial failed, with its behavior; empty when the trial passed
	 * @throws NullPointerException if any of the arguments are null or {@code failures} contains null
	 * @throws IllegalArgumentException if {@code rationale} is blank, or {@code failures} is empty for a failed verdict
	 *   or not empty for a passing one
	 */
	public Judgment
	{
		requireThat(verdict, "verdict").isNotNull();
		requireThat(rationale, "rationale").isNotBlank();
		requireThat(failures, "failures").isNotNull();
		failures = List.copyOf(failures);
		if (failures.isEmpty() == (verdict == Verdict.FAIL))
		{
			throw new IllegalArgumentException("A failed verdict names at least one failure and a passing verdict none. " +
				"verdict: " + verdict + ", failures: " + failures);
		}
	}
}
