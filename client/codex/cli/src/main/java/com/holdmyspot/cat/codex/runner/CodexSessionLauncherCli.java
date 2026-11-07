package com.holdmyspot.cat.codex.runner;

import static com.holdmyspot.cat.codex.CodexPluginInfo.HARNESS_ID;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.policy.CodexPolicyReader;
import com.holdmyspot.cat.codex.policy.CodexSession;
import com.holdmyspot.cat.codex.policy.CodexSystemFiles;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.launcher.LauncherRecord;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.launcher.ProcessHandleTable;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherService;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
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
 * The Codex plugin's {@code session-launcher} program, which CAT's SessionStart hook starts on the launcher folder of
 * a Codex process. It serves the sessions that the Codex session's commands request, within the sandbox policy that
 * the Codex process's command line and the session's configuration give, and starts each requested session through
 * the runner that {@code codex-runner} would start.
 */
public final class CodexSessionLauncherCli
{
	/**
	 * The name of the harness as its user knows it, which refusals name.
	 */
	private static final String HARNESS_NAME = "Codex";

	/**
	 * Prevents instantiation.
	 */
	private CodexSessionLauncherCli()
	{
	}

	/**
	 * Serves the Codex process of a launcher folder. The program inherits the hook's environment, which Codex gave the
	 * hook.
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
		Path artifact = PluginDirectory.of(CodexSessionLauncherCli.class);
		Path home = Platform.current().homeDirectory(environment);
		Path accountHome = Path.of(System.getProperty("user.home"));
		CodexPolicyReader reader = new CodexPolicyReader(CodexSystemFiles.ofThisMachine(
			System.getProperty("user.name", "")));
		HarnessProcesses processes = HarnessProcesses.system();
		int status;
		try (JvmScope scope = new MainJvmScope(environment, Pause::sleeping))
		{
			RequestedSessionStarter starter = (harness, arguments, directory) ->
				CodexRunnerCli.requestedRunner(scope, artifact, harness, environment).start(arguments, directory,
					environment);
			status = SessionLauncherService.serve(new SessionLauncherChannel(home), Path.of(args[0]), HARNESS_ID,
				record -> policySource(reader, processes, record, environment, accountHome), starter,
				new ProcessHandleTable(processes), scope.fileLocks(), Clock.systemUTC(),
				Executors.newCachedThreadPool(), Pause.sleeping(SessionLauncherChannel.INBOX_POLL),
				work -> Runtime.getRuntime().addShutdownHook(new Thread(work)), System.err);
		}
		System.exit(status);
	}

	/**
	 * Returns the sandbox policy source of the Codex session that a hook described. Each reading takes the command
	 * line of the Codex process that the hook named, and is refused once that process has ended. A reading for a
	 * subagent thread of the session takes that thread's latest turn policy, so its child gets no more than the
	 * thread may do.
	 *
	 * @param reader      reads the session's configuration
	 * @param processes   the running processes
	 * @param record      the hook's data
	 * @param environment the hook's environment, which Codex gave the hook
	 * @param accountHome the user's home folder in the account database
	 * @return the source
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static ParentPolicySource policySource(CodexPolicyReader reader, HarnessProcesses processes,
		LauncherRecord record, Map<String, String> environment, Path accountHome)
	{
		requireThat(reader, "reader").isNotNull();
		requireThat(processes, "processes").isNotNull();
		requireThat(record, "record").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(accountHome, "accountHome").isNotNull();
		Map<String, String> copy = Map.copyOf(environment);
		return new ParentPolicySource()
		{
			@Override
			public String harnessName()
			{
				return HARNESS_NAME;
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
					List<String> entries = new ArrayList<>();
					for (Path write : writes)
						entries.add("\"" + TomlStrings.escape(write.toString()) + "\" = \"write\"");
					additions.add(String.join(" and ", entries) + " under [permissions.<profile>.filesystem]");
				}
				if (!hosts.isEmpty())
				{
					List<String> entries = new ArrayList<>();
					for (String host : new TreeSet<>(hosts))
						entries.add("\"" + TomlStrings.escape(host) + "\" = \"allow\"");
					additions.add(String.join(" and ", entries) + " under [permissions.<profile>.network.domains]");
					// Codex's managed network proxy blocks these hosts unless the profile also lets commands bind local
					// addresses, even when its domains allow them
					if (hosts.stream().anyMatch(ChildPolicy::needsLocalBinding))
						additions.add("allow_local_binding = true under [permissions.<profile>.network]");
				}
				String instruction = "add " + String.join(" and ", additions) + " in Codex's config.toml, where " +
					"<profile> is the permissions profile that default_permissions names";
				if (!writes.isEmpty())
				{
					instruction += "; a session that sets sandbox_mode instead lists the paths in " +
						"sandbox_workspace_write.writable_roots";
				}
				return instruction;
			}

			@Override
			public ParentPolicy read(Optional<String> askingThread) throws IOException, PolicyRefusal
			{
				requireThat(askingThread, "askingThread").isNotNull();
				long pid = record.process().pid();
				Optional<ProcessDescription> codex = processes.find(pid).
					filter(process -> process.start().equals(Optional.of(record.process().start())));
				if (codex.isEmpty())
				{
					throw new PolicyRefusal("the Codex process " + pid + " that started the session has ended",
						"Start a new Codex session");
				}
				CodexSession session = new CodexSession(List.of(codex.get()), copy, accountHome, record.cwd(),
					record.transcript(), record.sessionId());
				return reader.read(session, askingThread);
			}
		};
	}
}
