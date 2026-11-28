package com.holdmyspot.cat.codex.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.installer.CodexWindowsSandbox;
import com.holdmyspot.cat.codex.installer.CodexWriteGrants;
import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.codex.optimizer.RolloutInterruptSignal;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SandboxWrites;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

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
	static final Pattern THREAD_ID = Pattern.compile(
		"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	/**
	 * The settings that choose Codex's Windows sandbox implementation, by dotted name.
	 */
	private static final List<String> SANDBOX_SELECTION = List.of("windows.sandbox", "windows.allow_mxc",
		"features.prefer_mxc");
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
	 * Reads the policy of a session that runs a command, as {@link #read(CodexSession, Optional)} does, except that a
	 * session that Codex's shared background server runs, whose command line gives no session's sandbox, has the
	 * latest turn policy that the asking thread's rollout records, and a thread whose rollout records none is refused.
	 * A command runs inside a turn, so its thread's rollout records that turn's policy before the command starts.
	 *
	 * @param session  the session
	 * @param threadId the {@code CODEX_THREAD_ID} of the thread that asks, or empty for the session's main thread
	 * @return the policy
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a source of the policy cannot be read
	 * @throws PolicyRefusal        if the policy cannot be established safely
	 */
	public ParentPolicy readForCommand(CodexSession session, Optional<String> threadId)
		throws IOException, PolicyRefusal
	{
		requireThat(session, "session").isNotNull();
		requireThat(threadId, "threadId").isNotNull();
		if (CodexCommandLine.runsSharedServer(codexArguments(session)))
			return readSharedServer(session, threadId);
		return read(session, threadId);
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
		return readPolicy(session, threadId);
	}

	/**
	 * Reads the Windows sandbox selection that wins for a session's launch: the values of the settings that choose
	 * Codex's Windows sandbox implementation, {@code [windows] sandbox}, {@code [windows] allow_mxc} and
	 * {@code [features] prefer_mxc}, in the session's merged configuration, its profile file and {@code -c} overrides
	 * included. A session that Codex's shared background server runs, whose command line names neither, takes them
	 * from the configuration files alone.
	 *
	 * @param session the session
	 * @return each setting that the configuration sets, by dotted name, and its value as TOML spells a string or a
	 *   boolean; empty if it sets none
	 * @throws NullPointerException if {@code session} is null
	 * @throws IOException          if a file cannot be read
	 * @throws PolicyRefusal        if the configuration cannot be read safely
	 */
	public Map<String, String> readSandboxSelection(CodexSession session) throws IOException, PolicyRefusal
	{
		requireThat(session, "session").isNotNull();
		List<String> arguments = codexArguments(session);
		if (CodexCommandLine.runsSharedServer(arguments))
			arguments = List.of();
		CodexLaunch launch = CodexCommandLine.parse(arguments);
		Path codexHome = CodexInstallation.resolveHome(session.environment(), session.accountHome(), session.cwd());
		JsonNode merged = CodexConfiguration.load(systemFiles, codexHome, session.cwd(), launch).merged();
		Map<String, String> selection = new TreeMap<>();
		for (String key : SANDBOX_SELECTION)
		{
			int dot = key.indexOf('.');
			JsonNode value = merged.path(key.substring(0, dot)).path(key.substring(dot + 1));
			if (value.isString() || value.isBoolean())
				selection.put(key, value.asString());
		}
		return selection;
	}

	/**
	 * Returns the recommendation of Codex's {@code mxc} Windows sandbox implementation for a session, as
	 * {@link CodexWindowsSandbox#recommendation} gives it for the session's command line. A session whose command line
	 * CAT cannot read, or that Codex's shared background server runs, has an unknown command line.
	 *
	 * @param session  the session
	 * @param platform the platform that the session runs on
	 * @return the recommendation, or empty off native Windows or when Codex applies {@code mxc} to the session
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if Codex's home cannot be resolved
	 */
	public Optional<String> sandboxRecommendation(CodexSession session, Platform platform) throws IOException
	{
		requireThat(session, "session").isNotNull();
		requireThat(platform, "platform").isNotNull();
		if (!platform.isWindows())
			return Optional.empty();
		Optional<CodexWriteGrants.Launch> launch = Optional.empty();
		try
		{
			List<String> arguments = codexArguments(session);
			if (!CodexCommandLine.runsSharedServer(arguments))
			{
				CodexLaunch parsed = CodexCommandLine.parse(arguments);
				launch = Optional.of(new CodexWriteGrants.Launch(parsed.overrides(), parsed.profile(),
					parsed.sandboxMode(), parsed.bypassSandbox(), parsed.addDirs(), parsed.ignoreUserConfig()));
			}
		}
		catch (PolicyRefusal _)
		{
			// The command line is then unknown, and so is the implementation that it selects
		}
		Path codexHome = CodexInstallation.resolveHome(session.environment(), session.accountHome(), session.cwd());
		return CodexWindowsSandbox.recommendation(platform, systemFiles.system(), codexHome, launch);
	}

	/**
	 * Reads where the session's sandbox may let commands write, with the overestimating reading that the folders-on-PATH
	 * check needs: it reports every grant that CAT cannot rule out, marks a grant possible when it cannot be sure that
	 * the sandbox applies it, and lists in the problems everything that it could not read. Unlike the reading that
	 * bounds a child session, a part of the policy that cannot be read never hides a grant: it becomes a problem, and
	 * the grants that it might have given are reported as possible.
	 *
	 * @param session  the session
	 * @param threadId the thread that asks, or empty for the session's main thread
	 * @return the grants, the denials that CAT is sure of, and the problems
	 * @throws NullPointerException if any argument is null
	 */
	public SandboxWrites readWideWrites(CodexSession session, Optional<String> threadId)
	{
		requireThat(session, "session").isNotNull();
		requireThat(threadId, "threadId").isNotNull();
		List<String> problems = new ArrayList<>();
		Optional<CodexWriteGrants.Launch> launch = Optional.empty();
		try
		{
			List<String> arguments = codexArguments(session);
			if (!CodexCommandLine.runsSharedServer(arguments))
			{
				CodexLaunch parsed = CodexCommandLine.parse(arguments);
				launch = Optional.of(new CodexWriteGrants.Launch(parsed.overrides(), parsed.profile(),
					parsed.sandboxMode(), parsed.bypassSandbox(), parsed.addDirs(), parsed.ignoreUserConfig()));
			}
		}
		catch (PolicyRefusal e)
		{
			problems.add("CAT could not read this session's command line: " + e.reason() + ", so every grant that " +
				"the configuration could give is reported as possible");
		}
		Path codexHome = session.accountHome().resolve(".codex");
		try
		{
			codexHome = CodexInstallation.resolveHome(session.environment(), session.accountHome(), session.cwd());
		}
		catch (IOException | IllegalArgumentException e)
		{
			problems.add("CAT could not find Codex's home, so it read " + codexHome + ": " + e.getMessage());
		}
		List<CodexWriteGrants.Recorded> recorded = new ArrayList<>();
		List<Path> rollouts = new ArrayList<>();
		session.transcript().filter(Files::isRegularFile).ifPresent(rollouts::add);
		try
		{
			threadRollout(codexHome, session, threadId).ifPresent(rollouts::add);
		}
		catch (IOException e)
		{
			problems.add("CAT could not find the rollout of the asking thread: " + e.getMessage());
		}
		for (Path rollout : rollouts)
		{
			try
			{
				Optional<CodexSandbox> turn = CodexRollout.latestTurn(rollout, session.environment(),
					systemFiles.slashTmp());
				if (turn.isPresent())
				{
					ParentPolicy policy = turn.get().toParentPolicy();
					recorded.add(new CodexWriteGrants.Recorded(policy.sandboxed(), policy.writeRoots()));
				}
			}
			catch (IOException e)
			{
				problems.add("CAT could not read the policy that " + rollout + " records: " + e.getMessage());
			}
			catch (PolicyRefusal e)
			{
				problems.add("CAT could not read the policy that " + rollout + " records: " + e.reason());
			}
		}
		Optional<Path> slashTmp = Optional.ofNullable(systemFiles.slashTmp());
		SandboxWrites writes = CodexWriteGrants.read(new CodexWriteGrants.Session(systemFiles.system(),
			systemFiles.managedPreferences(), codexHome, session.accountHome(), session.cwd(), session.environment(),
			slashTmp, launch, recorded));
		problems.addAll(writes.problems());
		return new SandboxWrites(writes.grants(), writes.denials(), problems);
	}

	/**
	 * Reads the policy.
	 *
	 * @param session  the session
	 * @param threadId the thread that asks
	 * @return the policy
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if the policy cannot be read safely, or the session's network is off
	 */
	private ParentPolicy readPolicy(CodexSession session, Optional<String> threadId)
		throws IOException, PolicyRefusal
	{
		CodexLaunch launch = CodexCommandLine.parse(codexArguments(session));
		for (Path preferences : systemFiles.managedPreferences())
		{
			if (Files.exists(preferences))
			{
				throw new PolicyRefusal("Codex reads managed preferences from " + preferences + ", which CAT " +
					"cannot parse", "Ask the administrator who manages Codex on this machine to deliver its " +
					"configuration through " + systemFiles.system().folder() + " instead, then start a new Codex session");
			}
		}
		Path codexHome = CodexInstallation.resolveHome(session.environment(), session.accountHome(), session.cwd());
		CodexConfiguration configuration = CodexConfiguration.load(systemFiles, codexHome, session.cwd(), launch);
		CodexPermissions.Resolved resolved = CodexPermissions.resolve(configuration, launch, session.cwd(),
			session.environment(), systemFiles.slashTmp());
		ParentPolicy rebuilt = resolved.sandbox().toParentPolicy();

		List<ParentPolicy> claims = new ArrayList<>();
		claims.add(rebuilt);
		List<Path> sources = new ArrayList<>(configuration.sources());
		ParentPolicy policy = rebuilt;
		Optional<Path> transcript = session.transcript().filter(Files::isRegularFile);
		if (transcript.isPresent())
		{
			sources.add(transcript.get());
			Optional<CodexSandbox> turn = CodexRollout.latestTurn(transcript.get(), session.environment(),
				systemFiles.slashTmp());
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
			Optional<CodexSandbox> turn = CodexRollout.latestTurn(threadRollout.get(), session.environment(),
				systemFiles.slashTmp());
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
	 * Reads the policy of a session that Codex's shared background server runs. The server's command line and
	 * configuration describe none of the sessions that it serves, so the policy is the latest turn policy that the
	 * asking thread's rollout records, narrowed by the latest one that the session's rollout records.
	 *
	 * @param session  the session
	 * @param threadId the thread that asks, or empty for the session's main thread
	 * @return the policy
	 * @throws IOException   if a rollout cannot be read
	 * @throws PolicyRefusal if the asking thread's rollout records no turn policy, the policy cannot be parsed, a
	 *                       rollout is one that the policy lets the session's commands write, or the session's network
	 *                       is off
	 */
	private ParentPolicy readSharedServer(CodexSession session, Optional<String> threadId)
		throws IOException, PolicyRefusal
	{
		Path codexHome = CodexInstallation.resolveHome(session.environment(), session.accountHome(), session.cwd());
		List<Path> sources = new ArrayList<>();
		session.transcript().filter(Files::isRegularFile).ifPresent(sources::add);
		Optional<Path> threadRollout = threadRollout(codexHome, session, threadId);
		threadRollout.ifPresent(sources::add);
		Optional<Path> asking = threadRollout.or(() -> session.transcript().filter(Files::isRegularFile));
		if (asking.isEmpty())
		{
			throw new PolicyRefusal("the session runs in Codex's shared background server, whose command line does " +
				"not give its sandbox, and CAT cannot find its rollout", "Start a new Codex session");
		}
		List<ParentPolicy> claims = new ArrayList<>();
		ParentPolicy policy = null;
		for (Path source : sources)
		{
			Optional<CodexSandbox> turn = CodexRollout.latestTurn(source, session.environment(),
				systemFiles.slashTmp());
			if (turn.isEmpty())
			{
				if (source.equals(asking.get()))
				{
					throw new PolicyRefusal("the session runs in Codex's shared background server, whose command " +
						"line does not give its sandbox, and its rollout " + source + " records no turn policy",
						"Start a new Codex session");
				}
				continue;
			}
			ParentPolicy recorded = turn.get().toParentPolicy();
			claims.add(recorded);
			if (policy == null)
				policy = recorded;
			else
				policy = policy.intersect(recorded);
		}
		assert policy != null;
		for (ParentPolicy claim : claims)
		{
			for (Path source : sources)
				claim.requireTrusted(source);
		}
		if (policy.sandboxed() && reachesNoHost(policy.hosts()))
			throw networkOff(Optional.empty());
		return policy;
	}

	/**
	 * Returns the arguments of the nearest Codex process that started the hook, skipping a Codex sandbox helper whose
	 * argv[0] basename is {@code codex-linux-sandbox}.
	 *
	 * @param session the session
	 * @return the arguments after the executable
	 * @throws PolicyRefusal if no ancestor is a Codex process, or its command line cannot be read
	 */
	private static List<String> codexArguments(CodexSession session) throws PolicyRefusal
	{
		Optional<ProcessDescription> codex = codexProcess(session.ancestors());
		if (codex.isEmpty())
		{
			throw new PolicyRefusal("CAT cannot find the Codex process that runs this session among the hook's " +
				"parent processes", "Start the session with the codex command, then start a new Codex session");
		}
		ProcessDescription process = codex.get();
		if (process.arguments().isEmpty())
		{
			String executable = process.command().orElse("");
			throw new PolicyRefusal("CAT cannot read the command line of the Codex process " + executable,
				"Run Codex as the same user as CAT, then start a new Codex session");
		}
		return process.arguments().get();
	}

	/**
	 * Returns the nearest Codex process among the processes that run a command, skipping a process whose argv[0]
	 * basename is {@code codex-linux-sandbox}.
	 *
	 * @param ancestors the processes that run the command, from its parent up
	 * @return the nearest process whose executable is Codex's and whose argv[0] basename is not
	 *   {@code codex-linux-sandbox}, or empty if no such process exists
	 * @throws NullPointerException if {@code ancestors} is null
	 */
	public static Optional<ProcessDescription> codexProcess(List<ProcessDescription> ancestors)
	{
		requireThat(ancestors, "ancestors").isNotNull();
		return ancestors.stream().
			filter(process -> isCodex(process.command().orElse(""))).
			filter(process -> !isCodexSandbox(process.argumentZero().orElse(""))).
			findFirst();
	}

	/**
	 * Indicates whether argv[0] names the Codex sandbox helper.
	 *
	 * @param argumentZero the first command-line argument
	 * @return {@code true} if its basename is {@code codex-linux-sandbox}
	 */
	private static boolean isCodexSandbox(String argumentZero)
	{
		String name = argumentZero.replaceAll("^.*[/\\\\]", "");
		return name.equals("codex-linux-sandbox");
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
