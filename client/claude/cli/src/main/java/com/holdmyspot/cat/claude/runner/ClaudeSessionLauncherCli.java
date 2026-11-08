package com.holdmyspot.cat.claude.runner;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;

import com.holdmyspot.cat.claude.policy.ClaudePolicyReader;
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
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
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
			RequestedSessionStarter starter = ClaudeRequestServerCli.starter(scope, artifact, environment);
			ParentPolicySource policy = ClaudeRequestServerCli.policySource(new ClaudePolicyReader(processes),
				() -> ClaudeSession.fromHookEnvironment(environment, home, user));
			status = SessionLauncherService.serve(new SessionLauncherChannel(home), Path.of(args[0]), HARNESS_ID,
				_ -> policy, starter, new ProcessHandleTable(processes), scope.fileLocks(), Clock.systemUTC(),
				Executors.newCachedThreadPool(), Pause.sleeping(SessionLauncherChannel.INBOX_POLL),
				work -> Runtime.getRuntime().addShutdownHook(new Thread(work)), System.err);
		}
		System.exit(status);
	}
}
