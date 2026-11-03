package com.holdmyspot.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.secrets.CodexRunPartAccess;
import com.holdmyspot.cat.codex.secrets.CodexRunPartSecrets;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Waiting;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Saves the output of a command in a new file, then prints that file's first part the way {@code read-part} prints a
 * file: the part ends with the command that prints the next part.
 * <p>
 * Usage: {@code run-part <<'EOF'}, then the shell command on the lines that follow, as it would be written without
 * {@code run-part}, then {@code EOF}. On Windows, the command is a PowerShell here-string piped to {@code run-part}: a
 * {@code @'} line, the command, then a {@code '@ | run-part} line. It runs the command in the shell that Codex runs
 * commands in, {@code bash} or PowerShell, and saves what it writes to standard output and standard error. It saves
 * the command's exit status in {@code <file>.exit-status}, so that whichever call prints the last part,
 * {@code run-part} or {@code read-part}, exits with that status; a part that more parts follow exits with status 0.
 * The output stays inside the project: in a Codex thread, which names its ID in {@code CODEX_THREAD_ID}, it is saved
 * in the temporary folder of the thread's CAT session directory (see {@link SessionDirectory}) in the nearest CAT
 * project that contains the folder that the command runs in. Outside a CAT project, or without
 * {@code CODEX_THREAD_ID}, it is saved in the folder that {@link #fallbackFolder(Map)} returns. When the folder or
 * the file that would save the output cannot be created, as in a read-only sandbox, {@code run-part} runs the command
 * as it would run without {@code run-part}: the output is neither saved nor limited, its standard output and standard
 * error reach those of {@code run-part} unchanged, and it exits with the command's exit status. Its only line of its
 * own is {@code [End of output]}, which ends its standard output on a line of its own, so that the agent can tell the
 * command's last line from the line that ends a part.
 * <p>
 * The command receives the saved output's path in {@code CAT_RUN_PART_OUTPUT} and, in {@code bash}, finds the
 * plugin's {@code client/run-part-overrides} folder first on its {@code PATH}. A program in that folder, such as
 * {@code rg}, keeps output that it writes straight into that file small enough for the agent that reads it, and leaves
 * other output unchanged. The programs there are POSIX scripts without an extension, so a PowerShell command does not
 * find the folder: PowerShell resolves a command to such a file when it comes first on the {@code PATH}, and hands a
 * file that it cannot run to the program that Windows associates with the file.
 */
public final class CodexRunPart
{
	static
	{
		CodexRunPartSecrets.setCodexRunPartAccess(new CodexRunPartAccess()
		{
			@Override
			public Optional<Path> createSavedOutput(Path workingDirectory, Map<String, String> environment,
				PrintStream error, Waiting waiting)
			{
				return CodexRunPart.createSavedOutput(workingDirectory, environment, error, waiting);
			}

			@Override
			public String commandToRun(String command, CommandShell shell, boolean saved)
			{
				return CodexRunPart.commandToRun(command, shell, saved);
			}

			@Override
			public int printSaved(Path saved, int status, int lineLimit, CommandShell shell, Path readPart,
				Map<String, String> environment, OutputStream output, PrintStream error) throws IOException
			{
				return CodexRunPart.printSaved(saved, status, lineLimit, shell, readPart, environment, output, error);
			}

			@Override
			public boolean copyOutput(InputStream commandOutput, OutputStream output) throws IOException
			{
				return CodexRunPart.copyOutput(commandOutput, output);
			}

			@Override
			public String unsavedEnd(boolean endsWithLine)
			{
				return CodexRunPart.unsavedEnd(endsWithLine);
			}
		});
	}

	/**
	 * The locale variables that the launcher changes for the JVM and saves for the programs that it starts.
	 */
	private static final String[] LOCALE_VARIABLES = {"LC_ALL", "LC_CTYPE"};
	/**
	 * The start of the name of the folder that holds one saved output.
	 */
	static final String FOLDER_PREFIX = "run-part-";
	/**
	 * The name of the saved output's file in its folder.
	 */
	static final String SAVED_OUTPUT = "output.txt";
	/**
	 * The text appended to a saved output's path to name the file that holds the command's exit status.
	 */
	static final String EXIT_STATUS_SUFFIX = ".exit-status";
	/**
	 * The line that ends the standard output of {@code run-part} when it cannot save the output. It is the line that
	 * ends a saved output's last part without the saved file's path and the clause that says the file was deleted.
	 */
	static final String UNSAVED_END = "[End of output]";
	/**
	 * The folder, in the runtime image, of the programs that the command finds before the caller's {@code PATH}.
	 */
	private static final String OVERRIDES = "run-part-overrides";

	/**
	 * Runs {@code run-part}.
	 *
	 * @param args a non-null empty command-line argument array
	 * @throws NullPointerException if {@code args} is null
	 */
	public static void main(String[] args)
	{
		args = CommandLineArguments.recover(args);
		Console console = System.console();
		boolean terminal = console != null && console.isTerminal();
		CommandShell shell = CommandShell.current();
		int status = run(args, System.in, terminal, Path.of("").toAbsolutePath(), shell,
			CodexReadPart.launcher(shell.launcher("read-part")), Path.of(System.getProperty("java.home"), OVERRIDES),
			Platform.current().environment(System.getenv()), System.out, System.err, Redirect.INHERIT, Pause::sleeping);
		System.out.flush();
		System.exit(status);
	}

	/**
	 * Runs a command, saves its output and exit status, then prints the output's first part. When the folder or the
	 * file that would save the output cannot be created, as in a read-only sandbox, it runs the command as it would
	 * run without {@code run-part} instead: the command's standard output goes to {@code output} and its standard error
	 * to {@code commandError}, unsaved and unchanged, then {@code output} receives the line {@code [End of output]},
	 * after a line feed if the standard output does not end with one.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param input the command
	 * @param terminal {@code true} if {@code input} is a terminal, which holds no command
	 * @param workingDirectory the folder that the command runs in; in a Codex thread, the output is saved in the
	 *   temporary folder of the thread's CAT session directory in the nearest CAT project that contains this folder
	 * @param shell the shell that runs the command, and that runs the commands that this program prints
	 * @param readPart the {@code read-part} launcher that the line that ends a part names
	 * @param overrides the folder of the programs that the command finds before the caller's {@code PATH} when
	 *   {@code shell} is {@code bash}
	 * @param environment the environment: {@code CODEX_THREAD_ID} names the Codex thread, and {@code CODEX_HOME}, or
	 *   else the user's home directory, locates Codex's configuration, which the session marker records; outside a
	 *   CAT project, or without {@code CODEX_THREAD_ID}, the output is saved in the folder that
	 *   {@link #fallbackFolder(Map)} returns for this environment; the command
	 *   runs in this environment with the caller's locale restored, the overrides folder first on a set {@code PATH}
	 *   when {@code shell} is {@code bash}, and {@code CAT_RUN_PART_OUTPUT} naming the saved output, which is unset
	 *   when the output cannot be saved; in PowerShell, {@code CAT_RG_LINE_LIMIT} sets the line limit of a saved
	 *   command that is one {@code rg} search (see {@link RipgrepOutputLimit})
	 * @param output receives the part, or the command's standard output when its output cannot be saved
	 * @param error receives diagnostics
	 * @param commandError where the command's standard error goes when its output cannot be saved
	 * @param waiting how the command waits between the attempts of a retried operation
	 * @return the exit status of {@code read-part}; the command's exit status if its output cannot be saved; 1 if the
	 *   command cannot be run; or 2 for a usage error or a {@code CAT_RG_LINE_LIMIT} that is not a whole number
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] args, InputStream input, boolean terminal, Path workingDirectory, CommandShell shell,
		Path readPart, Path overrides, Map<String, String> environment, OutputStream output, PrintStream error,
		Redirect commandError, Waiting waiting)
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(shell, "shell").isNotNull();
		requireThat(readPart, "readPart").isNotNull();
		requireThat(overrides, "overrides").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(error, "error").isNotNull();
		requireThat(commandError, "commandError").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		try
		{
			String command = "";
			if (args.length == 0 && !terminal)
				command = new String(input.readAllBytes(), UTF_8);
			if (command.isBlank())
			{
				error.println(shell.runPartUsage());
				return 2;
			}
			int lineLimit = 0;
			if (shell == CommandShell.POWERSHELL && RipgrepOutputLimit.limitedCommand(command).isPresent())
			{
				try
				{
					lineLimit = RipgrepOutputLimit.lineLimit(environment);
				}
				catch (IllegalArgumentException e)
				{
					error.println("run-part: " + e.getMessage());
					return 2;
				}
			}
			Optional<Path> saved = createSavedOutput(workingDirectory, environment, error, waiting);
			if (saved.isEmpty())
			{
				ProcessBuilder builder = commandBuilder(commandToRun(command, shell, false), workingDirectory, shell,
					overrides, environment).
					redirectError(commandError);
				return runUnsaved(builder, output);
			}
			ProcessBuilder builder = commandBuilder(commandToRun(command, shell, true), workingDirectory, shell,
				overrides, environment).
				redirectErrorStream(true).
				redirectOutput(saved.get().toFile());
			builder.environment().put("CAT_RUN_PART_OUTPUT", saved.get().toString());
			int status = runCommand(builder);
			return printSaved(saved.get(), status, lineLimit, shell, readPart, environment, output, error);
		}
		catch (IOException | InvalidPathException e)
		{
			error.println("run-part: " + e.getMessage());
			return 1;
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			error.println("run-part: interrupted");
			return 1;
		}
	}

	/**
	 * Creates the empty file that saves the command's output, in a new folder of its own.
	 *
	 * @param workingDirectory the folder that the command runs in
	 * @param environment the environment of the JVM
	 * @param error receives the notice about the project's {@code .gitignore} when creating the CAT session directory
	 *   changed that file or could not
	 * @param waiting how the command waits between the attempts of a retried operation
	 * @return the file, or an empty {@code Optional} if the folder or the file cannot be created, as in a read-only
	 *   sandbox
	 */
	private static Optional<Path> createSavedOutput(Path workingDirectory, Map<String, String> environment,
		PrintStream error, Waiting waiting)
	{
		try
		{
			Path folder = Files.createTempDirectory(outputFolder(workingDirectory, environment, error, waiting),
				FOLDER_PREFIX);
			return Optional.of(Files.createFile(folder.resolve(SAVED_OUTPUT)));
		}
		catch (IOException | InvalidPathException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Returns the command that the shell runs. A PowerShell command that is one {@code rg} search, whose output is
	 * saved, runs with each line cut to its first 500 bytes (see {@link RipgrepOutputLimit#limitedCommand(String)}).
	 * Every other command, including such a search whose output cannot be saved, runs as written, as it would without
	 * {@code run-part}.
	 *
	 * @param command the command as written
	 * @param shell   the shell that runs the command
	 * @param saved   {@code true} if the command's output is saved
	 * @return the command to run
	 */
	private static String commandToRun(String command, CommandShell shell, boolean saved)
	{
		if (!saved || shell != CommandShell.POWERSHELL)
			return command;
		return RipgrepOutputLimit.limitedCommand(command).orElse(command);
	}

	/**
	 * Saves the exit status of a command whose output was saved, then prints the output's first part. When
	 * {@code lineLimit} is not 0, the output of a search with more lines than the limit is first replaced by a request
	 * to narrow the search, and the status saved is then 2.
	 *
	 * @param saved       the file that saves the command's output
	 * @param status      the command's exit status
	 * @param lineLimit   the most lines that the agent receives from a PowerShell {@code rg} search, or 0 for a
	 *                    command whose output is not limited
	 * @param shell       the shell that runs the commands that this program prints
	 * @param readPart    the {@code read-part} launcher that the line that ends a part names
	 * @param environment the environment of the JVM
	 * @param output      receives the part
	 * @param error       receives diagnostics
	 * @return the exit status of {@code read-part}
	 * @throws IOException if the output cannot be read or replaced, or the status cannot be saved
	 */
	private static int printSaved(Path saved, int status, int lineLimit, CommandShell shell, Path readPart,
		Map<String, String> environment, OutputStream output, PrintStream error) throws IOException
	{
		int savedStatus = RipgrepOutputLimit.limitOutput(saved, status, lineLimit);
		Files.writeString(Path.of(saved + EXIT_STATUS_SUFFIX), savedStatus + "\n");
		return CodexReadPart.run(new String[]{saved.toString()}, shell, readPart, environment, output, error);
	}

	/**
	 * Runs a command whose output cannot be saved, without input, copies its standard output as it arrives, then ends
	 * it with the line {@code [End of output]} on a line of its own.
	 *
	 * @param builder the process builder of the command, whose standard output is a pipe
	 * @param output receives the command's standard output, then the line
	 * @return the command's exit status
	 * @throws IOException if the command cannot be started or its output cannot be copied
	 * @throws InterruptedException if the thread is interrupted while the command runs
	 */
	private static int runUnsaved(ProcessBuilder builder, OutputStream output) throws IOException,
		InterruptedException
	{
		try (Process process = builder.start())
		{
			// The command reads no input: closing its standard input ends any read at once, on every platform
			process.getOutputStream().close();
			boolean endsWithLine;
			try (InputStream commandOutput = process.getInputStream())
			{
				endsWithLine = copyOutput(commandOutput, output);
			}
			int status = process.waitFor();
			output.write(unsavedEnd(endsWithLine).getBytes(UTF_8));
			output.flush();
			return status;
		}
	}

	/**
	 * Copies the standard output of a command whose output cannot be saved, unchanged, as it arrives.
	 *
	 * @param commandOutput the command's standard output
	 * @param output        receives the command's standard output
	 * @return {@code true} if the standard output is empty or ends with a line feed
	 * @throws IOException if the output cannot be read or written
	 */
	private static boolean copyOutput(InputStream commandOutput, OutputStream output) throws IOException
	{
		byte last = '\n';
		byte[] buffer = new byte[8192];
		for (int count = commandOutput.read(buffer); count >= 0; count = commandOutput.read(buffer))
		{
			output.write(buffer, 0, count);
			output.flush();
			last = buffer[count - 1];
		}
		return last == '\n';
	}

	/**
	 * Returns the text that ends the standard output of {@code run-part} when it cannot save the output: the line
	 * {@code [End of output]} on a line of its own. The line ends with {@code \n} on every platform, as do the lines
	 * that {@code read-part} adds to a part.
	 *
	 * @param endsWithLine {@code true} if the command's standard output is empty or ends with a line feed
	 * @return the line, after a line feed if {@code endsWithLine} is {@code false}
	 */
	private static String unsavedEnd(boolean endsWithLine)
	{
		String end = UNSAVED_END + "\n";
		if (!endsWithLine)
			end = "\n" + end;
		return end;
	}

	/**
	 * Returns a process builder that runs a command in the shell, in its folder and in the caller's environment.
	 *
	 * @param command the command
	 * @param workingDirectory the folder that the command runs in
	 * @param shell the shell that runs the command
	 * @param overrides the folder of the programs that the command finds before the caller's {@code PATH} when
	 *   {@code shell} is {@code bash}
	 * @param environment the environment of the JVM
	 * @return the process builder, whose environment is the caller's, with its locale restored and, when {@code shell}
	 *   is {@code bash}, {@code overrides} first on a set {@code PATH}
	 */
	private static ProcessBuilder commandBuilder(String command, Path workingDirectory, CommandShell shell,
		Path overrides, Map<String, String> environment)
	{
		ProcessBuilder builder = new ProcessBuilder(shell.processArguments(command, environment)).
			directory(workingDirectory.toFile());
		Map<String, String> childEnvironment = builder.environment();
		childEnvironment.clear();
		childEnvironment.putAll(callerEnvironment(environment));
		String path = childEnvironment.get("PATH");
		if (path != null && shell == CommandShell.POSIX)
			childEnvironment.put("PATH", overrides + File.pathSeparator + path);
		return builder;
	}

	/**
	 * Runs a command without input and waits for it to exit.
	 *
	 * @param builder the process builder of the command
	 * @return the command's exit status
	 * @throws IOException if the command cannot be started
	 * @throws InterruptedException if the thread is interrupted while the command runs
	 */
	private static int runCommand(ProcessBuilder builder) throws IOException, InterruptedException
	{
		try (Process process = builder.start())
		{
			// The command reads no input: closing its standard input ends any read at once, on every platform
			process.getOutputStream().close();
			return process.waitFor();
		}
	}

	/**
	 * Returns the folder that holds the saved output.
	 *
	 * @param workingDirectory the folder that the command runs in
	 * @param environment the environment of the JVM
	 * @param error receives the notice about the project's {@code .gitignore} when creating the CAT session directory
	 *   changed that file or could not
	 * @param waiting how the command waits between the attempts of a retried operation
	 * @return the temporary folder of the Codex thread's CAT session directory in the nearest CAT project that
	 *   contains {@code workingDirectory}; otherwise the folder that {@link #fallbackFolder(Map)} returns
	 * @throws IOException if {@code CODEX_THREAD_ID} is not a UUID in canonical form, if neither {@code CODEX_HOME} nor
	 *   the home directory's variable is set, or if the CAT session directory cannot be created
	 */
	private static Path outputFolder(Path workingDirectory, Map<String, String> environment, PrintStream error,
		Waiting waiting) throws IOException
	{
		try (ToolScope scope = ToolScope.forSession(workingDirectory, environment,
			List.of(CodexSessionHarness.HARNESS), waiting))
		{
			if (scope.isInProject())
			{
				Optional<SessionDirectory> session = scope.sessionDirectory();
				if (session.isPresent())
				{
					session.get().ignoreNotice().ifPresent(notice -> error.println(notice.forAgent()));
					return session.get().temp();
				}
			}
			return scope.temporaryDirectory();
		}
	}

	/**
	 * Returns the folder that holds the saved output of a command that runs outside a CAT project or outside a Codex
	 * thread.
	 *
	 * @param environment the environment of the JVM
	 * @return the absolute, normalized folder named by {@code TMPDIR}, then {@code TEMP}, then the JVM temporary folder
	 *   ({@code java.io.tmpdir}) when both are unset or empty; creates the folder when necessary
	 * @throws NullPointerException if {@code environment} is null
	 * @throws IOException if the process temporary directory cannot be created
	 */
	public static Path fallbackFolder(Map<String, String> environment) throws IOException
	{
		return Files.createDirectories(MainJvmScope.processTemporaryDirectory(environment).toAbsolutePath().normalize());
	}

	/**
	 * Indicates whether a folder is named and placed the way {@code run-part} creates the folder of one saved output:
	 * a {@code run-part-*} folder directly inside a CAT session's temporary directory or directly inside the folder
	 * that {@link #fallbackFolder(Map)} returns for {@code environment}. The folder need not exist.
	 *
	 * @param folder the folder
	 * @param environment the environment of the JVM
	 * @return {@code true} if the folder's name and location match
	 * @throws IOException if the process temporary directory cannot be created
	 */
	static boolean isOutputFolder(Path folder, Map<String, String> environment) throws IOException
	{
		Path normalized = folder.toAbsolutePath().normalize();
		Path name = normalized.getFileName();
		Path temporary = normalized.getParent();
		if (name == null || temporary == null || !name.toString().startsWith(FOLDER_PREFIX))
			return false;
		return SessionDirectory.isTemporaryDirectory(temporary) ||
			temporary.equals(fallbackFolder(environment).toAbsolutePath().normalize());
	}

	/**
	 * Indicates whether a file is output that {@code run-part} saved: a regular file named {@code output.txt}, with the
	 * regular file {@code output.txt.exit-status} beside it, in a folder for which
	 * {@link #isOutputFolder(Path, Map)} holds. Neither file nor their folder may be a symbolic link.
	 *
	 * @param file the file
	 * @param environment the environment of the JVM
	 * @return {@code true} if {@code run-part} saved the file
	 * @throws IOException if the process temporary directory cannot be created
	 */
	static boolean isSavedOutput(Path file, Map<String, String> environment) throws IOException
	{
		Path normalized = file.toAbsolutePath().normalize();
		Path name = normalized.getFileName();
		Path folder = normalized.getParent();
		if (name == null || folder == null || !name.toString().equals(SAVED_OUTPUT) ||
			!isOutputFolder(folder, environment))
		{
			return false;
		}
		return Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) &&
			Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) &&
			Files.isRegularFile(folder.resolve(SAVED_OUTPUT + EXIT_STATUS_SUFFIX), LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * Returns the environment with the caller's locale, which the launcher saved before it changed the locale of the
	 * JVM. A saved value is {@code +} followed by the variable's value, or {@code -} when the variable was unset.
	 *
	 * @param environment the environment of the JVM
	 * @return the caller's environment
	 */
	private static Map<String, String> callerEnvironment(Map<String, String> environment)
	{
		Map<String, String> caller = new HashMap<>(environment);
		for (String name : LOCALE_VARIABLES)
		{
			String saved = caller.remove("CAT_CALLER_" + name);
			if (saved == null)
				continue;
			if (saved.startsWith("+"))
				caller.put(name, saved.substring(1));
			else
				caller.remove(name);
		}
		return caller;
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexRunPart()
	{
	}
}
