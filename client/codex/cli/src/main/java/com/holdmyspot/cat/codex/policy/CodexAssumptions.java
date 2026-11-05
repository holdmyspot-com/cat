package com.holdmyspot.cat.codex.policy;

import com.holdmyspot.cat.common.runner.policy.HostAccess;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The behaviors of Codex that {@link CodexPolicyReader} relies on although they have not been observed on every
 * platform that CAT supports.
 * <p>
 * Each one lives here so that a correction, once an observation differs, changes one place.
 */
public final class CodexAssumptions
{
	/**
	 * The subcommand of Codex's shared background server. Its command line carries none of the overrides of the
	 * sessions that it serves, and its process tree holds the commands of all of them, so a hook whose nearest Codex
	 * process runs it cannot tell which session's policy applies.
	 */
	public static final String SHARED_SERVER_SUBCOMMAND = "app-server";
	/**
	 * The {@code type} values of a {@code path} in a rollout's {@code permission_profile} entries.
	 */
	public static final Set<String> ROLLOUT_PATH_TYPES = Set.of("path", "glob_pattern", "special");
	/**
	 * The {@code kind} values of a {@code special} path in a rollout's {@code permission_profile} entries.
	 */
	public static final Set<String> ROLLOUT_SPECIAL_KINDS = Set.of("root", "minimal", "project_roots", "tmpdir",
		"slash_tmp");
	/**
	 * The {@code access} values of a rollout's {@code permission_profile} entries.
	 */
	public static final Set<String> ROLLOUT_ACCESS_MODES = Set.of("read", "write", "deny");
	/**
	 * The {@code network} values of a rollout's {@code permission_profile}. The rollout lists no network domains, so
	 * {@code enabled} counts as every host.
	 */
	public static final Set<String> ROLLOUT_NETWORK_STATES = Set.of("enabled", "restricted");
	/**
	 * The prefix of a rollout's glob pattern that stands for each of the turn's {@code workspace_roots}.
	 */
	public static final String PROJECT_ROOTS_GLOB_PREFIX = "codex-project-roots://";

	/**
	 * Prevents instantiation.
	 */
	private CodexAssumptions()
	{
	}

	/**
	 * Returns the files of the managed preferences that an administrator may push to Codex on macOS. The reader
	 * cannot parse them, so it refuses when one exists.
	 *
	 * @param user the name of the user that runs Codex
	 * @return the files
	 */
	public static List<Path> managedPreferences(String user)
	{
		Path folder = Path.of("/Library/Managed Preferences");
		if (user.isBlank() || user.contains("/"))
			return List.of(folder.resolve("com.openai.codex.plist"));
		return List.of(folder.resolve("com.openai.codex.plist"), folder.resolve(user).resolve("com.openai.codex.plist"));
	}

	/**
	 * Returns the hosts that Codex's managed network proxy lets a session's commands reach. A proxy whose profile
	 * allows no domain is taken to block every host.
	 *
	 * @param allowedDomains the domain patterns that the session's permissions profile allows and does not deny
	 * @return the hosts
	 */
	public static HostAccess managedProxyHosts(Set<String> allowedDomains)
	{
		return HostAccess.listed(allowedDomains);
	}
}
