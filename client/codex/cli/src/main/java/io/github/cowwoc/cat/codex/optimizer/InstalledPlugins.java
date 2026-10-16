package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.BoundedInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the user's installed copies of a plugin through Codex's install records.
 * <p>
 * Codex records each installed plugin as a {@code [plugins."<plugin>@<marketplace>"]} table of {@code config.toml}
 * in its home directory, and keeps the plugin's files in {@code plugins/cache/<marketplace>/<plugin>/<version>}.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
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
	 * @throws IOException if the configuration exists but cannot be read, or the cache cannot be listed
	 */
	public static List<Path> of(Path home, String name) throws IOException
	{
		requireThat(home, "home").isNotNull();
		requireThat(name, "name").matches("[^@/\\\\\"]+@[^@/\\\\\"]+");
		Path configuration = home.resolve("config.toml");
		if (!Files.isRegularFile(configuration))
			return List.of();
		String text = BoundedInput.readUtf8(configuration, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
		Pattern table = Pattern.compile("(?m)^\\s*\\[plugins\\.\"" + Pattern.quote(name) + "\"]\\s*(?:#.*)?$");
		if (!table.matcher(text).find())
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
}
