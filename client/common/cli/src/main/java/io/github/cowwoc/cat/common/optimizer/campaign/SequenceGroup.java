package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

/**
 * The assigned trials that share one cell (prompt test and trial profile), purpose and snapshot, and therefore one
 * sequence of numbers.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cell the trials' cell identifier
 * @param purpose why the trials run
 * @param snapshot the snapshot that the trials run
 */
public record SequenceGroup(String cell, Purpose purpose, Sha256 snapshot)
{
	/**
	 * Creates a group.
	 *
	 * @param cell the trials' cell identifier
	 * @param purpose why the trials run
	 * @param snapshot the snapshot that the trials run
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code cell} is blank
	 */
	public SequenceGroup
	{
		requireThat(cell, "cell").isNotBlank();
		requireThat(purpose, "purpose").isNotNull();
		requireThat(snapshot, "snapshot").isNotNull();
	}
}
