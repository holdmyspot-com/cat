package com.holdmyspot.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.CodexRunPart;
import com.holdmyspot.cat.codex.CommandShell;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;
import com.holdmyspot.cat.common.fakes.test.Yielding;

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
	 * The folder of the programs that a command finds before the caller's {@code PATH}, in a plugin that the tests do not
	 * create.
	 */
	private static final Path OVERRIDES = Path.of("/plugin/client/run-part-overrides").toAbsolutePath();
	/**
	 * The line separator that the shell's commands end their lines with.
	 */
	private static final String EOL = System.lineSeparator();
	private static final Pattern LAST_PART = Pattern.compile("\\[End of (?<path>[^\n]+)\\. The file was deleted]\n\\z");
	/**
	 * The line that ends the standard output of {@code run-part} when it cannot save the output.
	 */
	private static final String UNSAVED_END = "[End of output]\n";
	/**
	 * A folder that no CAT project contains, so that {@code run-part} saves the output of a command that runs there
	 * under {@code TMPDIR}.
	 */
	private static final Path OUTSIDE_PROJECT = Path.of("/").toAbsolutePath();
	/**
	 * A Codex thread ID in canonical form.
	 */
	private static final String THREAD_ID = "01a147d8-bf56-7283-92b4-12dcaeb53738";

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
			requireThat(call.output(), "output").isEqualTo("out" + EOL + "err" + EOL + "[End of " + saved +
				". The file was deleted]\n");
			requireThat(call.status(), "status").isEqualTo(3);
			requireThat(Files.readString(Path.of(saved + ".exit-status")), "exitStatus").isEqualTo("3\n");
			requireThat(call.error(), "error").isEmpty();
			requireThat(call.commandError(), "commandError").isEmpty();
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
	 * Verifies that a command runs in its folder and that, in a Codex thread, its output is saved in the temporary folder
	 * of the thread's CAT session directory in the nearest project that contains that folder, so that it stays inside the
	 * project.
	 *
	 * @throws IOException if the temporary folders cannot be created
	 */
	@Test
	public void savesOutputInSessionTemp() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Files.createDirectories(project.resolve(".cat"));
			Path folder = Files.createDirectories(project.resolve("src/main"));
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Path sessionTemp = project.toAbsolutePath().normalize().resolve(".cat/sessions").resolve(THREAD_ID).
				resolve("temp");

			Call call = runPart(script("pwd", "(Get-Location).Path"), false, folder, Map.of("TMPDIR", temporary.toString(),
				"CODEX_THREAD_ID", THREAD_ID, "CODEX_HOME", temporary.toString()));

			Matcher lastPart = LAST_PART.matcher(call.output());
			requireThat(lastPart.find(), "lastPart").isTrue();
			Path saved = Path.of(lastPart.group("path"));
			requireThat(saved.getParent().getParent(), "folder").isEqualTo(sessionTemp);
			requireThat(sessionTemp.resolveSibling("session.json"), "marker").isRegularFile();
			// pwd prints the folder's real path, which differs where the temporary folder is a symbolic link, as on macOS
			requireThat(call.output(), "output").isEqualTo(folder.toRealPath() + EOL + "[End of " + saved +
				". The file was deleted]\n");
		}
	}

	/**
	 * Verifies that without {@code CODEX_THREAD_ID} the output is saved under {@code TMPDIR} even inside a CAT project
	 * that holds the shared temporary folder of earlier releases, and that no CAT session directory is created.
	 *
	 * @throws IOException if the temporary folders cannot be created
	 */
	@Test
	public void fallsBackWithoutThreadId() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Path shared = Files.createDirectories(project.resolve(".cat/work/temp"));
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));

			Call call = runPart(script("pwd", "(Get-Location).Path"), false, project, Map.of("TMPDIR",
				temporary.toString()));

			Matcher lastPart = LAST_PART.matcher(call.output());
			requireThat(lastPart.find(), "lastPart").isTrue();
			Path saved = Path.of(lastPart.group("path"));
			requireThat(saved.getParent().getParent(), "folder").isEqualTo(temporary);
			try (Stream<Path> entries = Files.list(shared))
			{
				requireThat(entries.toList(), "sharedEntries").isEmpty();
			}
			requireThat(Files.exists(project.resolve(".cat/sessions")), "sessionsRootExists").isFalse();
		}
	}

	/**
	 * Verifies that in a Codex thread, output too long for one part ends with the {@code read-part} command that prints
	 * the next part, and that the first part exits with status 0 whatever the command's status. Codex's
	 * {@code config.toml} sets a small output budget so that a short command fills more than one part.
	 *
	 * @throws IOException if the temporary folder cannot be created or the configuration cannot be written
	 */
	@Test
	public void longOutputNamesNextPart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Map<String, String> environment = Map.of("TMPDIR", temporary.toString(), "CODEX_THREAD_ID", "thread",
				"CODEX_HOME", temporary.toString());
			Files.writeString(temporary.resolve("config.toml"), "tool_output_token_limit = 250\n");

			Call call = runPart(script("seq 1 200; exit 4", "1..200; exit 4"), false, OUTSIDE_PROJECT, environment);

			requireThat(call.output(), "output").startsWith("1" + EOL + "2" + EOL + "3" + EOL).
				matches("(?s).*\\[More output follows: [0-9]+ of the output's [0-9]+ bytes remain, in at least [0-9]+ " +
					"more parts?\\. To read the next part, run: " +
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
	 * that Windows associates with the file. {@code run-part} limits a PowerShell search itself.
	 *
	 * @throws IOException if the temporary files cannot be written
	 */
	@Test
	public void findsRipgrepOverrideOnlyInPosixShell() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-")).toAbsolutePath();
			Path plugin = temporary.resolve("plugin");
			Path overrides = Files.createDirectories(plugin.resolve("client/run-part-overrides"));
			Path override = overrides.resolve("rg");
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
			if (SHELL == CommandShell.POWERSHELL)
				environment.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");

			Call call = runPart(script("printf '%s|%s\\n' \"$(command -v rg)\" \"${CAT_RUN_PART_OUTPUT-unset}\"",
				"Write-Output \"$((Get-Command -Name rg).Source)|$env:CAT_RUN_PART_OUTPUT\""), false, OUTSIDE_PROJECT,
				plugin.resolve("bin").resolve(SHELL.launcher("read-part")), overrides, environment);

			Matcher lastPart = LAST_PART.matcher(call.output());
			requireThat(lastPart.find(), "lastPart").withContext(call.error(), "error").isTrue();
			requireThat(call.output(), "output").isEqualTo(expected + "|" + lastPart.group("path") + EOL +
				"[End of " + lastPart.group("path") + ". The file was deleted]\n");
		}
	}

	/**
	 * Verifies that a PowerShell command that is one {@code rg} search runs with each line cut to its first 500 bytes,
	 * keeps its output and exit status within the line limit, and is replaced by a request to narrow the search, with
	 * exit status 2, above it.
	 *
	 * @throws IOException if the temporary files cannot be written
	 */
	@Test
	public void powerShellSearchOutputIsLimited() throws IOException
	{
		if (SHELL != CommandShell.POWERSHELL)
			throw new SkipException("A POSIX shell runs the plugin's rg override, which Bats tests");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-")).toAbsolutePath();
			Path caller = Files.createDirectories(temporary.resolve("caller"));
			Files.writeString(caller.resolve("rg.cmd"), "@echo %*\r\n@echo b\r\n@echo c\r\n@exit /b 1\r\n");
			// The caller's rg.cmd runs in cmd.exe, which Windows finds through the system folders
			Map<String, String> environment = new HashMap<>();
			for (String name : List.of("SystemRoot", "ComSpec"))
			{
				String value = System.getenv(name);
				if (value != null)
					environment.put(name, value);
			}
			environment.put("PATH", caller + File.pathSeparator + System.getenv("PATH"));
			environment.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");
			environment.put("TMPDIR", temporary.toString());

			environment.put("CAT_RG_LINE_LIMIT", "3");
			Call within = runPart("rg -n needle", false, OUTSIDE_PROJECT, environment);
			requireThat(within.output(), "within").startsWith("--max-columns=500 --max-columns-preview -n needle" +
				EOL + "b" + EOL + "c" + EOL);
			requireThat(within.status(), "withinStatus").withContext(within.output(), "output").isEqualTo(1);

			environment.put("CAT_RG_LINE_LIMIT", "2");
			Call above = runPart("rg -n needle", false, OUTSIDE_PROJECT, environment);
			requireThat(above.output(), "above").startsWith("rg: the search produced more than 2 lines");
			requireThat(above.status(), "aboveStatus").withContext(above.output(), "output").isEqualTo(2);
		}
	}

	/**
	 * Verifies that when {@code run-part} cannot create the folder that would save the output, as in a read-only
	 * sandbox, the command still runs and behaves as it would without {@code run-part}: its standard output and
	 * standard error reach the caller's, unsaved and unchanged, {@code run-part} prints only the line
	 * {@code [End of output]} after the standard output, and the call exits with the command's exit status. Outside a
	 * CAT project the folder's parent is {@code TMPDIR}; in a Codex thread inside a CAT project it is the project's
	 * {@code .cat/sessions}. Each of them is a regular file here, so no folder can be created inside it whatever the
	 * user's permissions.
	 *
	 * @throws IOException if the temporary files cannot be written
	 */
	@Test
	public void unsavableOutputPassesThrough() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Path file = Files.writeString(temporary.resolve("file"), "");
			Path project = Files.createDirectories(temporary.resolve("project"));
			Files.createDirectories(project.resolve(".cat"));
			Files.writeString(project.resolve(".cat/sessions"), "");
			String command = script("printf 'out|%s\\n' \"${CAT_RUN_PART_OUTPUT-unset}\"\necho err >&2\nexit 3\n",
				"Write-Output \"out|$(if ($null -eq $env:CAT_RUN_PART_OUTPUT) { 'unset' } else { 'set' })\"\n" +
					"[Console]::Error.WriteLine('err')\nexit 3\n");

			List<Call> calls = List.of(
				runPart(command, false, OUTSIDE_PROJECT, Map.of("TMPDIR", file.resolve("temp").toString())),
				runPart(command, false, project, Map.of("TMPDIR", temporary.toString(), "CODEX_THREAD_ID", THREAD_ID,
					"CODEX_HOME", temporary.toString())));

			for (Call call : calls)
			{
				requireThat(call.output(), "output").withContext(call.error(), "error").
					isEqualTo("out|unset" + EOL + UNSAVED_END);
				requireThat(call.commandError(), "commandError").isEqualTo("err" + EOL);
				requireThat(call.error(), "error").isEmpty();
				requireThat(call.status(), "status").isEqualTo(3);
			}
		}
	}

	/**
	 * Verifies that when {@code run-part} cannot save the output, the line {@code [End of output]} that ends its
	 * standard output is on a line of its own: after a standard output that does not end with a line feed, a line feed
	 * comes first, and a command that writes no standard output gives only that line. The command's standard error and
	 * exit status stay unchanged.
	 *
	 * @throws IOException if the temporary files cannot be written
	 */
	@Test
	public void unsavableOutputEndsWithLine() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Path file = Files.writeString(temporary.resolve("file"), "");
			Map<String, String> environment = Map.of("TMPDIR", file.resolve("temp").toString());

			Call unfinished = runPart(script("printf 'a\\nb'\necho err >&2\nexit 3\n",
				"[Console]::Out.Write(\"a`nb\")\n[Console]::Error.WriteLine('err')\nexit 3\n"), false, OUTSIDE_PROJECT,
				environment);
			requireThat(unfinished.output(), "unfinished").withContext(unfinished.error(), "error").
				isEqualTo("a\nb\n" + UNSAVED_END);
			requireThat(unfinished.commandError(), "unfinishedError").isEqualTo("err" + EOL);
			requireThat(unfinished.status(), "unfinishedStatus").isEqualTo(3);

			Call empty = runPart("exit 4", false, OUTSIDE_PROJECT, environment);
			requireThat(empty.output(), "empty").withContext(empty.error(), "error").isEqualTo(UNSAVED_END);
			requireThat(empty.commandError(), "emptyError").isEmpty();
			requireThat(empty.status(), "emptyStatus").isEqualTo(4);
		}
	}

	/**
	 * Verifies that a command whose output cannot be saved runs in the folder and environment that it would run in if
	 * the output were saved: the caller's locale is restored and, in a POSIX shell, the plugin's {@code run-part}
	 * overrides come first on its {@code PATH}.
	 *
	 * @throws IOException if the temporary files cannot be written
	 */
	@Test
	public void unsavableOutputKeepsCommandEnvironment() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-")).toAbsolutePath();
			Path file = Files.writeString(temporary.resolve("file"), "");
			Path folder = Files.createDirectories(temporary.resolve("folder"));
			String path = Path.of("/usr/bin").toAbsolutePath() + File.pathSeparator + Path.of("/bin").toAbsolutePath();
			Map<String, String> environment = Map.of("TMPDIR", file.resolve("temp").toString(), "PATH", path,
				"LC_CTYPE", "C.UTF-8", "CAT_CALLER_LC_ALL", "+C", "CAT_CALLER_LC_CTYPE", "-");
			String expected = "C|unset|unset";
			if (SHELL == CommandShell.POSIX)
				expected += "|" + OVERRIDES + File.pathSeparator + path;

			// PowerShell may add its own folder to PATH, so only the POSIX command prints it
			Call call = runPart(script("pwd; printf '%s|%s|%s|%s\\n' \"${LC_ALL-unset}\" \"${LC_CTYPE-unset}\" " +
				"\"${CAT_CALLER_LC_ALL-unset}\" \"$PATH\"", "(Get-Location).Path; " +
				variables("LC_ALL", "LC_CTYPE", "CAT_CALLER_LC_ALL")), false, folder, environment);

			// pwd prints the folder's real path, which differs where the temporary folder is a symbolic link, as on macOS
			requireThat(call.output(), "output").withContext(call.error(), "error").
				isEqualTo(folder.toRealPath() + EOL + expected + EOL + UNSAVED_END);
		}
	}

	/**
	 * Verifies that a PowerShell command that is one {@code rg} search, whose output cannot be saved, runs as written,
	 * without the line width and line limit that apply to a saved search, as it would without {@code run-part}.
	 *
	 * @throws IOException if the temporary files cannot be written
	 */
	@Test
	public void unsavableSearchIsNotLimited() throws IOException
	{
		if (SHELL != CommandShell.POWERSHELL)
			throw new SkipException("A POSIX shell runs the plugin's rg override, which Bats tests");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-")).toAbsolutePath();
			Path file = Files.writeString(temporary.resolve("file"), "");
			Path caller = Files.createDirectories(temporary.resolve("caller"));
			Files.writeString(caller.resolve("rg.cmd"), "@echo %*\r\n@echo b\r\n@echo c\r\n@exit /b 1\r\n");
			// The caller's rg.cmd runs in cmd.exe, which Windows finds through the system folders
			Map<String, String> environment = new HashMap<>();
			for (String name : List.of("SystemRoot", "ComSpec"))
			{
				String value = System.getenv(name);
				if (value != null)
					environment.put(name, value);
			}
			environment.put("PATH", caller + File.pathSeparator + System.getenv("PATH"));
			environment.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");
			environment.put("TMPDIR", file.resolve("temp").toString());
			environment.put("CAT_RG_LINE_LIMIT", "1");

			Call call = runPart("rg -n needle", false, OUTSIDE_PROJECT, environment);

			requireThat(call.output(), "output").withContext(call.error(), "error").
				isEqualTo("-n needle" + EOL + "b" + EOL + "c" + EOL + UNSAVED_END);
			requireThat(call.status(), "status").isEqualTo(1);
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
	 * @throws IOException if the file of the command's own standard error cannot be created, read or removed
	 */
	private static Call runPart(String command, boolean terminal, Path workingDirectory,
		Map<String, String> environment) throws IOException
	{
		return runPart(command, terminal, workingDirectory, READ_PART, OVERRIDES, environment);
	}

	/**
	 * Runs {@code run-part} from a plugin's {@code bin} folder. The command's own standard error, which reaches the
	 * caller only when {@code run-part} cannot save the output, goes to a file that this call reads back.
	 *
	 * @param command the command on standard input
	 * @param terminal {@code true} if standard input is a terminal
	 * @param workingDirectory the folder that the command runs in
	 * @param readPart the path of the plugin's {@code read-part} launcher
	 * @param overrides the folder of the programs that the command finds before the caller's {@code PATH}
	 * @param environment the environment
	 * @return the call's streams and exit status
	 * @throws IOException if the file of the command's own standard error cannot be created, read or removed
	 */
	private static Call runPart(String command, boolean terminal, Path workingDirectory, Path readPart,
		Path overrides, Map<String, String> environment) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path streams = cleanup.add(Files.createTempDirectory("run-part-streams-"));
			Path commandError = streams.resolve("stderr");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream error = new ByteArrayOutputStream();
			int status = CodexRunPart.run(new String[0], new ByteArrayInputStream(command.getBytes(UTF_8)), terminal,
				workingDirectory, SHELL, readPart, overrides, environment, output, new PrintStream(error, true, UTF_8),
				Redirect.to(commandError.toFile()), Yielding.waiting());
			return new Call(output.toString(UTF_8), error.toString(UTF_8), status, readIfPresent(commandError));
		}
	}

	/**
	 * Returns a file's text.
	 *
	 * @param file the file
	 * @return the file's text, or an empty string if the file does not exist
	 * @throws IOException if the file cannot be read
	 */
	private static String readIfPresent(Path file) throws IOException
	{
		if (Files.notExists(file))
			return "";
		return Files.readString(file);
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
	 * @param output the standard output of {@code run-part}
	 * @param error the standard error of {@code run-part}
	 * @param status the exit status
	 * @param commandError the command's own standard error, which reaches the caller only when {@code run-part} cannot
	 *   save the output
	 */
	private record Call(String output, String error, int status, String commandError)
	{
		/**
		 * Creates a call.
		 *
		 * @param output the standard output of {@code run-part}
		 * @param error the standard error of {@code run-part}
		 * @param status the exit status
		 * @param commandError the command's own standard error
		 */
		private Call
		{
			assert output != null;
			assert error != null;
			assert commandError != null;
		}
	}
}
