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
			Path.of(".codex")));
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
}
