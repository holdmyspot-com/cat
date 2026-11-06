package com.holdmyspot.cat.codex.secrets;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart.DetachedStarter;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Exposes to the module's tests a Codex SessionStart hook whose session directory decides what to add to the project's
 * {@code .gitignore} from the answers that a given Git gives, and which starts the session launcher of the Codex
 * process through a given starter as the given platform and ancestry decide.
 */
@FunctionalInterface
public interface CodexSessionStartHookAccess
{
	/**
	 * Processes one Codex SessionStart event and writes CAT's hook response.
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
	 * @param ancestry         returns the hook's ancestor processes, nearest first
	 * @param starter          starts the session launcher
	 * @throws NullPointerException if any argument is null
	 */
	void run(String[] args, InputStream input, OutputStream output, PrintStream error, Map<String, String> environment,
		Path workingDirectory, Waiting waiting, SessionIgnore.GitQueries git, Platform platform,
		Supplier<List<ProcessDescription>> ancestry, DetachedStarter starter);
}
