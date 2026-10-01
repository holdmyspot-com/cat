package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * Whether two observations were measured under exactly equal conditions, and which conditions prevent it.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param status whether the observations are comparable
 * @param differing the conditions that both record with different values
 * @param missing the conditions that at least one does not record
 */
public record Comparability(Status status, List<MeasurementCondition> differing, List<MeasurementCondition> missing)
{
	/**
	 * Whether two observations are comparable.
	 */
	public enum Status
	{
		/**
		 * Every condition is recorded by both and equal.
		 */
		COMPARABLE,
		/**
		 * Some condition differs; the comparison is unavailable.
		 */
		NOT_COMPARABLE,
		/**
		 * No recorded condition differs, but some is missing; the comparison is unavailable.
		 */
		INDETERMINATE
	}

	/**
	 * Creates a comparability result.
	 *
	 * @param status whether the observations are comparable
	 * @param differing the conditions that both record with different values
	 * @param missing the conditions that at least one does not record
	 * @throws NullPointerException if any argument is null
	 */
	public Comparability
	{
		requireThat(status, "status").isNotNull();
		requireThat(differing, "differing").isNotNull();
		requireThat(missing, "missing").isNotNull();
		differing = List.copyOf(differing);
		missing = List.copyOf(missing);
	}

	/**
	 * Compares the conditions of two observations by exact equality.
	 *
	 * @param first the first observation's identity
	 * @param second the second observation's identity
	 * @return the result, naming each differing and each missing condition
	 * @throws NullPointerException if any argument is null
	 */
	public static Comparability of(MeasurementIdentity first, MeasurementIdentity second)
	{
		requireThat(first, "first").isNotNull();
		requireThat(second, "second").isNotNull();
		List<MeasurementCondition> differing = first.differences(second);
		List<MeasurementCondition> missing = first.missing(second);
		Status status;
		if (!differing.isEmpty())
			status = Status.NOT_COMPARABLE;
		else if (missing.isEmpty())
			status = Status.COMPARABLE;
		else
			status = Status.INDETERMINATE;
		return new Comparability(status, differing, missing);
	}

	/**
	 * Indicates whether a comparison between the observations is available.
	 *
	 * @return {@code true} if the status is {@link Status#COMPARABLE}
	 */
	public boolean isComparable()
	{
		return status == Status.COMPARABLE;
	}
}
