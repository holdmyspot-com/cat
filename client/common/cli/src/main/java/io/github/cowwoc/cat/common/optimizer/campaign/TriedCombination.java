package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;

/**
 * A key-value combination that a reproduction round tried, with its reproduction counts.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param combination the combination
 * @param trials the round's adopted trials of the combination
 * @param failures the trials among {@code trials} that failed the group's requirement through the group's behavior
 */
public record TriedCombination(AddedCombination combination, int trials, int failures)
{
	/**
	 * Creates a tried combination.
	 *
	 * @param combination the combination
	 * @param trials the round's adopted trials of the combination
	 * @param failures the trials among {@code trials} that failed the group's requirement through the group's
	 *   behavior
	 * @throws NullPointerException if {@code combination} is null
	 * @throws IllegalArgumentException if {@code trials} or {@code failures} are negative, or {@code failures}
	 *   exceeds {@code trials}
	 */
	public TriedCombination
	{
		requireThat(combination, "combination").isNotNull();
		requireThat(trials, "trials").isNotNegative();
		requireThat(failures, "failures").isNotNegative().isLessThanOrEqualTo(trials, "trials");
	}

	/**
	 * Returns this combination with one more adopted trial.
	 *
	 * @param failed {@code true} if the trial failed the group's requirement through the group's behavior
	 * @return the updated combination
	 */
	TriedCombination add(boolean failed)
	{
		int added = 0;
		if (failed)
			added = 1;
		return new TriedCombination(combination, trials + 1, failures + added);
	}
}
