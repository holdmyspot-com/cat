package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.List;

/**
 * An author's validated prompt edits awaiting targeted trials that decide whether they replace the working candidate.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param number the attempt's number within the campaign, from 1
 * @param category the failing cell that the attempt addresses
 * @param snapshot the attempt's snapshot
 * @param runs the promotion runs
 */
public record AuthorAttempt(int number, String category, Sha256 snapshot, List<PromotionRun> runs)
{
	/**
	 * Creates an author attempt.
	 *
	 * @param number the attempt's number within the campaign, from 1
	 * @param category the failing cell that the attempt addresses
	 * @param snapshot the attempt's snapshot
	 * @param runs the promotion runs
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code number} is not positive or {@code category} is blank
	 */
	public AuthorAttempt
	{
		requireThat(number, "number").isPositive();
		requireThat(category, "category").isNotBlank();
		requireThat(snapshot, "snapshot").isNotNull();
		requireThat(runs, "runs").isNotNull();
		runs = List.copyOf(runs);
	}
}
