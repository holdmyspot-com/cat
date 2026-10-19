package com.holdmyspot.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.CommandShell;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Verifies that each shell quotes, joins and splits words the way that shell reads them, and runs a command the way
 * Codex runs it. The shells are text formats, so every platform tests both.
 */
public final class CommandShellTest
{
	/**
	 * Creates a test instance.
	 */
	public CommandShellTest()
	{
	}

	/**
	 * Verifies that a POSIX command leaves plain words bare and single-quotes the others, closing the quotes around a
	 * single quote.
	 */
	@Test
	public void posixQuotesChangedWords()
	{
		requireThat(CommandShell.POSIX.command("/plugin/bin/read-part", "/tmp/it's here.txt", "4"), "command").
			isEqualTo("/plugin/bin/read-part '/tmp/it'\"'\"'s here.txt' 4");
		requireThat(CommandShell.POSIX.launcher("read-part"), "launcher").isEqualTo("read-part");
	}

	/**
	 * Verifies that a PowerShell command starts with the call operator, leaves plain Windows paths bare, and
	 * single-quotes the other words, doubling each character that PowerShell reads as a single quote.
	 */
	@Test
	public void powerShellQuotesChangedWords()
	{
		requireThat(CommandShell.POWERSHELL.command("C:\\Program Files\\cat\\bin\\read-part.exe",
			"C:\\work\\it's here.txt", "C:\\work\\notes.txt", "4"), "command").
			isEqualTo("& 'C:\\Program Files\\cat\\bin\\read-part.exe' 'C:\\work\\it''s here.txt' C:\\work\\notes.txt 4");
		requireThat(CommandShell.POWERSHELL.quote("a\u2019b"), "curlyQuote").isEqualTo("'a\u2019\u2019b'");
		requireThat(CommandShell.POWERSHELL.quote("$HOME"), "variable").isEqualTo("'$HOME'");
		requireThat(CommandShell.POWERSHELL.launcher("read-part"), "launcher").isEqualTo("read-part.cmd");
	}

	/**
	 * Verifies that a PowerShell command that runs a batch file double-quotes each argument that PowerShell would quote,
	 * so that {@code cmd.exe}, which runs the batch file, reads none of its characters as commands.
	 */
	@Test
	public void powerShellDoubleQuotesBatchArguments()
	{
		requireThat(CommandShell.POWERSHELL.command("C:\\Program Files\\cat\\bin\\read-part.cmd",
			"C:\\R&D\\it's here.txt", "C:\\work\\notes.txt", "4"), "command").
			isEqualTo("& 'C:\\Program Files\\cat\\bin\\read-part.cmd' '\"C:\\R&D\\it''s here.txt\"' C:\\work\\notes.txt 4");
	}

	/**
	 * Verifies that splitting a command gives back the words that each shell's quoting joined, and stops at the end of
	 * the first command.
	 */
	@Test
	public void wordsUndoQuoting()
	{
		List<String> words = List.of("C:\\Program Files\\cat\\bin\\read-part.exe", "C:\\work\\it's here.txt", "4");
		for (CommandShell shell : CommandShell.values())
		{
			String command = shell.command(words) + "; echo done";
			// The words start at the program, after the call operator that a PowerShell command starts with
			String program = command.substring(command.indexOf(shell.quote(words.getFirst())));
			requireThat(shell.words(program), shell.name()).isEqualTo(Optional.of(words));
		}
		requireThat(CommandShell.POWERSHELL.words("\"a`\"b\"\"c\" d"), "doubleQuoted").
			isEqualTo(Optional.of(List.of("a\"b\"c", "d")));
		requireThat(CommandShell.POSIX.words("a \"b\\\"c\" d\\ e"), "posixDoubleQuoted").
			isEqualTo(Optional.of(List.of("a", "b\"c", "d e")));
	}

	/**
	 * Verifies that a command that runs one program splits into its words, and that a command that does more, such as
	 * one that pipes, redirects or runs a second line, has no such words.
	 */
	@Test
	public void simpleCommandWordsCoverWholeCommand()
	{
		for (CommandShell shell : CommandShell.values())
		{
			requireThat(shell.simpleCommandWords("\n  rg -n 'a|b' src\n"), shell.name()).
				isEqualTo(Optional.of(List.of("rg", "-n", "a|b", "src")));
			for (String command : List.of("rg needle | sort", "rg needle > found.txt", "rg needle; ls",
				"rg needle\nls", "rg 'needle"))
			{
				requireThat(shell.simpleCommandWords(command), shell.name()).withContext(command, "command").
					isEqualTo(Optional.empty());
			}
		}
	}

	/**
	 * Verifies that a command whose quote is not closed has no words.
	 */
	@Test
	public void unclosedQuoteHasNoWords()
	{
		for (CommandShell shell : CommandShell.values())
			requireThat(shell.words("read-part 'notes.txt"), shell.name()).isEqualTo(Optional.empty());
	}

	/**
	 * Verifies that a POSIX command runs with {@code bash -c}.
	 */
	@Test
	public void posixRunsCommandWithBash()
	{
		requireThat(CommandShell.POSIX.processArguments("echo \"hi\"", Map.of()), "arguments").
			isEqualTo(List.of("bash", "-c", "echo \"hi\""));
	}

	/**
	 * Verifies that a PowerShell command runs in the first {@code pwsh.exe} on the {@code PATH}, after the line that
	 * Codex runs to make PowerShell read the output of programs as UTF-8, and is quoted so that the double quotes and
	 * backslashes that it holds reach PowerShell unchanged.
	 *
	 * @throws IOException if the temporary folders cannot be created
	 */
	@Test
	public void powerShellRunsQuotedCommand() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path empty = cleanup.add(Files.createTempDirectory("command-shell-"));
			Path folder = cleanup.add(Files.createTempDirectory("command-shell-"));
			Path pwsh = Files.createFile(folder.resolve("pwsh.exe"));
			String path = empty + File.pathSeparator + folder;

			List<String> arguments = CommandShell.POWERSHELL.processArguments("Write-Output \"a \\\"b\" C:\\dir\\",
				Map.of("Path", path));

			requireThat(arguments, "arguments").isEqualTo(List.of(pwsh.toString(), "-NoProfile", "-Command",
				"\"try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}\n" +
					"Write-Output \\\"a \\\\\\\"b\\\" C:\\dir\\\\\""));
		}
	}
}
