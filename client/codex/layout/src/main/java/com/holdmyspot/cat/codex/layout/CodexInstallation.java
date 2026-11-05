package com.holdmyspot.cat.codex.layout;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installation.HarnessInstallation;
import java.nio.file.Path;
import java.util.Map;

/**
 * Where Codex keeps CAT's installation. Codex copies the marketplace plugin into its plugin cache, one folder per
 * plugin version.
 *
 * @param home Codex's absolute home directory
 */
public record CodexInstallation(Path home) implements HarnessInstallation
{
	/**
	 * The name of Codex's home directory, relative to the directory that holds it.
	 */
	private static final Path DEFAULT_HOME = Path.of(".codex");
	/**
	 * The name of Codex's configuration file, relative to Codex's home.
	 */
	private static final Path CONFIGURATION = Path.of("config.toml");

	/**
	 * Creates Codex's installation.
	 *
	 * @param home Codex's absolute home directory
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	public CodexInstallation
	{
		requireThat(home, "home").isAbsolute();
		home = home.normalize();
	}

	/**
	 * Locates Codex's installation in the directory that {@code CODEX_HOME} names, or in {@code .codex} in the user's
	 * home directory when the variable is unset or empty.
	 *
	 * @param environment the environment variables
	 * @param userHome    the user's absolute home directory
	 * @return the installation
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code userHome} is relative or {@code CODEX_HOME} names a relative
	 *                                  directory
	 */
	public static CodexInstallation fromEnvironment(Map<String, String> environment, Path userHome)
	{
		return new CodexInstallation(HarnessInstallation.resolveHome(environment, "CODEX_HOME", userHome,
			DEFAULT_HOME));
	}

	/**
	 * Returns the name of Codex's home directory when {@code CODEX_HOME} does not move it.
	 *
	 * @return {@code .codex}, relative to the directory that holds Codex's home, such as the user's home directory
	 */
	public static Path defaultHome()
	{
		return DEFAULT_HOME;
	}

	/**
	 * Returns where Codex's configuration file is, relative to the directory that holds {@link #defaultHome()}. A
	 * project directory holds a {@code .codex} folder of the same layout, so this is also the project's Codex
	 * configuration.
	 *
	 * @return {@code .codex/config.toml}
	 */
	public static Path defaultConfiguration()
	{
		return DEFAULT_HOME.resolve(CONFIGURATION);
	}

	@Override
	public Path marketplaceRoot()
	{
		return home.resolve("plugins/cat-marketplace");
	}

	@Override
	public Path marketplacePlugin()
	{
		return marketplaceRoot().resolve("plugins/cat");
	}

	@Override
	public Path cacheRoot()
	{
		return home.resolve("plugins/cache");
	}

	@Override
	public Path versionFolders()
	{
		return cacheRoot().resolve("cat/cat");
	}

	@Override
	public Path dataDirectory()
	{
		return home.resolve("plugins/data/cat-cat");
	}

	/**
	 * Returns the manifest of CAT's marketplace, which Codex reads to find CAT's plugin.
	 *
	 * @return {@code .agents/plugins/marketplace.json} in the marketplace folder
	 */
	public Path marketplaceManifest()
	{
		return marketplaceRoot().resolve(".agents/plugins/marketplace.json");
	}

	/**
	 * Returns Codex's custom agent folder, which holds CAT's agents beside the user's.
	 *
	 * @return {@code agents} in Codex's home
	 */
	public Path agents()
	{
		return home.resolve("agents");
	}

	/**
	 * Returns Codex's execution-policy rules folder, which holds the rules that CAT adds beside the user's.
	 *
	 * @return {@code rules} in Codex's home
	 */
	public Path rules()
	{
		return home.resolve("rules");
	}

	/**
	 * Returns Codex's configuration file.
	 *
	 * @return {@code config.toml} in Codex's home
	 */
	public Path configuration()
	{
		return home.resolve(CONFIGURATION);
	}
}
