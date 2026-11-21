package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One ordered launch input for a trial plugin.
 *
 * @param path the staged plugin directory
 * @param installedId the claimed source installation's exact identifier, or empty for a path-only input
 */
public record TrialPluginInput(Path path, Optional<String> installedId)
{
	/**
	 * The internal option that binds an installed identifier to its preceding plugin option.
	 */
	public static final String ID_OPTION = "--installed-plugin-id";
	/**
	 * The optional wire field whose entries correspond to the plugin path array.
	 */
	public static final String PROVENANCE_FIELD = "plugin_provenance";
	private static final Pattern ID_PART = Pattern.compile("[^@/\\\\\":\\p{Cntrl}]+");

	/**
	 * Creates an input. An installation identifier is a locator for native verification.
	 *
	 * @param path the staged plugin directory
	 * @param installedId the source installation's identifier, if supplied
	 * @throws IllegalArgumentException if the identifier cannot name a plugin and marketplace
	 */
	public TrialPluginInput
	{
		Objects.requireNonNull(path);
		Objects.requireNonNull(installedId);
		if (installedId.isPresent())
		{
			String[] parts = installedId.orElseThrow().split("@", -1);
			if (parts.length != 2 || !idPart(parts[0]) || !idPart(parts[1]))
				throw new IllegalArgumentException("Installed plugin ID must name a plugin and marketplace");
		}
	}

	/**
	 * Creates path-only inputs in the original order.
	 *
	 * @param paths the plugin paths
	 * @return the launch inputs
	 */
	public static List<TrialPluginInput> unbound(List<Path> paths)
	{
		return paths.stream().map(path -> new TrialPluginInput(path, Optional.empty())).toList();
	}

	/**
	 * Appends this input to a runner command.
	 *
	 * @param arguments the command's options
	 */
	public void appendArguments(List<String> arguments)
	{
		arguments.addAll(List.of("--plugin", path.toString()));
		installedId.ifPresent(id -> arguments.addAll(List.of(ID_OPTION, id)));
	}

	/**
	 * Binds an identifier to the most recent plugin option.
	 *
	 * @param plugins the ordered inputs parsed so far
	 * @param id the claimed installation identifier
	 * @throws InvalidArgumentsException if the identifier is invalid or there is no unbound plugin to attach it to
	 */
	public static void bindLast(List<TrialPluginInput> plugins, String id)
	{
		if (plugins.isEmpty() || plugins.getLast().installedId().isPresent())
			throw new InvalidArgumentsException(ID_OPTION + " must follow its --plugin option once");
		try
		{
			plugins.set(plugins.size() - 1, new TrialPluginInput(plugins.getLast().path(), Optional.of(id)));
		}
		catch (IllegalArgumentException e)
		{
			throw new InvalidArgumentsException(e.getMessage());
		}
	}

	/**
	 * Writes provenance only when an input has an identifier. Unbound entries preserve later positions.
	 *
	 * @param plugins the ordered inputs
	 * @param destination the request carrying the plugin paths
	 */
	public static void writeProvenance(List<TrialPluginInput> plugins, ObjectNode destination)
	{
		if (plugins.stream().noneMatch(plugin -> plugin.installedId().isPresent()))
			return;
		ArrayNode provenance = destination.putArray(PROVENANCE_FIELD);
		for (TrialPluginInput plugin : plugins)
		{
			if (plugin.installedId().isPresent())
				provenance.add(plugin.installedId().orElseThrow());
			else
				provenance.addNull();
		}
	}

	/**
	 * Resolves a wire provenance field against its ordered plugin directories.
	 *
	 * @param paths the plugin paths, after any archive extraction
	 * @param provenance the optional wire field
	 * @return the ordered launch inputs
	 * @throws IOException if a supplied field cannot bind exactly one entry per path
	 */
	public static List<TrialPluginInput> readProvenance(List<Path> paths, JsonNode provenance) throws IOException
	{
		if (provenance.isMissingNode())
			return unbound(paths);
		if (!provenance.isArray() || provenance.size() != paths.size())
			throw new IOException(PROVENANCE_FIELD + " must contain one entry per plugin");
		List<TrialPluginInput> plugins = new ArrayList<>(paths.size());
		for (int index = 0; index < paths.size(); ++index)
		{
			JsonNode source = provenance.path(index);
			if (!source.isNull() && !source.isString())
				throw new IOException(PROVENANCE_FIELD + " entries must be installed IDs or null");
			try
			{
				plugins.add(new TrialPluginInput(paths.get(index), Optional.ofNullable(source.stringValue())));
			}
			catch (IllegalArgumentException e)
			{
				throw new IOException("Invalid " + PROVENANCE_FIELD + ": " + e.getMessage(), e);
			}
		}
		return List.copyOf(plugins);
	}

	/**
	 * Checks that a component can identify an installation name without leaving its cache directory.
	 *
	 * @param part a plugin or marketplace name
	 * @return whether the component is suitable as a cache directory name
	 */
	private static boolean idPart(String part)
	{
		return !part.isBlank() && !part.equals(".") && !part.equals("..") && ID_PART.matcher(part).matches();
	}
}
