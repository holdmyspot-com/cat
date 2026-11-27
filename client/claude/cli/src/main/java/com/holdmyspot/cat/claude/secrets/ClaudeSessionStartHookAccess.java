package com.holdmyspot.cat.claude.secrets;

import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.Waiting;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Exposes to the module's tests a Claude SessionStart hook whose session directory decides what to add to the project's
 * {@code .gitignore} from the answers that a given Git gives.
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
	 * @param processes        describes the session's Claude Code process, for the check of the folders on {@code PATH}
	 *                         that the session's sandbox lets commands write; empty to skip the check
	 * @throws NullPointerException if any argument is null
	 */
	void run(String[] args, InputStream input, OutputStream output, PrintStream error, Map<String, String> environment,
		Path workingDirectory, Waiting waiting, SessionIgnore.GitQueries git, Optional<HarnessProcesses> processes);

	/**
	 * Processes one Claude SessionStart event without the check of the folders on {@code PATH}, and writes CAT's hook
	 * response.
	 *
	 * @param args             a non-null empty command-line argument array
	 * @param input            non-null harness hook input
	 * @param output           non-null harness hook output
	 * @param error            non-null diagnostic output
	 * @param environment      a non-null process environment
	 * @param workingDirectory a non-null process working directory
	 * @param waiting          how the hook waits between the attempts of a retried operation
	 * @param git              answers what Git ignores in the project
	 * @throws NullPointerException if any argument is null
	 */
	default void run(String[] args, InputStream input, OutputStream output, PrintStream error,
		Map<String, String> environment, Path workingDirectory, Waiting waiting, SessionIgnore.GitQueries git)
	{
		run(args, input, output, error, environment, workingDirectory, waiting, git, Optional.empty());
	}
}
