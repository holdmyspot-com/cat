package com.holdmyspot.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.CodexRunPart;
import com.holdmyspot.cat.codex.CommandShell;
import com.holdmyspot.cat.codex.secrets.CodexRunPartAccess;
import com.holdmyspot.cat.codex.secrets.CodexRunPartSecrets;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.GitAnswers;
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
import java.util.Optional;
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
 * <p>
 * Where the output is saved, when it cannot be, and what {@code run-part} prints around a command's output are
 * tested through {@link CodexRunPartAccess} with given command output and exit status. The tests that run a real
 * command through {@code bash} or PowerShell prove only how the shell and the process deliver the command, its input,
 * environment, output and exit status.
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
	private static final CodexRunPartAccess ACCESS = CodexRunPartSecrets.getCodexRunPartAccess();

	/**
	 * Creates a test instance.
	 */
	public CodexRunPartTest()
	{
	}

	/**
	 * Verifies, as the end-to-end check of the processes that {@code run-part} starts, that a real command's standard
	 * output and standard error both reach the saved output that {@code run-part} prints, and that its exit status
	 * reaches the caller.
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

			requireThat(call.output(), "output").startsWith("out" + EOL + "err" + EOL + "[End of ");
			requireThat(call.status(), "status").isEqualTo(3);
			requireThat(call.commandError(), "commandError").isEmpty();
		}
	}

	/**
	 * Verifies that the output of a command is saved in a new, empty {@code output.txt} under {@code TMPDIR} when no
	 * CAT project contains the folder that the command runs in.
	 *
	 * @throws IOException if the temporary folder cannot be created or read
	 */
	@Test
	public void savesOutsideProjectUnderTmpdir() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			ByteArrayOutputStream error = new ByteArrayOutputStream();

			Path saved = ACCESS.createSavedOutput(OUTSIDE_PROJECT, Map.of("TMPDIR", temporary.toString()),
				new PrintStream(error, true, UTF_8), Yielding.waiting(), GitAnswers.IGNORED).orElseThrow();

			requireThat(saved.getParent().getParent(), "folder").isEqualTo(temporary);
			requireThat(saved.getFileName().toString(), "name").isEqualTo("output.txt");
			requireThat(saved.getParent().getFileName().toString(), "folderName").startsWith("run-part-");
			requireThat(Files.size(saved), "size").isEqualTo(0L);
			requireThat(error.toString(UTF_8), "error").isEmpty();
		}
	}

	/**
	 * Verifies that a saved output that fits in one part is printed whole, ending with the line that names the saved
	 * file and says that it was deleted, that the call exits with the command's exit status, and that the status is
	 * saved next to the output for {@code read-part}.
	 *
	 * @throws IOException if the temporary folder cannot be created or read
	 */
	@Test
	public void lastPartExitsWithCommandStatus() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Map<String, String> environment = Map.of("TMPDIR", temporary.toString());
			Path saved = savedOutput(environment, "out\nerr\n");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream error = new ByteArrayOutputStream();

			int status = ACCESS.printSaved(saved, 3, 0, SHELL, READ_PART, environment, output,
				new PrintStream(error, true, UTF_8));

			requireThat(output.toString(UTF_8), "output").isEqualTo("out\nerr\n[End of " + saved +
				". The file was deleted]\n");
			requireThat(status, "status").isEqualTo(3);
			requireThat(Files.readString(Path.of(saved + ".exit-status")), "exitStatus").isEqualTo("3\n");
			requireThat(error.toString(UTF_8), "error").isEmpty();
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
	 * Verifies that in a Codex thread, the output of a command is saved in the temporary folder of the thread's CAT
	 * session directory in the nearest project that contains the folder that the command runs in, so that it stays
	 * inside the project, and that the session directory's marker is created.
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

			Optional<Path> saved = ACCESS.createSavedOutput(folder, Map.of("TMPDIR", temporary.toString(),
				"CODEX_THREAD_ID", THREAD_ID, "CODEX_HOME", temporary.toString()), new PrintStream(
				new ByteArrayOutputStream(), true, UTF_8), Yielding.waiting(), GitAnswers.IGNORED);

			requireThat(saved.orElseThrow().getParent().getParent(), "folder").isEqualTo(sessionTemp);
			requireThat(sessionTemp.resolveSibling("session.json"), "marker").isRegularFile();
		}
	}

	/**
	 * Verifies that a command whose output is saved runs in its folder.
	 *
	 * @throws IOException if the temporary folders cannot be created
	 */
	@Test
	public void commandRunsInItsFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Path folder = Files.createDirectories(temporary.resolve("folder"));

			Call call = runPart(script("pwd", "(Get-Location).Path"), false, folder, Map.of("TMPDIR",
				temporary.toString()));

			// pwd prints the folder's real path, which differs where the temporary folder is a symbolic link, as on macOS
			requireThat(call.output(), "output").startsWith(folder.toRealPath() + EOL + "[End of ");
		}
	}

	/**
	 * Verifies that when {@code run-part} creates the thread's CAT session directory in a Git work tree that does not
	 * ignore it, it adds CAT's lines to the project's {@code .gitignore} and its standard error, which the agent reads,
	 * says that the change is the user's to commit and not the agent's.
	 *
	 * @throws IOException if the temporary folders cannot be created
	 */
	@Test
	public void reportsAddedIgnoreLinesForAgent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Files.createDirectories(project.resolve(".cat"));
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));

			ByteArrayOutputStream error = new ByteArrayOutputStream();

			ACCESS.createSavedOutput(project, Map.of("TMPDIR", temporary.toString(), "CODEX_THREAD_ID", THREAD_ID,
				"CODEX_HOME", temporary.toString()), new PrintStream(error, true, UTF_8), Yielding.waiting(),
				GitAnswers.NOT_IGNORED).orElseThrow();

			requireThat(error.toString(UTF_8), "error").contains("CAT added /.cat/sessions/ to " + project.toAbsolutePath().
				normalize().resolve(".gitignore")).
				contains("Tell the user that this change is theirs to commit; do not stage or commit it.");
			requireThat(Files.readString(project.resolve(".gitignore"), UTF_8), "gitignore").
				endsWith("\n/.cat/sessions/\n");
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

			Path saved = ACCESS.createSavedOutput(project, Map.of("TMPDIR", temporary.toString()), new PrintStream(
				new ByteArrayOutputStream(), true, UTF_8), Yielding.waiting(), GitAnswers.IGNORED).orElseThrow();

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
			StringBuilder lines = new StringBuilder();
			for (int line = 1; line <= 200; ++line)
				lines.append(line).append('\n');
			Path saved = savedOutput(environment, lines.toString());
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			int status = ACCESS.printSaved(saved, 4, 0, SHELL, READ_PART, environment, output,
				new PrintStream(new ByteArrayOutputStream(), true, UTF_8));

			requireThat(output.toString(UTF_8), "output").startsWith("1\n2\n3\n").
				matches("(?s).*\\[More output follows: [0-9]+ of the output's [0-9]+ bytes remain, in at least [0-9]+ " +
					"more parts?\\. To read the next part, run: " +
					Pattern.quote(SHELL.command(READ_PART.toString())) + " .+[/\\\\]output\\.txt[\"']* [1-9][0-9]*]\n");
			requireThat(status, "status").isEqualTo(0);
			requireThat(Files.readString(Path.of(saved + ".exit-status")), "exitStatus").isEqualTo("4\n");
		}
	}

	/**
	 * Verifies that the saved output of a PowerShell {@code rg} search with more lines than the line limit is replaced
	 * by a request to narrow the search, with exit status 2, and that output within the limit keeps its lines and the
	 * search's exit status.
	 *
	 * @throws IOException if the temporary folder cannot be created or read
	 */
	@Test
	public void searchAboveLineLimitIsReplaced() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Map<String, String> environment = Map.of("TMPDIR", temporary.toString());
			PrintStream error = new PrintStream(new ByteArrayOutputStream(), true, UTF_8);

			Path within = savedOutput(environment, "a\nb\nc\n");
			ByteArrayOutputStream withinOutput = new ByteArrayOutputStream();
			int withinStatus = ACCESS.printSaved(within, 1, 3, SHELL, READ_PART, environment, withinOutput, error);
			requireThat(withinOutput.toString(UTF_8), "within").startsWith("a\nb\nc\n[End of ");
			requireThat(withinStatus, "withinStatus").isEqualTo(1);

			Path above = savedOutput(environment, "a\nb\nc\n");
			ByteArrayOutputStream aboveOutput = new ByteArrayOutputStream();
			int aboveStatus = ACCESS.printSaved(above, 1, 2, SHELL, READ_PART, environment, aboveOutput, error);
			requireThat(aboveOutput.toString(UTF_8), "above").startsWith("rg: the search produced more than 2 lines");
			requireThat(aboveStatus, "aboveStatus").isEqualTo(2);
			requireThat(Files.readString(Path.of(above + ".exit-status")), "aboveExitStatus").isEqualTo("2\n");
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
			requireThat(call.output(), "output").startsWith(expected + "|" + lastPart.group("path") + EOL);
		}
	}

	/**
	 * Verifies that PowerShell runs a command that is one {@code rg} search with the arguments that cut each line to its
	 * first 500 bytes, and that the search's output and exit status reach the caller.
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
		}
	}

	/**
	 * Verifies that {@code run-part} finds no file to save the output in when it cannot create the folder that would
	 * hold it, as in a read-only sandbox, so that the command runs without {@code run-part}. Outside a CAT project the
	 * folder's parent is {@code TMPDIR}; in a Codex thread inside a CAT project it is the project's
	 * {@code .cat/sessions}. Each of them is a regular file here, so no folder can be created inside it whatever the
	 * user's permissions.
	 *
	 * @throws IOException if the temporary files cannot be written
	 */
	@Test
	public void unsavableOutputHasNoFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Path file = Files.writeString(temporary.resolve("file"), "");
			Path project = Files.createDirectories(temporary.resolve("project"));
			Files.createDirectories(project.resolve(".cat"));
			Files.writeString(project.resolve(".cat/sessions"), "");
			ByteArrayOutputStream error = new ByteArrayOutputStream();
			PrintStream errorStream = new PrintStream(error, true, UTF_8);

			Optional<Path> outside = ACCESS.createSavedOutput(OUTSIDE_PROJECT, Map.of("TMPDIR",
				file.resolve("temp").toString()), errorStream, Yielding.waiting(), GitAnswers.IGNORED);
			Optional<Path> session = ACCESS.createSavedOutput(project, Map.of("TMPDIR", temporary.toString(),
				"CODEX_THREAD_ID", THREAD_ID, "CODEX_HOME", temporary.toString()), errorStream, Yielding.waiting(),
				GitAnswers.IGNORED);

			requireThat(outside, "outside").isEmpty();
			requireThat(session, "session").isEmpty();
			requireThat(error.toString(UTF_8), "error").isEmpty();
		}
	}

	/**
	 * Verifies that when {@code run-part} cannot save the output, a real command's standard output and standard error
	 * reach the caller's unchanged, the command is not told of a saved output, and the call exits with the command's
	 * exit status.
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
			String command = script("printf 'out|%s\\n' \"${CAT_RUN_PART_OUTPUT-unset}\"\necho err >&2\nexit 3\n",
				"Write-Output \"out|$(if ($null -eq $env:CAT_RUN_PART_OUTPUT) { 'unset' } else { 'set' })\"\n" +
					"[Console]::Error.WriteLine('err')\nexit 3\n");

			Call call = runPart(command, false, OUTSIDE_PROJECT, Map.of("TMPDIR", file.resolve("temp").toString()));

			requireThat(call.output(), "output").withContext(call.error(), "error").
				startsWith("out|unset" + EOL);
			requireThat(call.commandError(), "commandError").isEqualTo("err" + EOL);
			requireThat(call.error(), "error").isEmpty();
			requireThat(call.status(), "status").isEqualTo(3);
		}
	}

	/**
	 * Verifies that when {@code run-part} cannot save the output, it copies the command's standard output unchanged
	 * and ends it with the line {@code [End of output]} on a line of its own: after a standard output that does not
	 * end with a line feed, a line feed comes first, and a command that writes no standard output gives only that line.
	 *
	 * @throws IOException if the output cannot be copied
	 */
	@Test
	public void unsavableOutputEndsWithLine() throws IOException
	{
		ByteArrayOutputStream unfinished = new ByteArrayOutputStream();
		boolean unfinishedEndsWithLine = ACCESS.copyOutput(new ByteArrayInputStream("a\nb".getBytes(UTF_8)),
			unfinished);
		unfinished.write(ACCESS.unsavedEnd(unfinishedEndsWithLine).getBytes(UTF_8));
		requireThat(unfinished.toString(UTF_8), "unfinished").isEqualTo("a\nb\n" + UNSAVED_END);

		ByteArrayOutputStream finished = new ByteArrayOutputStream();
		boolean finishedEndsWithLine = ACCESS.copyOutput(new ByteArrayInputStream("a\nb\n".getBytes(UTF_8)), finished);
		finished.write(ACCESS.unsavedEnd(finishedEndsWithLine).getBytes(UTF_8));
		requireThat(finished.toString(UTF_8), "finished").isEqualTo("a\nb\n" + UNSAVED_END);

		ByteArrayOutputStream empty = new ByteArrayOutputStream();
		boolean emptyEndsWithLine = ACCESS.copyOutput(new ByteArrayInputStream(new byte[0]), empty);
		empty.write(ACCESS.unsavedEnd(emptyEndsWithLine).getBytes(UTF_8));
		requireThat(empty.toString(UTF_8), "empty").isEqualTo(UNSAVED_END);
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
				startsWith(folder.toRealPath() + EOL + expected + EOL);
		}
	}

	/**
	 * Verifies that a PowerShell command that is one {@code rg} search runs with each line cut to its first 500 bytes
	 * only when its output is saved: when its output cannot be saved it runs as written, without the line width that
	 * applies to a saved search, as it would without {@code run-part}. Other commands, and a search in a POSIX shell,
	 * run as written.
	 */
	@Test
	public void unsavableSearchIsNotLimited()
	{
		String search = "rg -n needle";

		requireThat(ACCESS.commandToRun(search, CommandShell.POWERSHELL, true), "saved").
			isEqualTo("rg --max-columns=500 --max-columns-preview -n needle");
		requireThat(ACCESS.commandToRun(search, CommandShell.POWERSHELL, false), "unsaved").isEqualTo(search);
		requireThat(ACCESS.commandToRun(search, CommandShell.POSIX, true), "posix").isEqualTo(search);
		requireThat(ACCESS.commandToRun("Get-ChildItem", CommandShell.POWERSHELL, true), "other").
			isEqualTo("Get-ChildItem");
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
	 * Returns a file in which {@code run-part} saves a command's output, holding output that the test supplies in place
	 * of a command's.
	 *
	 * @param environment the environment, whose {@code TMPDIR} holds the file
	 * @param content     the command's output
	 * @return the file
	 * @throws IOException if the file cannot be created or written
	 */
	private static Path savedOutput(Map<String, String> environment, String content) throws IOException
	{
		Path saved = ACCESS.createSavedOutput(OUTSIDE_PROJECT, environment, new PrintStream(new ByteArrayOutputStream(),
			true, UTF_8), Yielding.waiting(), GitAnswers.IGNORED).orElseThrow();
		return Files.writeString(saved, content, UTF_8);
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
