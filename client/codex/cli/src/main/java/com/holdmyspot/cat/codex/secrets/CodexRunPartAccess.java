package com.holdmyspot.cat.codex.secrets;

import com.holdmyspot.cat.codex.CodexRunPart;
import com.holdmyspot.cat.codex.CommandShell;
import com.holdmyspot.cat.common.Waiting;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Exposes what {@link CodexRunPart} decides before and after it runs a command to the module's tests.
 */
public interface CodexRunPartAccess
{
	/**
	 * Creates the empty file that saves a command's output, in a new folder of its own.
	 *
	 * @param workingDirectory the folder that the command runs in
	 * @param environment      the environment of the JVM
	 * @param error            receives the notice about the project's {@code .gitignore} when creating the CAT
	 *                         session directory changed that file or could not
	 * @param waiting          how the command waits between the attempts of a retried operation
	 * @return the file, or an empty {@code Optional} if the folder or the file cannot be created
	 */
	Optional<Path> createSavedOutput(Path workingDirectory, Map<String, String> environment, PrintStream error,
		Waiting waiting);

	/**
	 * Returns the command that the shell runs.
	 *
	 * @param command the command as written
	 * @param shell   the shell that runs the command
	 * @param saved   {@code true} if the command's output is saved
	 * @return the PowerShell {@code rg} search with each line cut to its first 500 bytes when its output is saved;
	 *   otherwise {@code command}
	 */
	String commandToRun(String command, CommandShell shell, boolean saved);

	/**
	 * Saves the exit status of a command whose output was saved, then prints the output's first part.
	 *
	 * @param saved       the file that saves the command's output
	 * @param status      the command's exit status
	 * @param lineLimit   the most lines that the agent receives from a PowerShell {@code rg} search, or 0 for a
	 *                    command whose output is not limited
	 * @param shell       the shell that runs the commands that {@code run-part} prints
	 * @param readPart    the {@code read-part} launcher that the line that ends a part names
	 * @param environment the environment of the JVM
	 * @param output      receives the part
	 * @param error       receives diagnostics
	 * @return the exit status of {@code read-part}
	 * @throws IOException if the output cannot be read or replaced, or the status cannot be saved
	 */
	int printSaved(Path saved, int status, int lineLimit, CommandShell shell, Path readPart,
		Map<String, String> environment, OutputStream output, PrintStream error) throws IOException;

	/**
	 * Copies the standard output of a command whose output cannot be saved, unchanged.
	 *
	 * @param commandOutput the command's standard output
	 * @param output        receives the command's standard output
	 * @return {@code true} if the standard output is empty or ends with a line feed
	 * @throws IOException if the output cannot be read or written
	 */
	boolean copyOutput(InputStream commandOutput, OutputStream output) throws IOException;

	/**
	 * Returns the text that ends the standard output of {@code run-part} when it cannot save the output.
	 *
	 * @param endsWithLine {@code true} if the command's standard output is empty or ends with a line feed
	 * @return the line {@code [End of output]}, after a line feed if {@code endsWithLine} is {@code false}
	 */
	String unsavedEnd(boolean endsWithLine);
}
