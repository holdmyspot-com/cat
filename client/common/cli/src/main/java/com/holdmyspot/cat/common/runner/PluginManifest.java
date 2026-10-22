package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a harness plugin's manifest.
 */
public final class PluginManifest
{
	/**
	 * The largest manifest that CAT reads.
	 */
	private static final int MAXIMUM_MANIFEST_BYTES = 1_048_576;
	/**
	 * A plugin name that names one directory and one marketplace entry.
	 */
	private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * The plugin manifest path of each harness, relative to the plugin directory.
	 */
	public static final List<Path> MANIFESTS = List.of(Path.of(".codex-plugin/plugin.json"),
		Path.of(".claude-plugin/plugin.json"));

	/**
	 * Returns the name under which a harness installs a plugin.
	 *
	 * @param plugin the plugin directory
	 * @param manifestPath the harness's plugin manifest path relative to {@code plugin}, such as
	 *   {@code .codex-plugin/plugin.json}
	 * @return the manifest's {@code name}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the manifest cannot be read, is not a JSON object, or its {@code name} is missing or is
	 *   not lowercase letters, digits and hyphens that start with a letter or digit
	 */
	public static String name(Path plugin, Path manifestPath) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(manifestPath, "manifestPath").isNotNull();
		Path manifest = plugin.resolve(manifestPath);
		JsonNode root = read(plugin, manifestPath);
		JsonNode name = root.get("name");
		if (!root.isObject() || name == null || !name.isString() || !NAME.matcher(name.asString()).matches())
		{
			throw new IOException("The plugin manifest " + manifest + " has the name " + name + ", but a plugin's " +
				"name must be lowercase letters, digits and hyphens that start with a letter or digit.");
		}
		return name.asString();
	}

	/**
	 * Returns the version that a plugin's manifest states.
	 *
	 * @param plugin       the plugin directory
	 * @param manifestPath the harness's plugin manifest path relative to {@code plugin}, such as
	 *                     {@code .codex-plugin/plugin.json}
	 * @return the manifest's {@code version}, or empty if the manifest has no non-blank string {@code version}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the manifest cannot be read or is not valid JSON
	 */
	public static Optional<String> version(Path plugin, Path manifestPath) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(manifestPath, "manifestPath").isNotNull();
		JsonNode version = read(plugin, manifestPath).get("version");
		if (version == null || !version.isString() || version.asString().isBlank())
			return Optional.empty();
		return Optional.of(version.asString());
	}

	/**
	 * Returns the repository that a plugin's manifest states.
	 *
	 * @param plugin       the plugin directory
	 * @param manifestPath the harness's plugin manifest path relative to {@code plugin}, such as
	 *                     {@code .codex-plugin/plugin.json}
	 * @return the manifest's {@code repository}, or empty if the manifest has no non-blank string {@code repository}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the manifest cannot be read or is not valid JSON
	 */
	public static Optional<String> repository(Path plugin, Path manifestPath) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(manifestPath, "manifestPath").isNotNull();
		JsonNode repository = read(plugin, manifestPath).get("repository");
		if (repository == null || !repository.isString() || repository.asString().isBlank())
			return Optional.empty();
		return Optional.of(repository.asString());
	}

	/**
	 * Reads a plugin's manifest.
	 *
	 * @param plugin       the plugin directory
	 * @param manifestPath the manifest path relative to {@code plugin}
	 * @return the manifest's JSON
	 * @throws IOException if the manifest does not exist, cannot be read or is not valid JSON
	 */
	private static JsonNode read(Path plugin, Path manifestPath) throws IOException
	{
		Path manifest = plugin.resolve(manifestPath);
		if (!Files.isRegularFile(manifest))
		{
			throw new IOException("The plugin directory " + plugin + " has no manifest " + manifestPath + ". Name a " +
				"directory that holds the harness's plugin manifest.");
		}
		try
		{
			return JSON.readTree(BoundedInput.readUtf8(manifest, MAXIMUM_MANIFEST_BYTES, "Plugin manifest"));
		}
		catch (JacksonException e)
		{
			throw new IOException("The plugin manifest " + manifest + " is not valid JSON: " + e.getOriginalMessage(), e);
		}
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private PluginManifest()
	{
	}
}
