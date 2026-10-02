package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether two observations record equal measurement conditions, and which conditions prevent comparison.
 * <p>
 * Compares campaign-wide values and values for the same cell, the requirement category under a runner profile.
 * A cell uses its recorded value when present, otherwise the campaign-wide value. Every required condition must be
 * recorded in both observations, including conditions absent from both.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param status whether the observations are comparable
 * @param differing the conditions with different recorded values campaign-wide or for the same cell
 * @param missing the conditions missing from either observation campaign-wide or for the same cell
 */
public record Comparability(Status status, List<MeasurementCondition> differing, List<MeasurementCondition> missing)
{
	/**
	 * Whether two observations are comparable.
	 */
	public enum Status
	{
		/**
		 * Every required condition is recorded and equal.
		 */
		COMPARABLE,
		/**
		 * Some condition differs; the comparison is unavailable.
		 */
		NOT_COMPARABLE,
		/**
		 * Some required condition is missing from either observation; comparison is unavailable.
		 */
		INDETERMINATE
	}

	/**
	 * Creates a comparability result.
	 *
	 * @param status whether the observations are comparable
	 * @param differing the conditions with different recorded values campaign-wide or for the same cell
	 * @param missing the conditions missing from either observation campaign-wide or for the same cell
	 * @throws NullPointerException if any argument is null or either list contains null
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
		List<MeasurementCondition> missing = findMissing(first, second);
		Status status;
		if (!missing.isEmpty())
			status = Status.INDETERMINATE;
		else if (!differing.isEmpty())
			status = Status.NOT_COMPARABLE;
		else
			status = Status.COMPARABLE;
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

	/**
	 * Returns the unavailable comparison's status and conditions, or {@code null} when comparison is available.
	 *
	 * @return the status and conditions that prevent comparison, or {@code null}
	 */
	public String unavailable()
	{
		if (isComparable())
			return null;
		Set<MeasurementCondition> conditions = EnumSet.noneOf(MeasurementCondition.class);
		conditions.addAll(differing);
		conditions.addAll(missing);
		return status + ": " + conditions;
	}

	/**
	 * Returns required conditions absent from either observation, using campaign-wide values for cells that
	 * record none of their own.
	 *
	 * @param first the first observation
	 * @param second the second observation
	 * @return missing conditions in declaration order
	 */
	private static List<MeasurementCondition> findMissing(MeasurementIdentity first, MeasurementIdentity second)
	{
		Set<String> cells = new HashSet<>(first.cells().keySet());
		cells.addAll(second.cells().keySet());
		List<MeasurementCondition> missing = new ArrayList<>();
		for (MeasurementCondition condition : MeasurementCondition.values())
		{
			boolean absent = false;
			if (cells.isEmpty())
				absent = first.values().get(condition) == null || second.values().get(condition) == null;
			for (String cell : cells)
			{
				if (first.valueOf(cell, condition) == null || second.valueOf(cell, condition) == null)
				{
					absent = true;
					break;
				}
			}
			if (absent)
				missing.add(condition);
		}
		return missing;
	}
}
