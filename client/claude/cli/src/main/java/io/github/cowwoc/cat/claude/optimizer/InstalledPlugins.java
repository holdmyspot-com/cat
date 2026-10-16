package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.BoundedInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the user's installed copies of a plugin through Claude Code's install records.
 * <p>
 * Claude Code records each installed plugin in {@code plugins/installed_plugins.json} under its configuration
 * directory, keyed by {@code <plugin>@<marketplace>}, with one entry per scope that installed it.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class InstalledPlugins
{
	/**
	 * The install records, relative to the configuration directory.
	 */
	private static final Path RECORDS = Path.of("plugins/installed_plugins.json");
	/**
	 * The manifest that every Claude Code plugin directory holds.
	 */
	private static final Path MANIFEST = Path.of(".claude-plugin/plugin.json");
	/**
	 * The version of the install records' format that this class reads.
	 */
	private static final int RECORDS_VERSION = 2;
	/**
	 * The largest install records file that is read.
	 */
	private static final int MAXIMUM_RECORDS_BYTES = 1_048_576;

	/**
	 * Prevents construction of utility class instances.
	 */
	private InstalledPlugins()
	{
	}

	/**
	 * Returns the installed copies of a plugin.
	 *
	 * @param configuration the user's Claude Code configuration directory
	 * @param name the plugin's name, {@code <plugin>@<marketplace>}
	 * @param json the JSON mapper
	 * @return the absolute directory of each distinct copy that the records name and that holds the plugin manifest, in
	 *   record order; empty if the records do not exist or name none
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code name} is blank
	 * @throws IOException if the records exist but cannot be read, are not valid JSON or have another format version
	 */
	public static List<Path> of(Path configuration, String name, JsonMapper json) throws IOException
	{
		requireThat(configuration, "configuration").isNotNull();
		requireThat(name, "name").isNotBlank();
		requireThat(json, "json").isNotNull();
		Path records = configuration.resolve(RECORDS);
		if (!Files.isRegularFile(records))
			return List.of();
		JsonNode root;
		try
		{
			root = json.readTree(BoundedInput.readUtf8(records, MAXIMUM_RECORDS_BYTES, "Claude Code install records"));
		}
		catch (JacksonException e)
		{
			throw new IOException("The install records " + records + " are not valid JSON", e);
		}
		int version = root.path("version").asInt(-1);
		if (version != RECORDS_VERSION)
		{
			throw new IOException("The install records " + records + " have the format version " +
				root.path("version") + "; this release reads version " + RECORDS_VERSION);
		}

		Set<Path> copies = new LinkedHashSet<>();
		for (JsonNode entry : root.path("plugins").path(name))
		{
			String installPath = entry.path("installPath").asString("");
			if (installPath.isBlank())
				continue;
			Path copy;
			try
			{
				copy = Path.of(installPath);
			}
			catch (InvalidPathException _)
			{
				// A record that names no valid path names no copy that trials could install
				continue;
			}
			if (copy.isAbsolute() && Files.isRegularFile(copy.resolve(MANIFEST)))
				copies.add(copy.normalize());
		}
		return List.copyOf(copies);
	}
}
