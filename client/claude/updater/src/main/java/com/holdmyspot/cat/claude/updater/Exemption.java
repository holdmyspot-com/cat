package com.holdmyspot.cat.claude.updater;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.updater.cli.SandboxExemption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Supplies Claude Code's shell-sandbox and permission entries for the trusted updater.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class Exemption implements SandboxExemption
{
	/**
	 * Creates a provider for service loading.
	 */
	public Exemption()
	{
	}

	@Override
	public String harness()
	{
		return "claude";
	}

	/**
	 * Builds exact-command entries for the latest release and wildcard entries for a requested version.
	 *
	 * @param stableLauncher the absolute path of the installed stable updater launcher
	 * @param harnessHome    the absolute configuration directory recorded in policy
	 * @return the two sandbox exclusions and their corresponding prompt permissions
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if either path is relative
	 */
	@Override
	public Map<String, List<String>> entries(Path stableLauncher, Path harnessHome)
	{
		requireThat(stableLauncher, "stableLauncher").isAbsolute();
		requireThat(harnessHome, "harnessHome").isAbsolute();
		// Claude's wildcard classification ignores shell quotes. Escape a literal star outside the quoted path
		// so the latest-release entry remains an exact command rather than permitting another executable.
		String launcher = ShellQuoting.posix(stableLauncher.toString()).replace("*", "'\\*'");
		String latest = launcher + " update --harness claude";
		// The version entry is a matcher pattern; its escapes must preserve the shell spelling above literally.
		String version = latest.replace("\\", "\\\\").replace("*", "\\*") + " *";
		return Map.of("sandbox.excludedCommands", List.of(latest, version),
			"permissions.allow", List.of("Bash(" + latest + ")", "Bash(" + version + ")"));
	}
}
