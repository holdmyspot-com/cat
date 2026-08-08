package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.LocalMarketplace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates Codex's isolated local CAT marketplace.
 */
final class CodexMarketplace
{
	/**
	 * Prevents construction of utility class instances.
	 */
	private CodexMarketplace()
	{
	}

	/**
	 * Copies an artifact and replaces Codex marketplace metadata when it already exists.
	 *
	 * @param artifact the validated Codex artifact
	 * @param home the isolated Codex home directory
	 * @return the local marketplace root
	 * @throws NullPointerException if {@code artifact} or {@code home} are null
	 * @throws IOException if the marketplace cannot be created
	 */
	static Path create(Path artifact, Path home) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(home, "home").isNotNull();
		Path root = home.resolve("plugins/cat-marketplace");
		LocalMarketplace.copy(artifact, pluginRoot(home));
		Path metadata = root.resolve(".agents/plugins/marketplace.json");
		Files.createDirectories(metadata.getParent());
		Files.writeString(metadata, """
			{
			\t"name": "cat",
			\t"interface":
			\t{
			\t\t"displayName": "CAT"
			\t},
			\t"plugins":
			\t[
			\t\t{
			\t\t\t"name": "cat",
			\t\t\t"source":
			\t\t\t{
			\t\t\t\t"source": "local",
			\t\t\t\t"path": "./plugins/cat"
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
			""");
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
