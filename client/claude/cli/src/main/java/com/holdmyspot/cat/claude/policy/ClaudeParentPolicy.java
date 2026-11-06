package com.holdmyspot.cat.claude.policy;

import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Reads the sandbox policy of the Claude Code session whose command runs a runner of CAT's Claude Code plugin.
 * <p>
 * The command's environment says which harness runs it and whether its sandbox does. A command that Claude Code runs
 * in its sandbox has the policy of the session's settings, as {@link ClaudePolicyReader} reads them. A command that
 * Claude Code runs outside its sandbox, or that no harness runs, is unsandboxed. A command that Codex runs is refused,
 * since only CAT's Codex plugin reads a Codex session's policy.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ClaudeParentPolicy implements ParentPolicySource
{
	/**
	 * The environment variable that Codex sets in every command that it runs.
	 */
	private static final String CODEX_THREAD_VARIABLE = "CODEX_THREAD_ID";
	private final ClaudePolicyReader reader;
	private final Map<String, String> environment;
	private final Path userHome;
	private final String userName;

	/**
	 * Creates a source.
	 *
	 * @param reader      reads a session's settings
	 * @param environment the environment of the command that runs the runner
	 * @param userHome    the user's absolute home folder
	 * @param userName    the user's login name
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code userHome} is not absolute
	 */
	public ClaudeParentPolicy(ClaudePolicyReader reader, Map<String, String> environment, Path userHome,
		String userName)
	{
		requireThat(reader, "reader").isNotNull();
		requireThat(userHome, "userHome").isAbsolute();
		requireThat(userName, "userName").isNotNull();
		this.reader = reader;
		this.environment = Map.copyOf(environment);
		this.userHome = userHome;
		this.userName = userName;
	}

	@Override
	public String harnessName()
	{
		return ClaudePolicyReader.HARNESS_NAME;
	}

	@Override
	public String permissionEntries(List<Path> writes, Set<String> hosts)
	{
		requireThat(writes, "writes").isNotNull();
		requireThat(hosts, "hosts").isNotNull();
		if (writes.isEmpty() && hosts.isEmpty())
			throw new IllegalArgumentException("writes and hosts may not both be empty");
		List<String> additions = new ArrayList<>();
		if (!writes.isEmpty())
		{
			List<String> quoted = new ArrayList<>();
			for (Path write : writes)
				quoted.add("\"" + write + "\"");
			additions.add(String.join(", ", quoted) + " to sandbox.filesystem.allowWrite");
		}
		if (!hosts.isEmpty())
		{
			List<String> quoted = new ArrayList<>();
			for (String host : new TreeSet<>(hosts))
				quoted.add("\"" + host + "\"");
			additions.add(String.join(", ", quoted) + " to sandbox.network.allowedDomains");
		}
		return "add " + String.join(" and ", additions) + " in your Claude Code settings";
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws SessionRefusedException if Codex runs the command
	 */
	@Override
	public ParentPolicy read() throws IOException, PolicyRefusal
	{
		if (!"1".equals(environment.get(ClaudeRelease.HARNESS_VARIABLE)))
		{
			String thread = environment.get(CODEX_THREAD_VARIABLE);
			if (thread != null && !thread.isBlank())
			{
				throw new SessionRefusedException("CAT cannot start the session: Codex runs this command, and CAT's " +
					"Claude Code plugin cannot read a Codex session's sandbox policy. Run the runner of CAT's Codex " +
					"plugin instead.");
			}
			return ParentPolicy.UNSANDBOXED;
		}
		if (!"1".equals(environment.get(ClaudeRelease.SANDBOX_VARIABLE)))
			return ParentPolicy.UNSANDBOXED;
		return reader.read(ClaudeSession.fromCommandEnvironment(environment, userHome, userName));
	}
}
