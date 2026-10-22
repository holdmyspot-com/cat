package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/**
 * The conditions that a result was measured under, other than the candidate snapshot and time.
 * <p>
 * A condition without a value has not been observed. An observation that found nothing to record, such as no host
 * tools, is recorded as an explicit value, so that a missing value always means missing data.
 * <p>
 * A cell tests one prompt test under one model and reasoning profile. Its observed trial model and reasoning
 * configuration are compared with observations of the same cell; different cells can use different profiles.
 * A condition that is recorded for the whole campaign applies to every cell that does not record its own value, so a
 * cell's value that equals the campaign-wide value is redundant and is not kept. Equal identities therefore record
 * equal conditions.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param values the observed value of each recorded condition that applies to the whole campaign
 * @param cells the observed value of each recorded condition of each cell, keyed by the cell's identifier
 */
public record MeasurementIdentity(Map<MeasurementCondition, String> values,
	Map<String, Map<MeasurementCondition, String>> cells)
{
	/**
	 * An identity with no recorded conditions.
	 */
	public static final MeasurementIdentity EMPTY = new MeasurementIdentity(Map.of());
	/**
	 * The {@link MeasurementCondition#PLUGIN_VERSIONS} entry of a trial plugin whose manifest declares no version.
	 */
	public static final String NO_PLUGIN_VERSION = "unversioned";
	/**
	 * The {@link MeasurementCondition#PLUGIN_BUILDS} entry of a trial plugin that trials install from the user's
	 * installed copy.
	 */
	public static final String INSTALLED_PLUGIN = "installed copy";

	/**
	 * Creates an identity.
	 *
	 * @param values the observed value of each recorded condition that applies to the whole campaign
	 * @param cells the observed value of each recorded condition of each cell, keyed by the cell's identifier
	 * @throws NullPointerException if {@code values} or {@code cells} are null, or contain a null key or value
	 * @throws IllegalArgumentException if a value or a cell identifier is blank
	 */
	public MeasurementIdentity
	{
		requireThat(values, "values").isNotNull();
		requireThat(cells, "cells").isNotNull();
		for (Map.Entry<MeasurementCondition, String> entry : values.entrySet())
			requireThat(entry.getValue(), "values[" + entry.getKey() + "]").isNotBlank();
		values = Map.copyOf(values);
		Map<String, Map<MeasurementCondition, String>> copy = new TreeMap<>();
		for (Map.Entry<String, Map<MeasurementCondition, String>> cell : cells.entrySet())
		{
			requireThat(cell.getKey(), "cell").isNotBlank();
			Map<MeasurementCondition, String> own = new EnumMap<>(MeasurementCondition.class);
			for (Map.Entry<MeasurementCondition, String> entry : cell.getValue().entrySet())
			{
				requireThat(entry.getValue(), "cells[" + cell.getKey() + "][" + entry.getKey() + "]").isNotBlank();
				if (!entry.getValue().equals(values.get(entry.getKey())))
					own.put(entry.getKey(), entry.getValue());
			}
			if (!own.isEmpty())
				copy.put(cell.getKey(), Map.copyOf(own));
		}
		cells = Collections.unmodifiableMap(copy);
	}

	/**
	 * Creates an identity that records no per-cell conditions.
	 *
	 * @param values the observed value of each recorded condition
	 * @throws NullPointerException if {@code values} is null or contains a null key or value
	 * @throws IllegalArgumentException if a value is blank
	 */
	public MeasurementIdentity(Map<MeasurementCondition, String> values)
	{
		this(values, Map.of());
	}

	/**
	 * Returns the conditions that a campaign's trial plugins set.
	 *
	 * @param plugins  the trial plugins, in configuration order
	 * @param versions the version that the manifest in each plugin's base declares, keyed by the plugin's name; a
	 *                 plugin whose manifest declares none is absent
	 * @return an identity that records {@link MeasurementCondition#TRIAL_PLUGINS},
	 *   {@link MeasurementCondition#PLUGIN_BASES}, {@link MeasurementCondition#PLUGIN_VERSIONS},
	 *   {@link MeasurementCondition#PLUGIN_BUILDS} and {@link MeasurementCondition#PLUGIN_MAPPINGS}, each {@code none}
	 *   when the list is empty
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static MeasurementIdentity ofPlugins(List<TrialPlugin> plugins, Map<String, String> versions)
	{
		requireThat(plugins, "plugins").isNotNull();
		requireThat(versions, "versions").isNotNull();
		StringJoiner names = new StringJoiner("\n");
		StringJoiner bases = new StringJoiner("\n");
		StringJoiner pluginVersions = new StringJoiner("\n");
		StringJoiner builds = new StringJoiner("\n");
		StringJoiner mappings = new StringJoiner("\n");
		for (TrialPlugin plugin : plugins)
		{
			names.add(plugin.name());
			bases.add(plugin.name() + " " + plugin.baseDigest());
			pluginVersions.add(plugin.name() + " " + versions.getOrDefault(plugin.name(), NO_PLUGIN_VERSION));
			String build = switch (plugin.source())
			{
				case TrialPlugin.Build b -> "output " + PortablePaths.text(b.output()) + ", command " + b.command();
				case TrialPlugin.Installed _ -> INSTALLED_PLUGIN;
			};
			builds.add(plugin.name() + ": " + build);
			for (TrialPlugin.Mapping mapping : plugin.mappings())
			{
				mappings.add(plugin.name() + ": " + PortablePaths.text(mapping.source()) + " -> " +
					PortablePaths.text(mapping.target()));
			}
		}
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.put(MeasurementCondition.TRIAL_PLUGINS, orNone(names));
		values.put(MeasurementCondition.PLUGIN_BASES, orNone(bases));
		values.put(MeasurementCondition.PLUGIN_VERSIONS, orNone(pluginVersions));
		values.put(MeasurementCondition.PLUGIN_BUILDS, orNone(builds));
		values.put(MeasurementCondition.PLUGIN_MAPPINGS, orNone(mappings));
		return new MeasurementIdentity(values);
	}

	/**
	 * Returns the joined lines of a condition's value.
	 *
	 * @param lines the lines
	 * @return the lines, or {@code none} if there are none, because a recorded value is never blank
	 */
	private static String orNone(StringJoiner lines)
	{
		if (lines.length() == 0)
			return "none";
		return lines.toString();
	}

	/**
	 * Returns the material conditions that both identities record with different values, for the whole campaign or
	 * for the same cell.
	 *
	 * @param other another identity
	 * @return the differing conditions, in declaration order
	 * @throws NullPointerException if {@code other} is null
	 */
	public List<MeasurementCondition> differences(MeasurementIdentity other)
	{
		requireThat(other, "other").isNotNull();
		return compare(other, (value, otherValue) -> value != null && otherValue != null && !value.equals(otherValue));
	}

	/**
	 * Returns the material conditions recorded by only one identity, for the whole campaign or for the same cell.
	 * Conditions absent from both identities are excluded.
	 *
	 * @param other another identity
	 * @return the conditions with missing data, in declaration order
	 * @throws NullPointerException if {@code other} is null
	 */
	public List<MeasurementCondition> missing(MeasurementIdentity other)
	{
		requireThat(other, "other").isNotNull();
		return compare(other, (value, otherValue) -> (value == null) != (otherValue == null));
	}

	/**
	 * Returns the material conditions that this identity records and {@code other} does not, for the whole campaign or
	 * for the same cell.
	 *
	 * @param other another identity
	 * @return the conditions that {@code other} lacks, in declaration order
	 * @throws NullPointerException if {@code other} is null
	 */
	public List<MeasurementCondition> unobservedBy(MeasurementIdentity other)
	{
		requireThat(other, "other").isNotNull();
		return compare(other, (value, otherValue) -> value != null && otherValue == null);
	}

	/**
	 * Returns the material conditions whose recorded values are not equal, including those that only one identity
	 * records.
	 *
	 * @param other another identity
	 * @return the conditions, in declaration order
	 * @throws NullPointerException if {@code other} is null
	 */
	public List<MeasurementCondition> changes(MeasurementIdentity other)
	{
		requireThat(other, "other").isNotNull();
		return compare(other, (value, otherValue) -> !Objects.equals(value, otherValue));
	}

	/**
	 * Returns the material conditions for which a comparison of the two identities' values holds, for the whole
	 * campaign or for any cell.
	 *
	 * @param other another identity
	 * @param differs compares this identity's value of a condition with {@code other}'s; a value that is not
	 *   recorded is {@code null}
	 * @return the conditions, in declaration order
	 */
	private List<MeasurementCondition> compare(MeasurementIdentity other, BiPredicate<String, String> differs)
	{
		Set<String> cellIds = new HashSet<>(cells.keySet());
		cellIds.addAll(other.cells.keySet());
		List<MeasurementCondition> conditions = new ArrayList<>();
		for (MeasurementCondition condition : MeasurementCondition.values())
		{
			if (!condition.isMaterial())
				continue;
			boolean found = differs.test(values.get(condition), other.values.get(condition));
			for (String cell : cellIds)
			{
				if (found)
					break;
				found = differs.test(valueOf(cell, condition), other.valueOf(cell, condition));
			}
			if (found)
				conditions.add(condition);
		}
		return conditions;
	}

	/**
	 * Returns the value of a condition for a cell.
	 *
	 * @param cell the cell's identifier
	 * @param condition the condition
	 * @return the cell's value, the campaign-wide value if the cell records none, or {@code null} if neither is
	 *   recorded
	 */
	public String valueOf(String cell, MeasurementCondition condition)
	{
		return cells.getOrDefault(cell, Map.of()).getOrDefault(condition, values.get(condition));
	}

	/**
	 * Returns this identity without the harness-specific conditions, campaign-wide and for every cell.
	 *
	 * @return the identity that another harness shares
	 * @see MeasurementCondition#isHarnessSpecific()
	 */
	public MeasurementIdentity withoutHarnessSpecific()
	{
		Map<MeasurementCondition, String> shared = new EnumMap<>(MeasurementCondition.class);
		for (Map.Entry<MeasurementCondition, String> entry : values.entrySet())
		{
			if (!entry.getKey().isHarnessSpecific())
				shared.put(entry.getKey(), entry.getValue());
		}
		Map<String, Map<MeasurementCondition, String>> sharedCells = new HashMap<>();
		for (Map.Entry<String, Map<MeasurementCondition, String>> cell : cells.entrySet())
		{
			Map<MeasurementCondition, String> own = new EnumMap<>(MeasurementCondition.class);
			for (Map.Entry<MeasurementCondition, String> entry : cell.getValue().entrySet())
			{
				if (!entry.getKey().isHarnessSpecific())
					own.put(entry.getKey(), entry.getValue());
			}
			sharedCells.put(cell.getKey(), own);
		}
		return new MeasurementIdentity(shared, sharedCells);
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
		return merge(observed, this);
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
		return merge(this, observed);
	}

	/**
	 * Merges two identities.
	 *
	 * @param base the identity whose values apply where {@code overrides} records none
	 * @param overrides the identity whose recorded values replace matching values in {@code base}
	 * @return the merged identity
	 */
	private static MeasurementIdentity merge(MeasurementIdentity base, MeasurementIdentity overrides)
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(base.values);
		values.putAll(overrides.values);
		Map<String, Map<MeasurementCondition, String>> cells = new HashMap<>();
		for (MeasurementIdentity identity : List.of(base, overrides))
		{
			for (Map.Entry<String, Map<MeasurementCondition, String>> cell : identity.cells.entrySet())
			{
				cells.computeIfAbsent(cell.getKey(), _ -> new EnumMap<>(MeasurementCondition.class)).
					putAll(cell.getValue());
			}
		}
		return new MeasurementIdentity(values, cells);
	}
}
