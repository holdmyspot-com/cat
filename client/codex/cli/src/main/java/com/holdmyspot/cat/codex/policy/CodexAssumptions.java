package com.holdmyspot.cat.codex.policy;

import com.holdmyspot.cat.common.runner.policy.HostAccess;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.LinkedHashSet;
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
	 * allows no domain is taken to block every host. Unless the profile lets the commands bind local addresses, the
	 * proxy blocks every local or private address, so a profile's {@code localhost} and literal loopback, private,
	 * link-local and unspecified addresses are then left out. No host is looked up: an allowed host name that resolves
	 * to such an address stays listed.
	 *
	 * @param allowedDomains    the domain patterns that the session's permissions profile allows and does not deny
	 * @param allowLocalBinding {@code true} if the profile lets the commands bind local addresses
	 * @return the hosts
	 */
	public static HostAccess managedProxyHosts(Set<String> allowedDomains, boolean allowLocalBinding)
	{
		if (allowLocalBinding)
			return HostAccess.listed(allowedDomains);
		Set<String> reachable = new LinkedHashSet<>();
		for (String domain : allowedDomains)
		{
			if (!isLocal(domain))
				reachable.add(domain);
		}
		return HostAccess.listed(reachable);
	}

	/**
	 * Indicates whether a host is {@code localhost} or a literal local or private address, which Codex's managed
	 * network proxy blocks unless the profile lets the commands bind local addresses.
	 *
	 * @param host a host name or literal IP address, the latter for IPv6 optionally in brackets
	 * @return {@code true} if {@code host} is local or private
	 */
	private static boolean isLocal(String host)
	{
		if (host.equalsIgnoreCase("localhost"))
			return true;
		String literal = host;
		if (literal.startsWith("[") && literal.endsWith("]"))
			literal = literal.substring(1, literal.length() - 1);
		InetAddress address;
		try
		{
			address = InetAddress.ofLiteral(literal);
		}
		catch (IllegalArgumentException _)
		{
			// A host name rather than a literal address
			return false;
		}
		if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() ||
			address.isAnyLocalAddress())
		{
			return true;
		}
		// IPv6 unique local addresses, fc00::/7, are private too
		return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
	}
}
