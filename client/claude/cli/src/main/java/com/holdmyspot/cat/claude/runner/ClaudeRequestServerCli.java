package com.holdmyspot.cat.claude.runner;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.policy.ClaudePolicyReader;
import com.holdmyspot.cat.claude.policy.ClaudePolicyReading;
import com.holdmyspot.cat.claude.policy.ClaudeSession;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.ProcessTreeStop;
import com.holdmyspot.cat.common.runner.RequestServer;
import com.holdmyspot.cat.common.runner.RequestedRunner;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.launcher.ProcessHandleTable;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Claude plugin's {@code request-server} program, which a Claude Code session's agent starts in the background,
 * outside the session's sandbox, through the only sandbox exception that CAT's install adds. It serves the sessions
 * that the Claude Code session's commands request outside a nested session, within the sandbox policy that the
 * session's settings give, and starts each requested session through the runner that {@code claude-runner} or {@code
 * codex-runner} would start.
 */
public final class ClaudeRequestServerCli
{
	/**
	 * How often the server checks for requests and for the end of its session.
	 */
	private static final Duration POLL = Duration.ofMillis(100);

	/**
	 * Returns the Claude Code session that the server serves.
	 */
	@FunctionalInterface
	public interface SessionSource
	{
		/**
		 * Returns the session.
		 *
		 * @return the session
		 * @throws PolicyRefusal if the session cannot be identified
		 */
		ClaudeSession session() throws PolicyRefusal;
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeRequestServerCli()
	{
	}

	/**
	 * Serves the Claude Code session whose command runs the program until that session's Claude Code process ends,
	 * as {@link RequestServer#serve(String[], RequestServer.Host, PrintWriter)} describes.
	 * <p>
	 * If the server cannot read or answer the session's requests, it prints why, without a stack trace, and exits with
	 * status {@value HarnessRunnerOptions#FAILED_LAUNCH_STATUS}.
	 *
	 * @param args the program's arguments, which must be none
	 */
	public static void main(String[] args)
	{
		Map<String, String> environment = Platform.current().environment(System.getenv());
		Path plugin = PluginDirectory.of(ClaudeRequestServerCli.class);
		ProcessTreeStop.onExit();
		int status;
		try (JvmScope scope = new MainJvmScope(environment, Pause::sleeping))
		{
			RequestServer.Host host = new RequestServer.Host(HARNESS_ID, ClaudeRunnerCli.MARKERS.running(environment),
				refusal(Platform.current(), environment), plugin,
				ClaudeRunnerCli.installedCopies(environment),
				() -> ClaudeServedSession.sandboxed(RequestServer.program(plugin), environment),
				ClaudeServedSession.sessions(environment),
				directory -> ToolScope.forSession(directory, environment, SessionHarness.installed(), scope.waiting()),
				RequestServer.LoginServices.system(environment),
				variables -> starter(scope, plugin, environment, variables),
				new ProcessHandleTable(HarnessProcesses.system()),
				Pause.sleeping(POLL), Platform.current());
			Optional<RequestServer.ComparisonHost> comparison;
			if (Platform.current().isLinux())
				comparison = Optional.of(ClaudeCodexComparison.host(environment));
			else
				comparison = Optional.empty();
			status = HarnessRunnerOptions.reportingFailures(() -> RequestServer.serve(args, host, errors(), comparison),
				errors());
		}
		System.exit(status);
	}

	/**
	 * Returns a writer for the server's standard error that flushes each line.
	 *
	 * @return the writer
	 */
	private static PrintWriter errors()
	{
		return new PrintWriter(System.err, true, StandardCharsets.UTF_8);
	}

	/**
	 * Returns why the server must not run in a process: the process belongs to a nested session's harness session, which
	 * no request server serves. The server runs on every platform; on native Windows, where Claude Code has no
	 * sandbox, the sessions that it starts get none.
	 *
	 * @param platform    the platform that runs the server
	 * @param environment the server's environment
	 * @return the reason, or empty if the server may run
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Optional<String> refusal(Platform platform, Map<String, String> environment)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		return HarnessEnvironment.requestServerRefusal(environment);
	}

	/**
	 * Returns the starter of the sessions that the server's harness session requests, which starts the runner of the
	 * requested harness: the {@code claude-runner} of this plugin, or the {@code codex-runner} of the Codex
	 * artifact that the {@code codex-artifact} launcher finds.
	 *
	 * @param scope          the borrowed process scope, which starts each runner
	 * @param artifact       this plugin's folder
	 * @param environment    the host environment, which each runner receives without the harness markers that
	 *                       {@link ClaudeRunnerCli#MARKERS} reads, so that it is not taken for a harness session's
	 *                       launch
	 * @param loginVariables the variables through which each runner, and each runner that it starts, reaches the
	 *                       server's login service
	 * @return the starter
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static RequestedRunner.Resolver starter(JvmScope scope, Path artifact, Map<String, String> environment,
		Map<String, String> loginVariables)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(loginVariables, "loginVariables").isNotNull();
		Map<String, String> runner = new HashMap<>(ClaudeRunnerCli.MARKERS.without(environment));
		runner.putAll(loginVariables);
		Map<String, String> copy = Map.copyOf(runner);
		return harness -> ClaudeRunnerCli.requestedRunner(scope, artifact, harness, copy).select(copy);
	}

	/**
	 * Returns the sandbox policy source of a Claude Code session. Its first reading is the session's snapshot; each
	 * later reading gives only what the snapshot also gives, and is refused once another process has taken the
	 * snapshot's Claude Code process identifier.
	 *
	 * @param reader     reads the session's settings
	 * @param sessions   returns the session
	 * @param transcript the session's transcript, in which Claude Code reports that its sandbox failed to start
	 * @return the source
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static ParentPolicySource policySource(ClaudePolicyReader reader, SessionSource sessions,
		Path transcript)
	{
		requireThat(reader, "reader").isNotNull();
		requireThat(sessions, "sessions").isNotNull();
		requireThat(transcript, "transcript").isNotNull();
		return new SessionPolicy(reader, sessions, transcript);
	}

	/**
	 * The sandbox policy of a Claude Code session.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 */
	private static final class SessionPolicy implements ParentPolicySource
	{
		private final ClaudePolicyReader reader;
		private final SessionSource sessions;
		private final Path transcript;
		/**
		 * The first successful reading, or {@code null} before it.
		 */
		private ClaudePolicyReading snapshot;

		/**
		 * Creates a source.
		 *
		 * @param reader     reads the session's settings
		 * @param sessions   returns the session
		 * @param transcript the session's transcript
		 */
		SessionPolicy(ClaudePolicyReader reader, SessionSource sessions, Path transcript)
		{
			this.reader = reader;
			this.sessions = sessions;
			this.transcript = transcript;
		}

		@Override
		public String harnessName()
		{
			return ClaudePolicyReader.HARNESS_NAME;
		}

		@Override
		public String sandboxAbsence()
		{
			if (reader.platform().isWindows())
				return ClaudeSessionSandbox.trialRefusal();
			return "Prompt optimization needs a sandbox, and this Claude Code session runs its commands without one. " +
				"Trials run in the same type of sandbox as the session that starts them, so that a trial cannot read " +
				"the held-out test cases or change files outside its workspace. Set sandbox.enabled to true in your " +
				"Claude Code settings, then start a new session.";
		}

		/**
		 * {@inheritDoc}
		 * <p>
		 * Claude Code reports that its sandbox failed to start as the result of the session's first Bash call, and runs
		 * the rest of the session without it, so the server reads the report from the session's transcript.
		 */
		@Override
		public Optional<String> sandboxFailure() throws IOException
		{
			if (!Files.isRegularFile(transcript))
				return Optional.empty();
			SandboxStartReport report = new SandboxStartReport(JsonMapper.builder().build());
			try (BufferedReader lines = Files.newBufferedReader(transcript, StandardCharsets.UTF_8))
			{
				while (true)
				{
					String line = lines.readLine();
					if (line == null)
						return Optional.empty();
					Optional<String> found = report.accept(line);
					if (found.isPresent())
					{
						return Optional.of("Prompt optimization needs a sandbox, and Claude Code reported in this " +
							"session that its sandbox failed to start: \"" + found.get() + "\" Claude Code runs the rest " +
							"of that session's commands without a sandbox, so trials would run without one. Fix the cause " +
							"that the report names, then start a new session.");
					}
				}
			}
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
			String entries = "add " + String.join(" and ", additions) + " in your Claude Code settings";
			List<String> limited = portLimitedEntries(hosts);
			if (limited.isEmpty())
				return entries;
			// Without this, the user sees the entry that they wrote and the host that it names listed as missing
			return entries + " (your " + String.join(", ", limited) + " does not allow it: it limits the host to " +
				"one port, and the requested session's commands reach a host on every port)";
		}

		/**
		 * Returns the allowed domains of the session's settings that limit a host to one port and name one of
		 * {@code hosts}.
		 *
		 * @param hosts the hosts that the session's sandbox lacks
		 * @return the entries as the settings spell them, each quoted, in alphabetical order; empty before the first
		 *   reading
		 */
		private List<String> portLimitedEntries(Set<String> hosts)
		{
			Set<String> domains = Set.of();
			synchronized (this)
			{
				if (snapshot != null)
					domains = snapshot.portLimitedDomains();
			}
			List<String> entries = new ArrayList<>();
			for (String entry : new TreeSet<>(domains))
			{
				HostAccess named = HostAccess.listed(Set.of(entry.substring(0, entry.lastIndexOf(':'))));
				for (String host : hosts)
				{
					if (named.allows(host))
					{
						entries.add("\"" + entry + "\"");
						break;
					}
				}
			}
			return entries;
		}

		/**
		 * {@inheritDoc}
		 * <p>
		 * Every thread of a Claude Code session runs its commands in the session's sandbox, so the policy is the
		 * session's whichever thread asks.
		 */
		@Override
		public ParentPolicy read(Optional<String> askingThread) throws IOException, PolicyRefusal
		{
			ParentPolicy bound = ParentPolicy.UNSANDBOXED;
			for (ParentPolicy policy : readings(askingThread))
				bound = bound.intersect(policy);
			return bound;
		}

		@Override
		public List<ParentPolicy> readings(Optional<String> askingThread) throws IOException, PolicyRefusal
		{
			requireThat(askingThread, "askingThread").isNotNull();
			synchronized (this)
			{
				ClaudeSession session = sessions.session();
				if (snapshot == null)
				{
					snapshot = reader.read(session);
					return List.of(snapshot.policy());
				}
				return List.of(snapshot.policy(), reader.currentPolicy(session, snapshot));
			}
		}
	}
}
