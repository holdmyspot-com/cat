package com.holdmyspot.cat.claude.layout;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installation.HarnessInstallation;
import java.nio.file.Path;
import java.util.Map;

/**
 * Where Claude Code keeps CAT's installation. Claude Code runs a directory-source plugin in place, so each plugin
 * version lives in its own folder inside the marketplace plugin.
 *
 * @param home Claude Code's absolute configuration directory
 */
public record ClaudeInstallation(Path home) implements HarnessInstallation
{
	/**
	 * Creates Claude Code's installation.
	 *
	 * @param home Claude Code's absolute configuration directory
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	public ClaudeInstallation
	{
		requireThat(home, "home").isAbsolute();
		home = home.normalize();
	}

	/**
	 * Locates Claude Code's installation in the directory that {@code CLAUDE_CONFIG_DIR} names, or in {@code .claude}
	 * in the user's home directory when the variable is unset or empty.
	 *
	 * @param environment the environment variables
	 * @param userHome    the user's absolute home directory
	 * @return the installation
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code userHome} is relative or {@code CLAUDE_CONFIG_DIR} names a relative
	 *                                  directory
	 */
	public static ClaudeInstallation fromEnvironment(Map<String, String> environment, Path userHome)
	{
		return new ClaudeInstallation(HarnessInstallation.resolveHome(environment, "CLAUDE_CONFIG_DIR", userHome,
			Path.of(".claude")));
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
		return marketplacePlugin();
	}

	@Override
	public Path dataDirectory()
	{
		return home.resolve("plugins/data/cat-cat");
	}
}
