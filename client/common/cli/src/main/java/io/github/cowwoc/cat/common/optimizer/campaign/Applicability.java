package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.List;

/**
 * Whether a result still describes the current prompt system and conditions.
 * <p>
 * A result is current only while CAT knows of no material change. A known change makes it historical without
 * deleting or rewriting it. Missing data is not a known change.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param status whether the result is current
 * @param promptContentChanged {@code true} if the prompt-system snapshot differs
 * @param changed the conditions that both identities record with different values
 */
public record Applicability(Status status, boolean promptContentChanged, List<MeasurementCondition> changed)
{
	/**
	 * Whether a result describes the current state.
	 */
	public enum Status
	{
		/**
		 * CAT knows of no material change.
		 */
		CURRENT,
		/**
		 * CAT knows of a material change; the result describes the conditions it was measured under.
		 */
		HISTORICAL
	}

	/**
	 * Creates an applicability result.
	 *
	 * @param status whether the result is current
	 * @param promptContentChanged {@code true} if the prompt-system snapshot differs
	 * @param changed the conditions that both identities record with different values
	 * @throws NullPointerException if {@code status} or {@code changed} are null
	 */
	public Applicability
	{
		requireThat(status, "status").isNotNull();
		requireThat(changed, "changed").isNotNull();
		changed = List.copyOf(changed);
	}

	/**
	 * Decides whether a result applies to the current state.
	 *
	 * @param resultSnapshot the snapshot that the result measured
	 * @param resultIdentity the conditions that the result was measured under
	 * @param currentSnapshot the current snapshot
	 * @param currentIdentity the current conditions
	 * @return the applicability, naming each known change
	 * @throws NullPointerException if any argument is null
	 */
	public static Applicability of(Sha256 resultSnapshot, MeasurementIdentity resultIdentity, Sha256 currentSnapshot,
		MeasurementIdentity currentIdentity)
	{
		requireThat(resultSnapshot, "resultSnapshot").isNotNull();
		requireThat(resultIdentity, "resultIdentity").isNotNull();
		requireThat(currentSnapshot, "currentSnapshot").isNotNull();
		requireThat(currentIdentity, "currentIdentity").isNotNull();
		boolean promptContentChanged = !resultSnapshot.equals(currentSnapshot);
		List<MeasurementCondition> changed = resultIdentity.differences(currentIdentity);
		if (promptContentChanged || !changed.isEmpty())
			return new Applicability(Status.HISTORICAL, promptContentChanged, changed);
		return new Applicability(Status.CURRENT, false, changed);
	}
}
