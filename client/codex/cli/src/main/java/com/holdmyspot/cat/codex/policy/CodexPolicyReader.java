package com.holdmyspot.cat.codex.policy;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Reads the sandbox policy of the Codex 0.160.0 thread that runs a command.
 * <p>
 * The reader rebuilds the policy from Codex's configuration files. It then narrows it by the policy that Codex
 * recorded for the thread's latest turn, so a record can only narrow what the configuration allows.
 */
public final class CodexPolicyReader
{
	private final CodexSystemFiles systemFiles;

	/**
	 * Creates a reader.
	 *
	 * @param systemFiles the machine-wide files that Codex reads
	 * @throws NullPointerException if {@code systemFiles} is null
	 */
	public CodexPolicyReader(CodexSystemFiles systemFiles)
	{
		requireThat(systemFiles, "systemFiles").isNotNull();
		this.systemFiles = systemFiles;
	}

	/**
	 * Reads the policy of the thread that runs a command, from Codex's configuration files, narrowed by the policy of
	 * the thread's latest turn that its rollout records. A command cannot see the Codex process, so the reader assumes
	 * that Codex's command line sets no sandbox option.
	 *
	 * @param session the session
	 * @return the policy
	 * @throws NullPointerException if {@code session} is null
	 * @throws IOException          if a file cannot be read
	 * @throws PolicyRefusal        if the policy cannot be read safely, or the session's network is off
	 */
	public ParentPolicy read(CodexSession session) throws IOException, PolicyRefusal
	{
		requireThat(session, "session").isNotNull();
		for (Path preferences : systemFiles.managedPreferences())
		{
			if (Files.exists(preferences))
			{
				throw new PolicyRefusal("Codex reads managed preferences from " + preferences + ", which CAT " +
					"cannot parse", "Ask the administrator who manages Codex on this machine to deliver its " +
					"configuration through /etc/codex instead, then start a new Codex session");
			}
		}
		Path codexHome = CodexInstallation.resolveHome(session.environment(), session.accountHome(), session.cwd());
		CodexConfiguration configuration = CodexConfiguration.load(systemFiles, codexHome, session.cwd());
		CodexPermissions.Resolved resolved = CodexPermissions.resolve(configuration, session.cwd(),
			session.environment(), systemFiles.slashTmp());
		ParentPolicy policy = resolved.sandbox().toParentPolicy();
		Optional<CodexSandbox> turn = CodexRollout.latestTurn(session.rollout(), session.environment(),
			systemFiles.slashTmp());
		if (turn.isPresent())
			policy = policy.intersect(turn.get().toParentPolicy());
		if (policy.sandboxed() && reachesNoHost(policy.hosts()))
			throw networkOff(resolved.profile());
		return policy;
	}

	/**
	 * Indicates whether an access reaches no host.
	 *
	 * @param hosts the access
	 * @return {@code true} if it reaches none
	 */
	private static boolean reachesNoHost(HostAccess hosts)
	{
		return hosts.equals(HostAccess.NONE) ||
			(hosts instanceof HostAccess.ListedHosts listed && listed.patterns().isEmpty());
	}

	/**
	 * Returns the refusal of a session whose network is off.
	 *
	 * @param profile the user permissions profile that the session uses, or empty if none
	 * @return the refusal
	 */
	private static PolicyRefusal networkOff(Optional<String> profile)
	{
		String name = profile.orElse("<profile>");
		return new PolicyRefusal("the Codex sandbox that runs it has network access off, so the session could " +
			"not reach its model", "Set sandbox_workspace_write.network_access = true in Codex's config.toml, or " +
			"[permissions." + name + ".network] enabled = true if the session uses the permissions profile " + name +
			", and start a new Codex session");
	}
}
