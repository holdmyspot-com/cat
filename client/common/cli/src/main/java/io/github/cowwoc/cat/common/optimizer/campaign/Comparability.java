package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The comparison of two observations' measurement conditions: whether they are equal, and which conditions prevent
 * comparison.
 * <p>
 * Compares campaign-wide values and values for the same cell, the prompt test under a runner profile.
 * A cell uses its recorded value when present, otherwise the campaign-wide value. Conditions absent from both
 * observations have not been observed and do not prevent comparison, and conditions that are recorded for
 * information only never prevent it.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param status indicates whether the observations are comparable
 * @param differing the conditions with different recorded values campaign-wide or for the same cell
 * @param missing the conditions recorded by only one observation campaign-wide or for the same cell
 * @param reason a readable explanation of the conditions that prevent comparison, or {@code null}
 */
public record Comparability(Status status, List<MeasurementCondition> differing, List<MeasurementCondition> missing,
	String reason)
{
	/**
	 * The comparability of two observations.
	 */
	public enum Status
	{
		/**
		 * The conditions that were observed are recorded and equal in both observations.
		 */
		COMPARABLE,
		/**
		 * Some condition differs; the comparison is unavailable.
		 */
		NOT_COMPARABLE,
		/**
		 * Some observed condition is missing from either observation; comparison is unavailable.
		 */
		INDETERMINATE
	}

	/**
	 * Creates a comparability result.
	 *
	 * @param status whether the observations are comparable
	 * @param differing the conditions with different recorded values campaign-wide or for the same cell
	 * @param missing the conditions recorded by only one observation campaign-wide or for the same cell
	 * @param reason a readable explanation of the conditions that prevent comparison, or {@code null}
	 * @throws NullPointerException if {@code status} or either list is null or either list contains null
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
	 * Creates a comparability result without observation values.
	 *
	 * @param status indicates whether the observations are comparable
	 * @param differing the conditions with different recorded values
	 * @param missing the conditions recorded by only one observation
	 * @throws NullPointerException if any argument is null or either list contains null
	 */
	public Comparability(Status status, List<MeasurementCondition> differing, List<MeasurementCondition> missing)
	{
		this(status, differing, missing, genericReason(differing, missing));
	}

	/**
	 * Compares the conditions of two observations by exact equality.
	 *
	 * @param first the working candidate's identity
	 * @param second the incumbent baseline's identity
	 * @return the result, naming each differing and each missing condition
	 * @throws NullPointerException if any argument is null
	 */
	public static Comparability of(MeasurementIdentity first, MeasurementIdentity second)
	{
		requireThat(first, "first").isNotNull();
		requireThat(second, "second").isNotNull();
		List<String> reasons = new ArrayList<>();
		Set<String> cells = new TreeSet<>(first.cells().keySet());
		cells.addAll(second.cells().keySet());
		for (MeasurementCondition condition : MeasurementCondition.values())
		{
			if (!condition.isMaterial())
				continue;
			String firstValue = first.values().get(condition);
			String secondValue = second.values().get(condition);
			addReason(reasons, condition, firstValue, secondValue, null);
			for (String cell : cells)
			{
				String firstCellValue = first.valueOf(cell, condition);
				String secondCellValue = second.valueOf(cell, condition);
				if (!Objects.equals(firstValue, firstCellValue) || !Objects.equals(secondValue, secondCellValue))
					addReason(reasons, condition, firstCellValue, secondCellValue, cell);
			}
		}
		return result(first.differences(second), first.missing(second), reasons);
	}

	/**
	 * Compares the effective conditions of two observations for a single cell.
	 *
	 * @param first the working candidate's identity
	 * @param second the incumbent baseline's identity
	 * @param cell the cell's identifier
	 * @return the result, naming each differing and each missing condition in the cell
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code cell} is blank
	 */
	public static Comparability of(MeasurementIdentity first, MeasurementIdentity second, String cell)
	{
		requireThat(first, "first").isNotNull();
		requireThat(second, "second").isNotNull();
		requireThat(cell, "cell").isNotBlank();
		List<MeasurementCondition> differing = new ArrayList<>();
		List<MeasurementCondition> missing = new ArrayList<>();
		List<String> reasons = new ArrayList<>();
		for (MeasurementCondition condition : MeasurementCondition.values())
		{
			if (!condition.isMaterial())
				continue;
			String firstValue = first.valueOf(cell, condition);
			String secondValue = second.valueOf(cell, condition);
			if ((firstValue == null) != (secondValue == null))
				missing.add(condition);
			else if (!Objects.equals(firstValue, secondValue))
				differing.add(condition);
			addReason(reasons, condition, firstValue, secondValue, cell);
		}
		return result(differing, missing, reasons);
	}

	/**
	 * Creates the result for the conditions that prevent comparison.
	 *
	 * @param differing the differing conditions
	 * @param missing the conditions recorded by only one observation
	 * @param reasons the readable reasons
	 * @return the comparability result
	 */
	private static Comparability result(List<MeasurementCondition> differing, List<MeasurementCondition> missing,
		List<String> reasons)
	{
		Status status;
		if (!missing.isEmpty())
			status = Status.INDETERMINATE;
		else if (!differing.isEmpty())
			status = Status.NOT_COMPARABLE;
		else
			status = Status.COMPARABLE;
		String reason = null;
		if (!reasons.isEmpty())
			reason = String.join(" ", reasons);
		return new Comparability(status, differing, missing, reason);
	}

	/**
	 * Adds an explanation if two values prevent comparison.
	 *
	 * @param reasons the explanations
	 * @param condition the condition being compared
	 * @param first the working candidate's value
	 * @param second the incumbent baseline's value
	 * @param cell the cell's identifier, or {@code null} for campaign-wide observations
	 */
	private static void addReason(List<String> reasons, MeasurementCondition condition, String first, String second,
		String cell)
	{
		if (Objects.equals(first, second))
			return;
		String location = "";
		if (cell != null)
			location = " in cell " + cell;
		if (first == null)
			reasons.add("The working candidate does not record the " + condition.displayName() + " observed" +
				location + " for the incumbent baseline (" + second + ").");
		else if (second == null)
			reasons.add("The incumbent baseline does not record the " + condition.displayName() + " observed" +
				location + " for the working candidate (" + first + ").");
		else
			reasons.add("The " + condition.displayName() + differenceVerb(condition) + location +
				": working candidate " + first +
				"; incumbent baseline " + second + ".");
	}

	/**
	 * Returns the verb agreeing with a condition's name.
	 *
	 * @param condition the condition
	 * @return the verb for a differing condition, including the leading space
	 */
	private static String differenceVerb(MeasurementCondition condition)
	{
		return switch (condition)
		{
			case PROMPT_SOURCES, HOST_TOOLS, EXTERNAL_SOURCES -> " differ";
			default -> " differs";
		};
	}

	/**
	 * Names the conditions that prevent comparison when the observations' values are unavailable.
	 *
	 * @param differing the differing conditions
	 * @param missing the conditions recorded by only one observation
	 * @return a readable explanation, or {@code null} when no condition prevents comparison
	 */
	private static String genericReason(List<MeasurementCondition> differing, List<MeasurementCondition> missing)
	{
		List<String> reasons = new ArrayList<>();
		for (MeasurementCondition condition : differing)
			reasons.add("The " + condition.displayName() + differenceVerb(condition) + ".");
		for (MeasurementCondition condition : missing)
			reasons.add("The observations do not both record the " + condition.displayName() + ".");
		if (reasons.isEmpty())
			return null;
		return String.join(" ", reasons);
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
	 * Returns the unavailable comparison's readable reason, or {@code null} when comparison is available.
	 *
	 * @return the conditions that prevent comparison in words, or {@code null}
	 */
	public String unavailable()
	{
		if (isComparable())
			return null;
		return reason;
	}
}
