package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.BoundedInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
		if (!Files.isRegularFile(manifest))
		{
			throw new IOException("The plugin directory " + plugin + " has no manifest " + manifestPath + ". Name a " +
				"directory that holds the harness's plugin manifest.");
		}
		JsonNode root;
		try
		{
			root = JSON.readTree(BoundedInput.readUtf8(manifest, MAXIMUM_MANIFEST_BYTES, "Plugin manifest"));
		}
		catch (JacksonException e)
		{
			throw new IOException("The plugin manifest " + manifest + " is not valid JSON: " + e.getOriginalMessage(), e);
		}
		JsonNode name = root.get("name");
		if (!root.isObject() || name == null || !name.isString() || !NAME.matcher(name.asString()).matches())
		{
			throw new IOException("The plugin manifest " + manifest + " has the name " + name + ", but a plugin's " +
				"name must be lowercase letters, digits and hyphens that start with a letter or digit.");
		}
		return name.asString();
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private PluginManifest()
	{
	}
}
