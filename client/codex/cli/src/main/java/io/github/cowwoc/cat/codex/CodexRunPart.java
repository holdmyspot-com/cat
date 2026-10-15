package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Saves the output of a command in a new file, then prints that file's first part the way {@code read-part} prints a
 * file: the part ends with the command that prints the next part.
 * <p>
 * Usage: {@code run-part <<'EOF'}, then the shell command on the lines that follow, as it would be written without
 * {@code run-part}, then {@code EOF}. It runs the command with {@code bash} and saves what it writes to standard output
 * and standard error. It saves the command's exit status in {@code <file>.exit-status}, so that whichever call prints
 * the last part, {@code run-part} or {@code read-part}, exits with that status; a part that more parts follow exits
 * with status 0. The output stays inside the project: it is saved in the CAT temporary folder, {@code .cat/work/temp},
 * of the nearest folder that holds one, starting from the folder that the command runs in.
 * <p>
 * The command finds the plugin's {@code bin/run-part-overrides} folder first on its {@code PATH} and receives the saved
 * output's path in {@code CAT_RUN_PART_OUTPUT}. A program in that folder, such as {@code rg}, keeps output that it
 * writes straight into that file small enough for the agent that reads it, and leaves other output unchanged.
 */
public final class CodexRunPart
{
	private static final String USAGE = "usage: run-part <<'EOF', then the command on the lines that follow, then EOF";
	/**
	 * The locale variables that the launcher changes for the JVM and saves for the programs that it starts.
	 */
	private static final String[] LOCALE_VARIABLES = {"LC_ALL", "LC_CTYPE"};
	/**
	 * The CAT temporary folder relative to a project folder. The session-start hook creates it in the session's project.
	 */
	private static final Path CAT_TEMP = Path.of(".cat/work/temp");
	/**
	 * The folder, next to the {@code read-part} launcher, of the programs that the command finds before the caller's
	 * {@code PATH}.
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
		requireThat(args, "args").isNotNull();
		Console console = System.console();
		boolean terminal = console != null && console.isTerminal();
		int status = run(args, System.in, terminal, Path.of("").toAbsolutePath(), CodexReadPart.launcher("read-part"),
			System.getenv(), System.out, System.err);
		System.out.flush();
		System.exit(status);
	}

	/**
	 * Runs a command, saves its output and exit status, then prints the output's first part.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param input the command
	 * @param terminal {@code true} if {@code input} is a terminal, which holds no command
	 * @param workingDirectory the folder that the command runs in; the output is saved in the CAT temporary folder of
	 *   the nearest folder that holds one, starting from this folder
	 * @param readPart the {@code read-part} launcher that the line that ends a part names; its folder holds the
	 *   {@code run-part-overrides} folder
	 * @param environment the environment: when no folder from {@code workingDirectory} upward holds a CAT temporary
	 *   folder, {@code TMPDIR} names the folder of the saved output, which is {@code /tmp} when it is unset; the command
	 *   runs in this environment with the caller's locale restored, the overrides folder first on a set {@code PATH},
	 *   and {@code CAT_RUN_PART_OUTPUT} naming the saved output
	 * @param output receives the part
	 * @param error receives diagnostics
	 * @return the exit status of {@code read-part}, 1 if the command cannot be run, or 2 for a usage error
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] args, InputStream input, boolean terminal, Path workingDirectory, Path readPart,
		Map<String, String> environment, OutputStream output, PrintStream error)
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(readPart, "readPart").isNotNull();
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
				error.println(USAGE);
				return 2;
			}
			Path saved = Files.createTempDirectory(outputFolder(workingDirectory, environment), "run-part-").
				resolve("output.txt");
			ProcessBuilder builder = new ProcessBuilder("bash", "-c", command).
				directory(workingDirectory.toFile()).
				redirectErrorStream(true).
				redirectOutput(saved.toFile()).
				redirectInput(Redirect.from(new File("/dev/null")));
			Map<String, String> childEnvironment = builder.environment();
			childEnvironment.clear();
			childEnvironment.putAll(callerEnvironment(environment));
			childEnvironment.put("CAT_RUN_PART_OUTPUT", saved.toString());
			String path = childEnvironment.get("PATH");
			if (path != null)
				childEnvironment.put("PATH", readPart.resolveSibling(OVERRIDES) + File.pathSeparator + path);
			int status = builder.start().waitFor();
			Files.writeString(Path.of(saved + ".exit-status"), status + "\n");
			return CodexReadPart.run(new String[]{saved.toString()}, readPart, environment, output, error);
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
	 * @return the CAT temporary folder of the nearest folder that holds one, starting from {@code workingDirectory};
	 *   otherwise {@code TMPDIR}, or {@code /tmp} when it is unset
	 */
	private static Path outputFolder(Path workingDirectory, Map<String, String> environment)
	{
		for (Path folder = workingDirectory.toAbsolutePath(); folder != null; folder = folder.getParent())
		{
			Path catTemp = folder.resolve(CAT_TEMP);
			if (Files.isDirectory(catTemp))
				return catTemp;
		}
		String temporary = environment.get("TMPDIR");
		if (temporary == null || temporary.isEmpty())
			return Path.of("/tmp");
		return Path.of(temporary);
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
