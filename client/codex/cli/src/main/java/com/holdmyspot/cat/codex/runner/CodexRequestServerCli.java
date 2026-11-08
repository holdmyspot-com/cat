package com.holdmyspot.cat.codex.runner;

import static com.holdmyspot.cat.codex.CodexPluginInfo.HARNESS_ID;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.ProcessTreeStop;
import com.holdmyspot.cat.common.runner.RequestServer;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.launcher.ProcessHandleTable;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Codex plugin's {@code request-server} program, which a Codex session's agent starts as a command that keeps
 * running, outside the session's sandbox, through the only rule that CAT's install adds. It serves the sessions that
 * the Codex session's commands request outside a run, within the sandbox policy that the session's settings and its
 * thread's latest turn give, and starts each requested session through the runner that {@code codex-runner} or
 * {@code claude-runner} would start.
 */
public final class CodexRequestServerCli
{
	/**
	 * How often the server checks for requests and for the end of its session.
	 */
	private static final Duration POLL = Duration.ofMillis(100);

	/**
	 * Prevents instantiation.
	 */
	private CodexRequestServerCli()
	{
	}

	/**
	 * Serves the Codex session whose command runs the program until that session's Codex process ends, as
	 * {@link RequestServer#serve(String[], RequestServer.Host, PrintWriter)} describes.
	 *
	 * @param args the program's arguments, which must be none
	 * @throws IOException if the session's requests cannot be read or answered
	 */
	public static void main(String[] args) throws IOException
	{
		Map<String, String> environment = Platform.current().environment(System.getenv());
		Path plugin = PluginDirectory.of(CodexRequestServerCli.class);
		ProcessTreeStop.onExit();
		int status;
		try (JvmScope scope = new MainJvmScope(environment, Pause::sleeping))
		{
			RequestServer.Host host = new RequestServer.Host(HARNESS_ID, CodexRunnerCli.MARKERS.running(environment),
				CodexSessionSandbox.refusal(Platform.current()), plugin,
				() -> CodexServedSession.sandboxed(RequestServer.program(plugin), environment),
				CodexServedSession.sessions(environment),
				directory -> CodexRunnerCli.toolScope(directory, environment, scope.waiting()),
				starter(scope, plugin, environment), new ProcessHandleTable(HarnessProcesses.system()),
				Pause.sleeping(POLL));
			status = RequestServer.serve(args, host, new PrintWriter(System.err, true, StandardCharsets.UTF_8));
		}
		System.exit(status);
	}

	/**
	 * Returns the starter of the sessions that the server's harness session requests, which starts the runner of the
	 * requested harness: the {@code codex-runner} of this plugin, or the {@code claude-runner} of the Claude
	 * artifact that the {@code claude-artifact} launcher finds.
	 *
	 * @param scope       the borrowed process scope, which starts each runner
	 * @param artifact    this plugin's folder
	 * @param environment the host environment, which each runner receives without the harness markers that
	 *                    {@link CodexRunnerCli#MARKERS} reads, so that it is not taken for a harness session's launch
	 * @return the starter
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static RequestedSessionStarter starter(JvmScope scope, Path artifact, Map<String, String> environment)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(environment, "environment").isNotNull();
		Map<String, String> copy = Map.copyOf(CodexRunnerCli.MARKERS.without(environment));
		return (harness, arguments, directory) -> CodexRunnerCli.requestedRunner(scope, artifact, harness, copy).
			start(arguments, directory, copy);
	}

	/**
	 * Returns the Codex settings entries that let a session's commands write paths and reach hosts, as a
	 * {@link ParentPolicySource#permissionEntries(List, Set)} names them.
	 *
	 * @param writes the paths
	 * @param hosts  the hosts
	 * @return the instruction that names the entries
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if both are empty
	 */
	public static String permissionEntries(List<Path> writes, Set<String> hosts)
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
}
