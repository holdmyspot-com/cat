package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Dependent;

import java.util.List;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

/**
 * The dependents of the target prompt file that a control trial removes from its copy of a built plugin.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class PluginRemovals
{
	/**
	 * The removals of a trial that is not a control trial, or of a plugin with no dependents to remove.
	 */
	public static final PluginRemovals NONE = new PluginRemovals(List.of(), null);
	private final List<Dependent> dependents;
	private final JsonMapper json;

	/**
	 * Creates removals.
	 *
	 * @param dependents the dependents, each in the plugin and relative to the plugin directory
	 * @param json reads and writes hook configurations, or null if {@code dependents} is empty
	 */
	private PluginRemovals(List<Dependent> dependents, JsonMapper json)
	{
		this.dependents = List.copyOf(dependents);
		this.json = json;
	}

	/**
	 * Returns removals.
	 *
	 * @param dependents the dependents, each in the plugin and relative to the plugin directory
	 * @param json reads and writes hook configurations
	 * @return the removals
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a dependent does not lie in the plugin
	 */
	public static PluginRemovals of(List<Dependent> dependents, JsonMapper json)
	{
		requireThat(dependents, "dependents").isNotNull();
		requireThat(json, "json").isNotNull();
		for (Dependent dependent : dependents)
		{
			if (dependent.location() != Dependent.Location.PLUGIN)
				throw new IllegalArgumentException("The dependent does not lie in the plugin: " + dependent);
		}
		if (dependents.isEmpty())
			return NONE;
		return new PluginRemovals(dependents, json);
	}

	/**
	 * Returns the dependents.
	 *
	 * @return the dependents, each in the plugin and relative to the plugin directory
	 */
	public List<Dependent> dependents()
	{
		return dependents;
	}

	/**
	 * Returns the mapper that reads and writes hook configurations.
	 *
	 * @return the mapper, or empty if there are no dependents
	 */
	public Optional<JsonMapper> json()
	{
		return Optional.ofNullable(json);
	}

	/**
	 * Indicates whether there is nothing to remove.
	 *
	 * @return {@code true} if there are no dependents
	 */
	public boolean isEmpty()
	{
		return dependents.isEmpty();
	}

	@Override
	public boolean equals(Object obj)
	{
		return obj instanceof PluginRemovals other && other.dependents.equals(dependents);
	}

	@Override
	public int hashCode()
	{
		return dependents.hashCode();
	}

	@Override
	public String toString()
	{
		return "PluginRemovals" + dependents;
	}
}
