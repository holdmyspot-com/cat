package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.CommandLineArguments;
import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.SessionDirectory;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
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
 * {@code CODEX_THREAD_ID}, it is saved in the folder that {@link #fallbackFolder(Map)} returns.
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
			Platform.current().environment(System.getenv()), System.out, System.err);
		System.out.flush();
		System.exit(status);
	}

	/**
	 * Runs a command, saves its output and exit status, then prints the output's first part.
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
	 *   when {@code shell} is {@code bash}, and {@code CAT_RUN_PART_OUTPUT} naming the saved output; in PowerShell,
	 *   {@code CAT_RG_LINE_LIMIT} sets the line limit of a command that is one {@code rg} search
	 *   (see {@link RipgrepOutputLimit})
	 * @param output receives the part
	 * @param error receives diagnostics
	 * @return the exit status of {@code read-part}, 1 if the command cannot be run, or 2 for a usage error or a
	 *   {@code CAT_RG_LINE_LIMIT} that is not a whole number
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] args, InputStream input, boolean terminal, Path workingDirectory, CommandShell shell,
		Path readPart, Path overrides, Map<String, String> environment, OutputStream output, PrintStream error)
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
			Optional<String> search = Optional.empty();
			int lineLimit = 0;
			if (shell == CommandShell.POWERSHELL)
			{
				search = RipgrepOutputLimit.limitedCommand(command);
				if (search.isPresent())
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
					command = search.get();
				}
			}
			Path saved = Files.createTempDirectory(outputFolder(workingDirectory, environment), FOLDER_PREFIX).
				resolve(SAVED_OUTPUT);
			ProcessBuilder builder = new ProcessBuilder(shell.processArguments(command, environment)).
				directory(workingDirectory.toFile()).
				redirectErrorStream(true).
				redirectOutput(saved.toFile());
			Map<String, String> childEnvironment = builder.environment();
			childEnvironment.clear();
			childEnvironment.putAll(callerEnvironment(environment));
			childEnvironment.put("CAT_RUN_PART_OUTPUT", saved.toString());
			String path = childEnvironment.get("PATH");
			if (path != null && shell == CommandShell.POSIX)
				childEnvironment.put("PATH", overrides + File.pathSeparator + path);
			int status;
			try (Process process = builder.start())
			{
				// The command reads no input: closing its standard input ends any read at once, on every platform
				process.getOutputStream().close();
				status = process.waitFor();
			}
			if (search.isPresent())
				status = RipgrepOutputLimit.limitOutput(saved, status, lineLimit);
			Files.writeString(Path.of(saved + EXIT_STATUS_SUFFIX), status + "\n");
			return CodexReadPart.run(new String[]{saved.toString()}, shell, readPart, environment, output, error);
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
	 * Returns the folder that holds the saved output.
	 *
	 * @param workingDirectory the folder that the command runs in
	 * @param environment the environment of the JVM
	 * @return the temporary folder of the Codex thread's CAT session directory in the nearest CAT project that
	 *   contains {@code workingDirectory}; otherwise the folder that {@link #fallbackFolder(Map)} returns
	 * @throws IOException if {@code CODEX_THREAD_ID} is not a UUID in canonical form, if neither {@code CODEX_HOME} nor
	 *   the home directory's variable is set, or if the CAT session directory cannot be created
	 */
	private static Path outputFolder(Path workingDirectory, Map<String, String> environment) throws IOException
	{
		try (ToolScope scope = ToolScope.forSession(workingDirectory, environment,
			List.of(CodexSessionHarness.HARNESS)))
		{
			if (scope.isInProject())
			{
				Optional<SessionDirectory> session = scope.sessionDirectory();
				if (session.isPresent())
					return session.get().temp();
			}
		}
		return fallbackFolder(environment);
	}

	/**
	 * Returns the folder that holds the saved output of a command that runs outside a CAT project or outside a Codex
	 * thread.
	 *
	 * @param environment the environment of the JVM
	 * @return the folder that {@code TMPDIR}, or else {@code TEMP}, names, or the JVM's temporary folder
	 *   ({@code java.io.tmpdir}) when both are unset or empty
	 * @throws NullPointerException if {@code environment} is null
	 */
	public static Path fallbackFolder(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		for (String name : new String[]{"TMPDIR", "TEMP"})
		{
			String temporary = environment.get(name);
			if (temporary != null && !temporary.isEmpty())
				return Path.of(temporary);
		}
		return Path.of(System.getProperty("java.io.tmpdir"));
	}

	/**
	 * Indicates whether a folder is named and placed the way {@code run-part} creates the folder of one saved output:
	 * a {@code run-part-*} folder directly inside a CAT session's temporary directory or directly inside the folder
	 * that {@link #fallbackFolder(Map)} returns for {@code environment}. The folder need not exist.
	 *
	 * @param folder the folder
	 * @param environment the environment of the JVM
	 * @return {@code true} if the folder's name and location match
	 */
	static boolean isOutputFolder(Path folder, Map<String, String> environment)
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
	 */
	static boolean isSavedOutput(Path file, Map<String, String> environment)
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
