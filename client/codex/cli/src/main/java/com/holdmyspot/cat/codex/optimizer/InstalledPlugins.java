package com.holdmyspot.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.BoundedInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Finds the user's installed copies of a plugin through Codex's install records.
 * <p>
 * Codex records each installed plugin as a {@code [plugins."<plugin>@<marketplace>"]} table of {@code config.toml}
 * in its home directory, and keeps the plugin's files in {@code plugins/cache/<marketplace>/<plugin>/<version>}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class InstalledPlugins
{
	/**
	 * The manifest that every Codex plugin directory holds.
	 */
	private static final Path MANIFEST = Path.of(".codex-plugin/plugin.json");
	/**
	 * The largest configuration file that is read.
	 */
	private static final int MAXIMUM_CONFIGURATION_BYTES = 1_048_576;
	/**
	 * The format of a plugin identifier, {@code <plugin>@<marketplace>}.
	 */
	private static final Pattern PLUGIN_NAME = Pattern.compile("[^@/\\\\\"]+@[^@/\\\\\"]+");

	/**
	 * Prevents construction of utility class instances.
	 */
	private InstalledPlugins()
	{
	}

	/**
	 * Returns the installed copies of a plugin.
	 *
	 * @param home the user's Codex home directory
	 * @param name the plugin's name, {@code <plugin>@<marketplace>}
	 * @return the absolute directory of each cached version that holds the plugin manifest, in path order; empty if the
	 *   configuration does not record the plugin
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code name} is not {@code <plugin>@<marketplace>}
	 * @throws IOException if the configuration exists but cannot be read or parsed, or the cache cannot be listed
	 */
	public static List<Path> of(Path home, String name) throws IOException
	{
		requireThat(home, "home").isNotNull();
		requireThat(name, "name").matches(PLUGIN_NAME.pattern());
		if (!registrations(home).path(name).isObject())
			return List.of();

		int separator = name.indexOf('@');
		Path versions = home.resolve("plugins/cache").resolve(name.substring(separator + 1)).
			resolve(name.substring(0, separator));
		if (!Files.isDirectory(versions))
			return List.of();
		List<Path> copies = new ArrayList<>();
		try (Stream<Path> entries = Files.list(versions))
		{
			for (Path version : entries.sorted().toList())
			{
				if (Files.isRegularFile(version.resolve(MANIFEST)))
					copies.add(version.toAbsolutePath().normalize());
			}
		}
		return copies;
	}

	/**
	 * Returns the name of every plugin that the configuration records.
	 *
	 * @param home the user's Codex home directory
	 * @return each name of a {@code [plugins."<plugin>@<marketplace>"]} table, once, in file order; empty if the
	 *   configuration does not exist
	 * @throws NullPointerException if {@code home} is null
	 * @throws IOException          if the configuration exists but cannot be read or parsed
	 */
	public static List<String> names(Path home) throws IOException
	{
		requireThat(home, "home").isNotNull();
		return registrations(home).properties().stream().filter(entry -> entry.getValue().isObject()).
			map(Entry::getKey).filter(name -> PLUGIN_NAME.matcher(name).matches()).toList();
	}

	/**
	 * Returns the parsed plugin registrations of a Codex home in configuration order.
	 *
	 * @param home the Codex home directory
	 * @return the plugin registrations, or a missing node
	 * @throws IOException if the configuration exists but cannot be read or parsed
	 */
	private static JsonNode registrations(Path home) throws IOException
	{
		Path configuration = new CodexInstallation(home.toAbsolutePath()).configuration();
		if (!Files.isRegularFile(configuration))
			return MissingNode.getInstance();
		String text = BoundedInput.readUtf8(configuration, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
		try
		{
			return TomlMapper.builder().build().readTree(text).path("plugins");
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot parse Codex configuration from " + configuration, e);
		}
	}
}
