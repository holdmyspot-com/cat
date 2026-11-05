package com.holdmyspot.cat.codex.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Optional;

/**
 * The parts of a Codex command line that decide the session's sandbox policy.
 *
 * @param overrides        the {@code key=value} configuration overrides, in the order that Codex applies them
 * @param profile          the profile that {@code -p} selects, or empty if none
 * @param sandboxMode      the sandbox mode that {@code -s} selects, or empty if none
 * @param bypassSandbox    {@code true} if the session runs its commands without a sandbox
 * @param addDirs          the folders that {@code --add-dir} names, as given
 * @param ignoreUserConfig {@code true} if Codex skips the user's {@code config.toml}
 */
public record CodexLaunch(List<String> overrides, Optional<String> profile, Optional<String> sandboxMode,
	boolean bypassSandbox, List<String> addDirs, boolean ignoreUserConfig)
{
	/**
	 * Creates a launch.
	 *
	 * @param overrides        the {@code key=value} configuration overrides, in the order that Codex applies them
	 * @param profile          the profile that {@code -p} selects, or empty if none
	 * @param sandboxMode      the sandbox mode that {@code -s} selects, or empty if none
	 * @param bypassSandbox    {@code true} if the session runs its commands without a sandbox
	 * @param addDirs          the folders that {@code --add-dir} names, as given
	 * @param ignoreUserConfig {@code true} if Codex skips the user's {@code config.toml}
	 * @throws NullPointerException if any argument is null
	 */
	public CodexLaunch
	{
		overrides = List.copyOf(overrides);
		requireThat(profile, "profile").isNotNull();
		requireThat(sandboxMode, "sandboxMode").isNotNull();
		addDirs = List.copyOf(addDirs);
	}
}
