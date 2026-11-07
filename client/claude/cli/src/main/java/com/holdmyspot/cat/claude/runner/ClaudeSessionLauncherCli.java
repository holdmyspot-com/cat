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
			RequestedSessionStarter starter = (harness, arguments, directory) ->
				ClaudeRunnerCli.requestedRunner(scope, artifact, harness, environment).start(arguments, directory,
					environment);
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
			return "add " + String.join(" and ", additions) + " in your Claude Code settings";
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
