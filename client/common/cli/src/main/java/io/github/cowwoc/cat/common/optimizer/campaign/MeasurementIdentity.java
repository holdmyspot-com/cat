package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The conditions that a result was measured under, other than the candidate snapshot and time.
 * <p>
 * A condition without a value has not been observed. An observation that found nothing to record, such as no host
 * tools, is recorded as an explicit value, so that a missing value always means missing data.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param values the observed value of each recorded condition
 */
public record MeasurementIdentity(Map<MeasurementCondition, String> values)
{
	/**
	 * An identity with no recorded conditions.
	 */
	public static final MeasurementIdentity EMPTY = new MeasurementIdentity(Map.of());

	/**
	 * Creates an identity.
	 *
	 * @param values the observed value of each recorded condition
	 * @throws NullPointerException if {@code values} is null or contains a null key or value
	 * @throws IllegalArgumentException if a value is blank
	 */
	public MeasurementIdentity
	{
		requireThat(values, "values").isNotNull();
		for (Map.Entry<MeasurementCondition, String> entry : values.entrySet())
			requireThat(entry.getValue(), "values[" + entry.getKey() + "]").isNotBlank();
		values = Map.copyOf(values);
	}

	/**
	 * Returns the conditions that both identities record with different values.
	 *
	 * @param other another identity
	 * @return the differing conditions, in declaration order
	 * @throws NullPointerException if {@code other} is null
	 */
	public List<MeasurementCondition> differences(MeasurementIdentity other)
	{
		requireThat(other, "other").isNotNull();
		List<MeasurementCondition> differences = new ArrayList<>();
		for (MeasurementCondition condition : MeasurementCondition.values())
		{
			String value = values.get(condition);
			String otherValue = other.values.get(condition);
			if (value != null && otherValue != null && !value.equals(otherValue))
				differences.add(condition);
		}
		return differences;
	}

	/**
	 * Returns the conditions that at least one of the identities does not record.
	 *
	 * @param other another identity
	 * @return the conditions with missing data, in declaration order
	 * @throws NullPointerException if {@code other} is null
	 */
	public List<MeasurementCondition> missing(MeasurementIdentity other)
	{
		requireThat(other, "other").isNotNull();
		List<MeasurementCondition> missing = new ArrayList<>();
		for (MeasurementCondition condition : MeasurementCondition.values())
		{
			if (values.containsKey(condition) != other.values.containsKey(condition))
				missing.add(condition);
		}
		return missing;
	}

	/**
	 * Returns the conditions whose recorded values are not equal, including those that only one identity records.
	 *
	 * @param other another identity
	 * @return the conditions, in declaration order
	 * @throws NullPointerException if {@code other} is null
	 */
	public List<MeasurementCondition> changes(MeasurementIdentity other)
	{
		requireThat(other, "other").isNotNull();
		List<MeasurementCondition> changes = new ArrayList<>();
		for (MeasurementCondition condition : MeasurementCondition.values())
		{
			if (!Objects.equals(values.get(condition), other.values.get(condition)))
				changes.add(condition);
		}
		return changes;
	}

	/**
	 * Returns this identity with the conditions that it does not record taken from an observation. Recorded values
	 * never change.
	 *
	 * @param observed the observed conditions
	 * @return the extended identity
	 * @throws NullPointerException if {@code observed} is null
	 */
	public MeasurementIdentity extendWith(MeasurementIdentity observed)
	{
		requireThat(observed, "observed").isNotNull();
		Map<MeasurementCondition, String> extended = new EnumMap<>(MeasurementCondition.class);
		extended.putAll(observed.values);
		extended.putAll(values);
		return new MeasurementIdentity(extended);
	}

	/**
	 * Returns this identity with every observed condition replaced by its observed value.
	 *
	 * @param observed the observed conditions
	 * @return the updated identity
	 * @throws NullPointerException if {@code observed} is null
	 */
	public MeasurementIdentity replaceWith(MeasurementIdentity observed)
	{
		requireThat(observed, "observed").isNotNull();
		Map<MeasurementCondition, String> replaced = new EnumMap<>(MeasurementCondition.class);
		replaced.putAll(values);
		replaced.putAll(observed.values);
		return new MeasurementIdentity(replaced);
	}
}
