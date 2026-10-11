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
 * with status 0.
 */
public final class CodexRunPart
{
	private static final String USAGE = "usage: run-part <<'EOF', then the command on the lines that follow, then EOF";
	/**
	 * The locale variables that the launcher changes for the JVM and saves for the programs that it starts.
	 */
	private static final String[] LOCALE_VARIABLES = {"LC_ALL", "LC_CTYPE"};

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
		int status = run(args, System.in, terminal, CodexReadPart.launcher("read-part"), System.getenv(), System.out,
			System.err);
		System.out.flush();
		System.exit(status);
	}

	/**
	 * Runs a command, saves its output and exit status, then prints the output's first part.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param input the command
	 * @param terminal {@code true} if {@code input} is a terminal, which holds no command
	 * @param readPart the {@code read-part} launcher that the line that ends a part names
	 * @param environment the environment: {@code TMPDIR} names the folder of the saved output, which is {@code /tmp}
	 *   when it is unset, and the command runs in this environment with the caller's locale restored
	 * @param output receives the part
	 * @param error receives diagnostics
	 * @return the exit status of {@code read-part}, 1 if the command cannot be run, or 2 for a usage error
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] args, InputStream input, boolean terminal, Path readPart,
		Map<String, String> environment, OutputStream output, PrintStream error)
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
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
			String temporary = environment.get("TMPDIR");
			if (temporary == null || temporary.isEmpty())
				temporary = "/tmp";
			Path saved = Files.createTempDirectory(Path.of(temporary), "run-part-").resolve("output.txt");
			ProcessBuilder builder = new ProcessBuilder("bash", "-c", command).
				redirectErrorStream(true).
				redirectOutput(saved.toFile()).
				redirectInput(Redirect.from(new File("/dev/null")));
			Map<String, String> childEnvironment = builder.environment();
			childEnvironment.clear();
			childEnvironment.putAll(callerEnvironment(environment));
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
