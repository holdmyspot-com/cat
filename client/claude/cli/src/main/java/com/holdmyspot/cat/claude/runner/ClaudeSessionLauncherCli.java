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
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.launcher.ProcessHandleTable;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherService;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;

/**
 * The Claude plugin's {@code session-launcher} program, which CAT's SessionStart hook starts on the launcher folder
 * of a Claude Code process. It serves the sessions that the Claude Code session's commands request, within the
 * sandbox policy that the session's settings give, and starts each requested session through the runner that
 * {@code claude-runner} would start.
 */
public final class ClaudeSessionLauncherCli
{
	/**
	 * Returns the Claude Code session that the launcher serves.
	 */
	@FunctionalInterface
	public interface SessionSource
	{
		/**
		 * Returns the session.
		 *
		 * @return the session
		 * @throws PolicyRefusal if the hook's environment does not describe the session
		 */
		ClaudeSession session() throws PolicyRefusal;
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeSessionLauncherCli()
	{
	}

	/**
	 * Serves the Claude Code process of a launcher folder. The program inherits the hook's environment, which
	 * describes the session.
	 *
	 * @param args the launcher's folder
	 * @throws IOException if the launcher's state cannot be read or written
	 */
	public static void main(String[] args) throws IOException
	{
		if (args.length != 1)
		{
			System.err.println("Usage: session-launcher <launcher folder>");
			System.exit(SessionLauncherService.INVALID_FOLDER_STATUS);
		}
		Map<String, String> environment = Platform.current().environment(System.getenv());
		Path artifact = PluginDirectory.of(ClaudeSessionLauncherCli.class);
		Path home = Platform.current().homeDirectory(environment);
		String user = System.getProperty("user.name", "");
		HarnessProcesses processes = HarnessProcesses.system();
		int status;
		try (JvmScope scope = new MainJvmScope(environment, Pause::sleeping))
		{
			RequestedSessionStarter starter = starter(scope, artifact, environment);
			ParentPolicySource policy = policySource(new ClaudePolicyReader(processes),
				() -> ClaudeSession.fromHookEnvironment(environment, home, user));
			status = SessionLauncherService.serve(new SessionLauncherChannel(home), Path.of(args[0]), HARNESS_ID,
				_ -> policy, starter, new ProcessHandleTable(processes), scope.fileLocks(), Clock.systemUTC(),
				Executors.newCachedThreadPool(), Pause.sleeping(SessionLauncherChannel.INBOX_POLL),
				work -> Runtime.getRuntime().addShutdownHook(new Thread(work)), System.err);
		}
		System.exit(status);
	}

	/**
	 * Returns the starter of the sessions that the launcher's harness session requests, which starts the runner of the
	 * requested harness: the {@code claude-runner} of this plugin, or the {@code codex-runner} of the Codex
	 * artifact that the {@code codex-artifact} launcher finds.
	 *
	 * @param scope       the borrowed process scope, which starts each runner
	 * @param artifact    this plugin's folder
	 * @param environment the host environment, which each runner receives
	 * @return the starter
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static RequestedSessionStarter starter(JvmScope scope, Path artifact, Map<String, String> environment)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(environment, "environment").isNotNull();
		Map<String, String> copy = Map.copyOf(environment);
		return (harness, arguments, directory) -> ClaudeRunnerCli.requestedRunner(scope, artifact, harness, copy).
			start(arguments, directory, copy);
	}

	/**
	 * Returns the sandbox policy source of a Claude Code session. Its first reading is the session's snapshot; each
	 * later reading gives only what the snapshot also gives, and is refused once another process has taken the
	 * snapshot's Claude Code process identifier.
	 *
	 * @param reader   reads the session's settings
	 * @param sessions returns the session
	 * @return the source
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static ParentPolicySource policySource(ClaudePolicyReader reader, SessionSource sessions)
	{
		requireThat(reader, "reader").isNotNull();
		requireThat(sessions, "sessions").isNotNull();
		return new SessionPolicy(reader, sessions);
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
		/**
		 * The first successful reading, or {@code null} before it.
		 */
		private ClaudePolicyReading snapshot;

		/**
		 * Creates a source.
		 *
		 * @param reader   reads the session's settings
		 * @param sessions returns the session
		 */
		SessionPolicy(ClaudePolicyReader reader, SessionSource sessions)
		{
			this.reader = reader;
			this.sessions = sessions;
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
			requireThat(askingThread, "askingThread").isNotNull();
			synchronized (this)
			{
				ClaudeSession session = sessions.session();
				if (snapshot == null)
				{
					snapshot = reader.read(session);
					return snapshot.policy();
				}
				return reader.reread(session, snapshot);
			}
		}
	}
}
