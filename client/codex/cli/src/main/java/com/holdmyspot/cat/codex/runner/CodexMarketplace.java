package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.runner.LocalMarketplace;

import java.io.File;
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
	 * Copies CAT's artifact into its marketplace, where Codex's layout puts it, and replaces Codex marketplace metadata
	 * when it already exists.
	 *
	 * @param plugin the validated Codex plugin
	 * @param codex the isolated Codex installation
	 * @return the local marketplace root
	 * @throws NullPointerException if {@code plugin} or {@code codex} are null
	 * @throws IOException if the marketplace cannot be created
	 */
	static Path create(Path plugin, CodexInstallation codex) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(codex, "codex").isNotNull();
		return write(plugin, CAT, "CAT", codex.marketplaceRoot(), codex.marketplacePlugin(),
			codex.marketplaceManifest());
	}

	/**
	 * Copies a trial plugin into the marketplace that bears its name and replaces Codex marketplace metadata when it
	 * already exists.
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
		return write(plugin, name, displayName, root, root.resolve("plugins/" + name),
			root.resolve(".agents/plugins/marketplace.json"));
	}

	/**
	 * Copies a plugin into a marketplace and writes the marketplace's metadata.
	 *
	 * @param plugin the plugin directory
	 * @param name the plugin's manifest name, which also names its marketplace
	 * @param displayName the marketplace's name in Codex's interface
	 * @param root the marketplace root
	 * @param pluginFolder the marketplace's folder for the plugin, below {@code root}, which the metadata names as the
	 *   plugin's source
	 * @param metadata the marketplace's metadata file
	 * @return {@code root}
	 * @throws IOException if the marketplace cannot be created
	 */
	private static Path write(Path plugin, String name, String displayName, Path root, Path pluginFolder, Path metadata)
		throws IOException
	{
		LocalMarketplace.copy(plugin, pluginFolder);
		String source = root.relativize(pluginFolder).toString().replace(File.separatorChar, '/');
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
			\t\t\t\t"path": "./%3$s"
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
			""".formatted(name, displayName, source));
		return root;
	}
}
