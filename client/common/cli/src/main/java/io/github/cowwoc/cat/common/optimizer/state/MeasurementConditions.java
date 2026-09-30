package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * One complete point-in-time observation of material measurement conditions.
 *
 * @param values known evidence or an explicit limitation for every material condition
 */
public record MeasurementConditions(Map<Kind, Value> values)
{
	/** Material condition dimensions named by the product contract. */
	public enum Kind
	{
		CANDIDATE,
		CONTRACT,
		GENERATOR,
		EVALUATOR,
		MODEL_PROFILE,
		HARNESS_RUNNER,
		HOST_TOOLS,
		PROMPT_SOURCES,
		EXTERNAL_SOURCES
	}

	/** Known immutable evidence or a specific observation limitation. */
	public sealed interface Value permits Value.Known, Value.Unknown
	{
		/**
		 * An observed condition backed by immutable evidence.
		 *
		 * @param evidence material condition evidence
		 */
		record Known(EvidenceRef evidence) implements Value
		{
			/**
			 * Validates the evidence reference.
			 *
			 * @param evidence material condition evidence
			 */
			public Known
			{
				Objects.requireNonNull(evidence, "evidence");
			}
		}

		/**
		 * A condition the current observation cannot establish.
		 *
		 * @param limitation reason the condition remains unknown
		 */
		record Unknown(String limitation) implements Value
		{
			/**
			 * Requires a specific limitation.
			 *
			 * @param limitation reason the condition remains unknown
			 */
			public Unknown
			{
				if (limitation == null || limitation.isBlank())
					throw new IllegalArgumentException("Unknown condition requires a limitation");
			}
		}
	}

	/** Field-level result of comparing two condition observations. */
	public enum Status
	{
		UNCHANGED,
		CHANGED,
		UNKNOWN
	}

	/**
	 * A complete field-by-field comparison.
	 *
	 * @param fields comparison result for every material condition
	 */
	public record Comparison(Map<Kind, Status> fields)
	{
		/**
		 * Requires a result for every material condition.
		 *
		 * @param fields comparison result for every material condition
		 */
		public Comparison
		{
			fields = completeCopy(fields);
		}

		/**
		 * Reports whether any known material condition changed.
		 *
		 * @return {@code true} if at least one field changed
		 */
		public boolean hasKnownChange()
		{
			return fields.containsValue(Status.CHANGED);
		}
	}

	/**
	 * Requires each condition to be present, including conditions that cannot be observed.
	 *
	 * @param values known evidence or an explicit limitation for every material condition
	 */
	public MeasurementConditions
	{
		values = completeCopy(values);
	}

	/**
	 * Compares each material condition with a later observation.
	 *
	 * @param current later observation
	 * @return known changes, known equality, and unknown comparisons by condition
	 */
	public Comparison compare(MeasurementConditions current)
	{
		Objects.requireNonNull(current, "current");
		Map<Kind, Status> compared = new EnumMap<>(Kind.class);
		for (Kind kind : Kind.values())
		{
			Value previous = values.get(kind);
			Value latest = current.values().get(kind);
			if (previous instanceof Value.Known earlier && latest instanceof Value.Known later)
			{
				if (earlier.evidence().equals(later.evidence()))
					compared.put(kind, Status.UNCHANGED);
				else
					compared.put(kind, Status.CHANGED);
			}
			else
				compared.put(kind, Status.UNKNOWN);
		}
		return new Comparison(compared);
	}

	/**
	 * Copies a complete enum-keyed condition map in its declared order.
	 *
	 * @param source caller-provided map
	 * @param <V> mapped value type
	 * @return complete immutable map
	 */
	private static <V> Map<Kind, V> completeCopy(Map<Kind, V> source)
	{
		Objects.requireNonNull(source, "source");
		Map<Kind, V> copy = new EnumMap<>(Kind.class);
		for (Map.Entry<Kind, V> entry : source.entrySet())
			copy.put(Objects.requireNonNull(entry.getKey(), "condition"),
				Objects.requireNonNull(entry.getValue(), "value"));
		if (copy.size() != Kind.values().length)
			throw new IllegalArgumentException("Every material condition requires an explicit value");
		return Collections.unmodifiableMap(copy);
	}
}
