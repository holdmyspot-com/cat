package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

/**
 * One exact snapshot of the prompt system being tested. The original prompt system is the incumbent.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param number the candidate's number within the campaign; the incumbent is 0
 * @param snapshot the snapshot's identity
 */
public record Candidate(int number, Sha256 snapshot)
{
	/**
	 * Creates a candidate.
	 *
	 * @param number the candidate's number within the campaign; the incumbent is 0
	 * @param snapshot the snapshot's identity
	 * @throws NullPointerException if {@code snapshot} is null
	 * @throws IllegalArgumentException if {@code number} is negative
	 */
	public Candidate
	{
		requireThat(number, "number").isNotNegative();
		requireThat(snapshot, "snapshot").isNotNull();
	}

	/**
	 * Indicates whether this candidate is the incumbent.
	 *
	 * @return {@code true} if the number is 0
	 */
	public boolean isIncumbent()
	{
		return number == 0;
	}
}
