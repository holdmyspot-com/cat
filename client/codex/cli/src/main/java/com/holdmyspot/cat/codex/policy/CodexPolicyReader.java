package com.holdmyspot.cat.codex.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.codex.optimizer.RolloutInterruptSignal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the sandbox policy of the Codex 0.160.0 session that runs a hook.
 * <p>
 * The reader rebuilds the policy from the nearest Codex process's command line and Codex's configuration files. It
 * then narrows it by the policy that Codex recorded for the latest turn of the session and, when a subagent thread
 * asks, of that thread. So a record can only narrow what the configuration allows, and a thread identifier that does
 * not name a thread of this session changes nothing.
 * <p>
 * Every file that the policy comes from must lie outside what the session's commands may write, so that they cannot
 * have changed it.
 */
public final class CodexPolicyReader
{
	/**
	 * The form of a Codex thread identifier.
	 */
	private static final Pattern THREAD_ID = Pattern.compile(
		"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
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
	 * Reads the policy.
	 *
	 * @param session  the session
	 * @param threadId the {@code CODEX_THREAD_ID} of the thread that asks, or empty for the session's main thread
	 * @return the policy
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if a file cannot be read
	 * @throws PolicyRefusal        if the policy cannot be read safely, or the session's network is off
	 */
	public ParentPolicy read(CodexSession session, Optional<String> threadId) throws IOException, PolicyRefusal
	{
		requireThat(session, "session").isNotNull();
		requireThat(threadId, "threadId").isNotNull();
		CodexLaunch launch = CodexCommandLine.parse(codexArguments(session));
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
		CodexConfiguration configuration = CodexConfiguration.load(systemFiles, codexHome, session.cwd(), launch);
		CodexPermissions.Resolved resolved = CodexPermissions.resolve(configuration, launch, session.cwd(),
			session.environment());
		ParentPolicy rebuilt = resolved.sandbox().toParentPolicy();

		List<ParentPolicy> claims = new ArrayList<>();
		claims.add(rebuilt);
		List<Path> sources = new ArrayList<>(configuration.sources());
		ParentPolicy policy = rebuilt;
		Optional<Path> transcript = session.transcript().filter(Files::isRegularFile);
		if (transcript.isPresent())
		{
			sources.add(transcript.get());
			Optional<CodexSandbox> turn = CodexRollout.latestTurn(transcript.get(), session.environment());
			if (turn.isPresent())
			{
				ParentPolicy recorded = turn.get().toParentPolicy();
				claims.add(recorded);
				policy = policy.intersect(recorded);
			}
		}
		Optional<Path> threadRollout = threadRollout(codexHome, session, threadId);
		if (threadRollout.isPresent())
		{
			sources.add(threadRollout.get());
			Optional<CodexSandbox> turn = CodexRollout.latestTurn(threadRollout.get(), session.environment());
			if (turn.isPresent())
			{
				ParentPolicy recorded = turn.get().toParentPolicy();
				claims.add(recorded);
				policy = policy.intersect(recorded);
			}
		}
		// Each claim describes what the session's commands may write; a source that any of them may write is suspect
		for (ParentPolicy claim : claims)
		{
			for (Path source : sources)
				claim.requireTrusted(source);
		}
		if (policy.sandboxed() && reachesNoHost(policy.hosts()))
			throw networkOff(resolved.profile());
		return policy;
	}

	/**
	 * Returns the arguments of the nearest Codex process that started the hook.
	 *
	 * @param session the session
	 * @return the arguments after the executable
	 * @throws PolicyRefusal if no ancestor is a Codex process, or its command line cannot be read
	 */
	private static List<String> codexArguments(CodexSession session) throws PolicyRefusal
	{
		for (ProcessDescription process : session.ancestors())
		{
			String executable = process.command().orElse("");
			if (!isCodex(executable))
				continue;
			if (process.arguments().isEmpty())
			{
				throw new PolicyRefusal("CAT cannot read the command line of the Codex process " +
					executable, "Run Codex as the same user as CAT, then start a new Codex session");
			}
			return process.arguments().get();
		}
		throw new PolicyRefusal("CAT cannot find the Codex process that runs this session among the hook's " +
			"parent processes", "Start the session with the codex command, then start a new Codex session");
	}

	/**
	 * Indicates whether an executable is Codex's.
	 *
	 * @param executable the path of the executable
	 * @return {@code true} if its name is {@code codex} or {@code codex.exe}
	 */
	private static boolean isCodex(String executable)
	{
		String name = executable.replaceAll("^.*[/\\\\]", "");
		return name.equals("codex") || name.equals("codex.exe");
	}

	/**
	 * Finds the rollout of the thread that asks, if it belongs to the session.
	 *
	 * @param codexHome Codex's home
	 * @param session   the session
	 * @param threadId  the thread, or empty for the main thread
	 * @return the rollout, or empty if the identifier is malformed, names the main thread, or names no thread of the
	 *         session
	 * @throws IOException if a folder or file cannot be read
	 */
	private static Optional<Path> threadRollout(Path codexHome, CodexSession session, Optional<String> threadId)
		throws IOException
	{
		if (threadId.isEmpty() || !THREAD_ID.matcher(threadId.get()).matches() ||
			threadId.get().equalsIgnoreCase(session.sessionId()))
		{
			return Optional.empty();
		}
		Optional<Path> rollout = RolloutInterruptSignal.locate(codexHome, threadId.get());
		if (rollout.isEmpty())
			return Optional.empty();
		Optional<String> owner = CodexRollout.sessionId(rollout.get());
		if (owner.filter(session.sessionId()::equals).isEmpty())
			return Optional.empty();
		return rollout;
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
