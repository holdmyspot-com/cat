package com.holdmyspot.cat.claude.secrets;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart.DetachedStarter;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * Exposes to the module's tests a Claude SessionStart hook whose session directory decides what to add to the project's
 * {@code .gitignore} from the answers that a given Git gives, and which starts the session launcher of the Claude Code
 * process through a given starter as the given platform and processes decide.
 */
@FunctionalInterface
public interface ClaudeSessionStartHookAccess
{
	/**
	 * Processes one Claude SessionStart event and writes CAT's hook response.
	 *
	 * @param args             a non-null empty command-line argument array
	 * @param input            non-null harness hook input
	 * @param output           non-null harness hook output
	 * @param error            non-null diagnostic output
	 * @param environment      a non-null process environment
	 * @param workingDirectory a non-null process working directory
	 * @param waiting          how the hook waits between the attempts of a retried operation
	 * @param git              answers what Git ignores in the project
	 * @param platform         the platform that the hook runs on
	 * @param processes        the operating system's processes, which report when Claude Code's process started
	 * @param starter          starts the session launcher
	 * @throws NullPointerException if any argument is null
	 */
	void run(String[] args, InputStream input, OutputStream output, PrintStream error, Map<String, String> environment,
		Path workingDirectory, Waiting waiting, SessionIgnore.GitQueries git, Platform platform,
		HarnessProcesses processes, DetachedStarter starter);
}
