package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.LocalMarketplace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates Codex's isolated local marketplaces: one per installed plugin, named after the plugin.
 */
final class CodexMarketplace
{
	/**
	 * CAT's plugin name.
	 */
	static final String CAT = "cat";

	/**
	 * Prevents construction of utility class instances.
	 */
	private CodexMarketplace()
	{
	}

	/**
	 * Copies CAT's artifact into its marketplace and replaces Codex marketplace metadata when it already exists.
	 *
	 * @param plugin the validated Codex plugin
	 * @param home the isolated Codex home directory
	 * @return the local marketplace root
	 * @throws NullPointerException if {@code plugin} or {@code home} are null
	 * @throws IOException if the marketplace cannot be created
	 */
	static Path create(Path plugin, Path home) throws IOException
	{
		return create(plugin, CAT, "CAT", home);
	}

	/**
	 * Copies a plugin into the marketplace that bears its name and replaces Codex marketplace metadata when it already
	 * exists.
	 *
	 * @param plugin the plugin directory
	 * @param name the plugin's manifest name, which also names its marketplace
	 * @param displayName the marketplace's name in Codex's interface
	 * @param home the isolated Codex home directory
	 * @return the local marketplace root
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code name} or {@code displayName} are blank
	 * @throws IOException if the marketplace cannot be created
	 */
	static Path create(Path plugin, String name, String displayName, Path home) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(name, "name").isNotBlank();
		requireThat(displayName, "displayName").isNotBlank();
		requireThat(home, "home").isNotNull();
		Path root = home.resolve("plugins/" + name + "-marketplace");
		LocalMarketplace.copy(plugin, root.resolve("plugins/" + name));
		Path metadata = root.resolve(".agents/plugins/marketplace.json");
		Files.createDirectories(metadata.getParent());
		Files.writeString(metadata, """
			{
			\t"name": "%1$s",
			\t"interface":
			\t{
			\t\t"displayName": "%2$s"
			\t},
			\t"plugins":
			\t[
			\t\t{
			\t\t\t"name": "%1$s",
			\t\t\t"source":
			\t\t\t{
			\t\t\t\t"source": "local",
			\t\t\t\t"path": "./plugins/%1$s"
			\t\t\t},
			\t\t\t"policy":
			\t\t\t{
			\t\t\t\t"installation": "INSTALLED_BY_DEFAULT",
			\t\t\t\t"authentication": "ON_INSTALL"
			\t\t\t},
			\t\t\t"category": "Productivity"
			\t\t}
			\t]
			}
			""".formatted(name, displayName));
		return root;
	}

	/**
	 * Returns the location where the isolated marketplace installs CAT.
	 *
	 * @param home the isolated Codex home directory
	 * @return the installed CAT plugin root
	 */
	static Path pluginRoot(Path home)
	{
		requireThat(home, "home").isNotNull();
		return home.resolve("plugins/cat-marketplace/plugins/cat");
	}
}
