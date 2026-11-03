package com.holdmyspot.cat.codex.secrets;

import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.Waiting;
import java.nio.file.Path;
import java.util.Map;

/**
 * Exposes to the module's tests the tool scope of a Codex runner command whose CAT session directory decides what to
 * add to the project's {@code .gitignore} from the answers that a given Git gives.
 */
@FunctionalInterface
public interface CodexRunnerCliAccess
{
	/**
	 * Opens the tool scope of a runner command. Its CAT session directory, whose temporary directory holds the runs
	 * of a command line that names no {@code --run-root}, belongs to the session whose tool shell runs the command, in
	 * the nearest CAT project.
	 *
	 * @param workingDirectory the directory from which the command was invoked
	 * @param environment      the command's environment, whose session variable names the session
	 * @param waiting          how the command waits between the attempts of a retried operation
	 * @param git              answers what Git ignores in the project
	 * @return an open tool scope
	 * @throws NullPointerException if any argument is null
	 */
	ToolScope toolScope(Path workingDirectory, Map<String, String> environment, Waiting waiting,
		SessionIgnore.GitQueries git);
}
