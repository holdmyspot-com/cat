package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

/**
 * The identity of one assigned test instance. Retries keep it.
 * <p>
 * Trials that share a cell (category and trial profile), purpose and snapshot are numbered 1, 2, … in assignment order
 * within their {@link SequenceGroup}. Trials that also share a series form an adoption group: their outcomes enter
 * campaign evidence in sequence order.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param purpose why the trial runs
 * @param series the development series for {@link Purpose#DEVELOPMENT} and {@link Purpose#DIAGNOSTIC}, the baseline
 *   index for {@link Purpose#BASELINE}, the certification index for {@link Purpose#FORMAL}, or the author attempt
 *   number for {@link Purpose#PROMOTION}
 * @param snapshot the snapshot that the trial runs
 * @param category the trial's cell identifier
 * @param sequence the trial's number within its sequence group, starting at 1
 */
public record TrialKey(Purpose purpose, int series, Sha256 snapshot, String category, long sequence)
{
	/**
	 * Creates a key.
	 *
	 * @param purpose why the trial runs
	 * @param series the series that the trial belongs to
	 * @param snapshot the snapshot that the trial runs
	 * @param category the trial's cell identifier
	 * @param sequence the trial's number within its sequence group, starting at 1
	 * @throws NullPointerException if {@code purpose}, {@code snapshot} or {@code category} are null
	 * @throws IllegalArgumentException if {@code series} is negative, {@code sequence} is not positive, or
	 *   {@code category} is blank
	 */
	public TrialKey
	{
		requireThat(purpose, "purpose").isNotNull();
		requireThat(series, "series").isNotNegative();
		requireThat(snapshot, "snapshot").isNotNull();
		requireThat(category, "category").isNotBlank();
		requireThat(sequence, "sequence").isPositive();
	}

	/**
	 * Returns the group whose numbering the trial's sequence number belongs to.
	 *
	 * @return the group
	 */
	public SequenceGroup sequenceGroup()
	{
		return new SequenceGroup(category, purpose, snapshot);
	}

	/**
	 * Indicates whether two trials belong to the same adoption group.
	 *
	 * @param other another key
	 * @return {@code true} if both share a purpose, series, snapshot and cell
	 * @throws NullPointerException if {@code other} is null
	 */
	public boolean isSameGroup(TrialKey other)
	{
		return purpose == other.purpose && series == other.series && snapshot.equals(other.snapshot) &&
			category.equals(other.category);
	}
}
