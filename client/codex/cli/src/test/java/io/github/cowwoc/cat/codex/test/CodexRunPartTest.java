package io.github.cowwoc.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.codex.CodexRunPart;
import io.github.cowwoc.cat.codex.CommandShell;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Verifies that {@code run-part} saves a command's output and exit status, then prints the output's first part the way
 * {@code read-part} prints a file.
 */
public final class CodexRunPartTest
{
	/**
	 * The shell that runs the command on this platform.
	 */
	private static final CommandShell SHELL = CommandShell.current();
	private static final Path READ_PART = Path.of("/plugin/bin").toAbsolutePath().resolve(SHELL.launcher("read-part"));
	/**
	 * The line separator that the shell's commands end their lines with.
	 */
	private static final String EOL = System.lineSeparator();
	private static final Pattern LAST_PART = Pattern.compile("\\[End of (?<path>[^\n]+)]\n\\z");
	/**
	 * A folder that no CAT project contains, so that {@code run-part} saves the output of a command that runs there
	 * under {@code TMPDIR}.
	 */
	private static final Path OUTSIDE_PROJECT = Path.of("/").toAbsolutePath();

	/**
	 * Creates a test instance.
	 */
	public CodexRunPartTest()
	{
	}

	/**
	 * Verifies that the command's standard output and standard error are saved in one file under {@code TMPDIR} when no
	 * CAT project contains the folder that the command runs in, that the last part exits with the command's exit status,
	 * and that the status is saved next to the output for {@code read-part}.
	 *
	 * @throws IOException if the temporary folder cannot be created or read
	 */
	@Test
	public void savesOutputAndStatus() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));

			Call call = runPart(script("printf 'out\\n'\necho err >&2\nexit 3\n",
				"Write-Output out\n[Console]::Error.WriteLine('err')\nexit 3\n"), false, OUTSIDE_PROJECT,
				Map.of("TMPDIR", temporary.toString()));

			Matcher lastPart = LAST_PART.matcher(call.output());
			requireThat(lastPart.find(), "lastPart").isTrue();
			Path saved = Path.of(lastPart.group("path"));
			requireThat(saved.getParent().getParent(), "folder").isEqualTo(temporary);
			requireThat(call.output(), "output").isEqualTo("out" + EOL + "err" + EOL + "[End of " + saved + "]\n");
			requireThat(call.status(), "status").isEqualTo(3);
			requireThat(Files.readString(Path.of(saved + ".exit-status")), "exitStatus").isEqualTo("3\n");
		}
	}

	/**
	 * Verifies that the shell receives the command unchanged, including its double quotes and a backslash before a
	 * closing quote.
	 *
	 * @throws IOException if the temporary folder cannot be created
	 */
	@Test
	public void shellReceivesCommandUnchanged() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));

			Call call = runPart(script("printf '%s\\n' \"a \\\"b\\\" c\\\\\"", "Write-Output \"a \"\"b\"\" c\\\""), false,
				OUTSIDE_PROJECT, Map.of("TMPDIR", temporary.toString()));

			requireThat(call.output(), "output").startsWith("a \"b\" c\\" + EOL + "[End of ");
		}
	}

	/**
	 * Verifies that a command runs in its folder and that its output is saved in the CAT temporary folder of the nearest
	 * project that contains that folder, so that it stays inside the project.
	 *
	 * @throws IOException if the temporary folders cannot be created
	 */
	@Test
	public void savesOutputInProjectTemp() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Path catTemp = Files.createDirectories(project.resolve(".cat/work/temp"));
			Path folder = Files.createDirectories(project.resolve("src/main"));
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));

			Call call = runPart(script("pwd", "(Get-Location).Path"), false, folder, Map.of("TMPDIR", temporary.toString()));

			Matcher lastPart = LAST_PART.matcher(call.output());
			requireThat(lastPart.find(), "lastPart").isTrue();
			Path saved = Path.of(lastPart.group("path"));
			requireThat(saved.getParent().getParent(), "folder").isEqualTo(catTemp);
			// pwd prints the folder's real path, which differs where the temporary folder is a symbolic link, as on macOS
			requireThat(call.output(), "output").isEqualTo(folder.toRealPath() + EOL + "[End of " + saved + "]\n");
		}
	}

	/**
	 * Verifies that in a Codex thread, output too long for one part ends with the {@code read-part} command that prints
	 * the next part, and that the first part exits with status 0 whatever the command's status.
	 *
	 * @throws IOException if the temporary folder cannot be created
	 */
	@Test
	public void longOutputNamesNextPart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Map<String, String> environment = Map.of("TMPDIR", temporary.toString(), "CODEX_THREAD_ID", "thread",
				"CODEX_HOME", temporary.toString());

			Call call = runPart(script("seq 1 20000; exit 4", "1..20000; exit 4"), false, OUTSIDE_PROJECT, environment);

			requireThat(call.output(), "output").startsWith("1" + EOL + "2" + EOL + "3" + EOL).
				matches("(?s).*\\[More output follows\\. Before any other command or answer, run: " +
					Pattern.quote(SHELL.command(READ_PART.toString())) + " .+[/\\\\]output\\.txt[\"']* [1-9][0-9]*]\n");
			requireThat(call.status(), "status").isEqualTo(0);
		}
	}

	/**
	 * Verifies that the command reads no input, so that it cannot wait for input that never comes.
	 *
	 * @throws IOException if the temporary folder cannot be created
	 */
	@Test
	public void commandReadsNoInput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));

			Call call = runPart(script("cat; echo done", "[Console]::In.ReadToEnd() | Out-Null; 'done'"), false,
				OUTSIDE_PROJECT, Map.of("TMPDIR", temporary.toString()));

			requireThat(call.output(), "output").startsWith("done" + EOL + "[End of ");
			requireThat(call.status(), "status").isEqualTo(0);
		}
	}

	/**
	 * Verifies that the command runs with the caller's locale, which the launcher saved before it changed the locale of
	 * the JVM.
	 *
	 * @throws IOException if the temporary folder cannot be created
	 */
	@Test
	public void commandRunsInCallerLocale() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Map<String, String> environment = Map.of("TMPDIR", temporary.toString(), "LC_CTYPE", "C.UTF-8",
				"CAT_CALLER_LC_ALL", "+C", "CAT_CALLER_LC_CTYPE", "-");

			Call call = runPart(script("printf '%s|%s|%s\\n' \"${LC_ALL-unset}\" \"${LC_CTYPE-unset}\" " +
				"\"${CAT_CALLER_LC_ALL-unset}\"", variables("LC_ALL", "LC_CTYPE", "CAT_CALLER_LC_ALL")), false,
				OUTSIDE_PROJECT, environment);

			requireThat(call.output(), "output").startsWith("C|unset|unset" + EOL);
		}
	}

	/**
	 * Verifies that a command that runs in a POSIX shell finds {@code rg} among the plugin's {@code run-part} overrides
	 * before the caller's {@code PATH}, and that the command is told which file saves its output, so that the override
	 * can tell whether it writes straight into that file. The overrides are POSIX scripts without an extension, so a
	 * command that runs in PowerShell finds the caller's {@code rg} instead: PowerShell resolves a command to a file
	 * without an extension that comes first on the {@code PATH}, and hands a file that it cannot run to the program
	 * that Windows associates with the file.
	 *
	 * @throws IOException if the temporary files cannot be written
	 */
	@Test
	public void findsRipgrepOverrideOnlyInPosixShell() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-")).toAbsolutePath();
			Path bin = Files.createDirectories(temporary.resolve("plugin").resolve("bin"));
			Path override = Files.createDirectories(bin.resolve("run-part-overrides")).resolve("rg");
			writeExecutable(override, "#!/bin/sh\n");
			Path caller = Files.createDirectories(temporary.resolve("caller"));
			Path callerRipgrep;
			Path expected;
			if (SHELL == CommandShell.POWERSHELL)
			{
				callerRipgrep = caller.resolve("rg.cmd");
				Files.writeString(callerRipgrep, "@exit /b 0\r\n");
				expected = callerRipgrep;
			}
			else
			{
				callerRipgrep = caller.resolve("rg");
				writeExecutable(callerRipgrep, "#!/bin/sh\n");
				expected = override;
			}
			String path = caller + File.pathSeparator + Path.of("/usr/bin").toAbsolutePath() + File.pathSeparator +
				Path.of("/bin").toAbsolutePath();
			Map<String, String> environment = new HashMap<>(Map.of("TMPDIR", temporary.toString(), "PATH", path));
			// PowerShell finds a program by its name without an extension only with an extension that PATHEXT lists
			String extensions = System.getenv("PATHEXT");
			if (extensions != null)
				environment.put("PATHEXT", extensions);

			Call call = runPart(script("printf '%s|%s\\n' \"$(command -v rg)\" \"${CAT_RUN_PART_OUTPUT-unset}\"",
				"Write-Output \"$((Get-Command -Name rg).Source)|$env:CAT_RUN_PART_OUTPUT\""), false, OUTSIDE_PROJECT,
				bin.resolve(SHELL.launcher("read-part")), environment);

			Matcher lastPart = LAST_PART.matcher(call.output());
			requireThat(lastPart.find(), "lastPart").withContext(call.error(), "error").isTrue();
			requireThat(call.output(), "output").isEqualTo(expected + "|" + lastPart.group("path") + EOL +
				"[End of " + lastPart.group("path") + "]\n");
		}
	}

	/**
	 * Verifies that a call without a command prints how to use {@code run-part} and saves nothing.
	 *
	 * @throws IOException if the temporary folder cannot be created or listed
	 */
	@Test
	public void blankCommandIsUsageError() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			for (Call call : List.of(runPart(" \n\t\n", false, OUTSIDE_PROJECT, Map.of("TMPDIR", temporary.toString())),
				runPart("echo typed", true, OUTSIDE_PROJECT, Map.of("TMPDIR", temporary.toString()))))
			{
				requireThat(call.status(), "status").isEqualTo(2);
				requireThat(call.output(), "output").isEmpty();
				requireThat(call.error(), "error").isEqualTo(SHELL.runPartUsage() + EOL);
			}
			try (Stream<Path> files = Files.list(temporary))
			{
				requireThat(files.toList(), "files").isEmpty();
			}
		}
	}

	/**
	 * Returns the form of a command for the shell that runs it on this platform.
	 *
	 * @param posix the command for a POSIX shell
	 * @param powerShell the command for PowerShell
	 * @return the command
	 */
	private static String script(String posix, String powerShell)
	{
		if (SHELL == CommandShell.POWERSHELL)
			return powerShell;
		return posix;
	}

	/**
	 * Returns a PowerShell command that prints environment variables on one line, separated by {@code |}, with
	 * {@code unset} for each variable that is unset.
	 *
	 * @param names the names of the variables
	 * @return the command
	 */
	private static String variables(String... names)
	{
		StringJoiner values = new StringJoiner("|", "Write-Output \"", "\"");
		for (String name : names)
			values.add("$(if ($null -eq $env:" + name + ") { 'unset' } else { $env:" + name + " })");
		return values.toString();
	}

	/**
	 * Runs {@code run-part}.
	 *
	 * @param command the command on standard input
	 * @param terminal {@code true} if standard input is a terminal
	 * @param workingDirectory the folder that the command runs in
	 * @param environment the environment
	 * @return the call's streams and exit status
	 */
	private static Call runPart(String command, boolean terminal, Path workingDirectory,
		Map<String, String> environment)
	{
		return runPart(command, terminal, workingDirectory, READ_PART, environment);
	}

	/**
	 * Runs {@code run-part} from a plugin's {@code bin} folder.
	 *
	 * @param command the command on standard input
	 * @param terminal {@code true} if standard input is a terminal
	 * @param workingDirectory the folder that the command runs in
	 * @param readPart the path of the plugin's {@code read-part} launcher
	 * @param environment the environment
	 * @return the call's streams and exit status
	 */
	private static Call runPart(String command, boolean terminal, Path workingDirectory, Path readPart,
		Map<String, String> environment)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		int status = CodexRunPart.run(new String[0], new ByteArrayInputStream(command.getBytes(UTF_8)), terminal,
			workingDirectory, SHELL, readPart, environment, output, new PrintStream(error, true, UTF_8));
		return new Call(output.toString(UTF_8), error.toString(UTF_8), status);
	}

	/**
	 * Writes a POSIX script that every user may run.
	 *
	 * @param path    the script's path
	 * @param content the script
	 * @throws IOException if the script cannot be written
	 */
	private static void writeExecutable(Path path, String content) throws IOException
	{
		Files.writeString(path, content);
		requireThat(path.toFile().setExecutable(true, false), "executable").isTrue();
	}

	/**
	 * The streams and exit status of one call.
	 *
	 * @param output the standard output
	 * @param error the standard error
	 * @param status the exit status
	 */
	private record Call(String output, String error, int status)
	{
		/**
		 * Creates a call.
		 *
		 * @param output the standard output
		 * @param error the standard error
		 * @param status the exit status
		 */
		private Call
		{
			assert output != null;
			assert error != null;
		}
	}
}
