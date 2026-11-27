package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.WindowsCommandLine;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests that {@link WindowsCommandLine} splits a command line as Rust's standard library does, on every platform.
 */
public final class WindowsCommandLineTest
{
	/**
	 * Creates a test instance.
	 */
	public WindowsCommandLineTest()
	{
	}

	/**
	 * Verifies that the launch of a Codex session, as Windows holds it, splits into the arguments that Codex reads: the
	 * program, the flags and the paths that hold backslashes, and a setting whose value holds escaped quotes.
	 */
	@Test
	public void splitsCodexLaunch()
	{
		// Requirement: R-I35
		String line = "\"C:\\tools\\codex\\vendor\\bin\\codex.exe\" exec --skip-git-repo-check --json -C " +
			"C:\\wi177\\proj-alpha -c \"model_reasoning_effort=\\\"low\\\"\" \"Please tidy the notes in the alpha folder.\"";

		List<String> arguments = WindowsCommandLine.split(line);

		requireThat(arguments, "arguments").isEqualTo(List.of("C:\\tools\\codex\\vendor\\bin\\codex.exe", "exec",
			"--skip-git-repo-check", "--json", "-C", "C:\\wi177\\proj-alpha", "-c", "model_reasoning_effort=\"low\"",
			"Please tidy the notes in the alpha folder."));
	}

	/**
	 * Verifies that a launch that selects a profile gives the profile's name as the argument after {@code --profile}.
	 */
	@Test
	public void splitsProfileLaunch()
	{
		// Requirement: R-I35
		String line = "C:\\codex\\codex.exe exec --skip-git-repo-check --json --profile beta -C C:\\wi177\\proj-beta " +
			"-c \"model_reasoning_effort=\\\"high\\\"\"";

		List<String> arguments = WindowsCommandLine.split(line);

		requireThat(arguments, "arguments").isEqualTo(List.of("C:\\codex\\codex.exe", "exec",
			"--skip-git-repo-check", "--json", "--profile", "beta", "-C", "C:\\wi177\\proj-beta", "-c",
			"model_reasoning_effort=\"high\""));
	}

	/**
	 * Verifies that a quoted program path with spaces is one argument, and that its trailing backslash, which would
	 * escape a quote in any later argument, escapes nothing.
	 */
	@Test
	public void takesNoEscapesInFirstArgument()
	{
		// Requirement: R-I35
		List<String> arguments = WindowsCommandLine.split("\"C:\\Program Files\\Codex\\\" next \"two words\"");

		requireThat(arguments, "arguments").isEqualTo(List.of("C:\\Program Files\\Codex\\", "next", "two words"));
	}

	/**
	 * Verifies that a quote inside an unquoted program name starts and ends a quoted part, whose space does not end the
	 * program.
	 */
	@Test
	public void togglesQuotesInUnquotedFirstArgument()
	{
		// Requirement: R-I35
		List<String> arguments = WindowsCommandLine.split("EXE\"x y\" a");

		requireThat(arguments, "arguments").isEqualTo(List.of("EXEx y", "a"));
	}

	/**
	 * Verifies that an even number of backslashes before a quote is halved and the quote starts or ends a quoted part,
	 * and that an odd number is halved and the quote is literal.
	 */
	@Test
	public void halvesBackslashesBeforeQuote()
	{
		// Requirement: R-I35
		requireThat(WindowsCommandLine.split("EXE a\\\\\"b c\"d e"), "even").
			isEqualTo(List.of("EXE", "a\\b cd", "e"));
		requireThat(WindowsCommandLine.split("EXE \"a\\\\\" b"), "evenAtEnd").isEqualTo(List.of("EXE", "a\\", "b"));
		requireThat(WindowsCommandLine.split("EXE a\\\\\\\"b"), "odd").isEqualTo(List.of("EXE", "a\\\"b"));
		requireThat(WindowsCommandLine.split("EXE \\\"a\\\" b"), "escapedOnly").
			isEqualTo(List.of("EXE", "\"a\"", "b"));
	}

	/**
	 * Verifies that backslashes that no quote follows stay as they are.
	 */
	@Test
	public void keepsBackslashesWithoutQuote()
	{
		// Requirement: R-I35
		List<String> arguments = WindowsCommandLine.split("EXE C:\\dir\\ \\\\server\\share a\\b");

		requireThat(arguments, "arguments").isEqualTo(List.of("EXE", "C:\\dir\\", "\\\\server\\share", "a\\b"));
	}

	/**
	 * Verifies that an empty quoted argument is an argument, whether another argument or the end of the line follows
	 * it, and that two quotes inside a quoted part are one literal quote.
	 */
	@Test
	public void keepsEmptyAndDoubledQuotes()
	{
		// Requirement: R-I35
		requireThat(WindowsCommandLine.split("EXE \"\" x"), "middle").isEqualTo(List.of("EXE", "", "x"));
		requireThat(WindowsCommandLine.split("EXE \"\""), "last").isEqualTo(List.of("EXE", ""));
		requireThat(WindowsCommandLine.split("EXE \"\" \"\""), "two").isEqualTo(List.of("EXE", "", ""));
		requireThat(WindowsCommandLine.split("EXE \"a\"\"b\" c"), "doubled").isEqualTo(List.of("EXE", "a\"b", "c"));
	}

	/**
	 * Verifies that runs of spaces and tabs between arguments are one separator, and that a space or tab inside quotes
	 * stays.
	 */
	@Test
	public void separatesOnSpacesAndTabs()
	{
		// Requirement: R-I35
		List<String> arguments = WindowsCommandLine.split("EXE  \ta \t\t\"b \tc\"   d");

		requireThat(arguments, "arguments").isEqualTo(List.of("EXE", "a", "b \tc", "d"));
	}

	/**
	 * Verifies that a quoted part that the line leaves open runs to the end of the line.
	 */
	@Test
	public void closesOpenQuoteAtEnd()
	{
		// Requirement: R-I35
		List<String> arguments = WindowsCommandLine.split("EXE \"a b");

		requireThat(arguments, "arguments").isEqualTo(List.of("EXE", "a b"));
	}

	/**
	 * Verifies that a line with only the program, with or without trailing whitespace, has no arguments after it, and
	 * that an empty line has none at all.
	 */
	@Test
	public void givesNoArguments()
	{
		// Requirement: R-I35
		requireThat(WindowsCommandLine.split("\"C:\\Program Files\\codex.exe\""), "quoted").
			isEqualTo(List.of("C:\\Program Files\\codex.exe"));
		requireThat(WindowsCommandLine.split("codex.exe  \t"), "trailing").isEqualTo(List.of("codex.exe"));
		requireThat(WindowsCommandLine.split(""), "empty").isEqualTo(List.of());
	}
}
