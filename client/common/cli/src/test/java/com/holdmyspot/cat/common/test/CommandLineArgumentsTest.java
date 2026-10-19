package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.CommandLineArguments;
import org.testng.annotations.Test;

/**
 * Verifies that the arguments of a program that the Java launcher started are read again from the Windows command
 * line, which holds the characters that the launcher replaced.
 */
public final class CommandLineArgumentsTest
{
	/**
	 * The start of the command line that a jlink launcher runs: the Java runtime, its options and the main class.
	 */
	private static final String LAUNCHER_COMMAND = "\"C:\\Program Files\\cat\\client\\bin\\\\java\" " +
		"-Xlog:all=off:stdout -m com.holdmyspot.cat.codex.cli/com.holdmyspot.cat.codex.CodexReadPart ";

	/**
	 * Creates a test instance.
	 */
	public CommandLineArgumentsTest()
	{
	}

	/**
	 * Verifies that a character outside the ANSI code page comes back from the command line.
	 */
	@Test
	public void recoversReplacedCharacters()
	{
		String[] recovered = CommandLineArguments.recover(new String[]{"C:\\R&D\\notes é?.txt", "4"},
			LAUNCHER_COMMAND + "\"C:\\R&D\\notes é✓.txt\" 4");

		requireThat(recovered, "recovered").isEqualTo(new String[]{"C:\\R&D\\notes é✓.txt", "4"});
	}

	/**
	 * Verifies that the command line's quotes and backslashes are read the way that Windows programs read them:
	 * backslashes escape only a quote, two quotes inside quotes are one quote, and quotes group spaces into one argument.
	 */
	@Test
	public void readsWindowsQuoting()
	{
		String[] recovered = CommandLineArguments.recover(new String[]{"? \"q\"", "x\\\\?", "a\\\"?", "?\"b", "c\\?"},
			LAUNCHER_COMMAND + "\"✓ \\\"q\\\"\" x\\\\✓ a\\\\\\\"✓ \"✓\"\"b\" c\\✓");

		requireThat(recovered, "recovered").isEqualTo(new String[]{"✓ \"q\"", "x\\\\✓", "a\\\"✓", "✓\"b", "c\\✓"});
	}

	/**
	 * Verifies that a character that the code page approximates, rather than replaces, comes back from the command
	 * line.
	 */
	@Test
	public void recoversApproximatedCharacters()
	{
		String[] recovered = CommandLineArguments.recover(new String[]{"Zdzislaw"}, LAUNCHER_COMMAND + "Zdzisław");

		requireThat(recovered, "recovered").isEqualTo(new String[]{"Zdzisław"});
	}

	/**
	 * Verifies that the launcher's arguments are kept when the command line's last arguments are not the same
	 * arguments, so that a command line that is read differently never changes an argument.
	 */
	@Test
	public void keepsArgumentsThatDiffer()
	{
		String[] launcherArguments = {"notes.txt", "?"};

		String[] recovered = CommandLineArguments.recover(launcherArguments, LAUNCHER_COMMAND + "other.txt ✓");

		requireThat(recovered, "recovered").isEqualTo(launcherArguments);
	}

	/**
	 * Verifies that the launcher's arguments are kept when the command line holds fewer arguments.
	 */
	@Test
	public void keepsArgumentsMissingFromCommandLine()
	{
		String[] launcherArguments = {"a", "b", "c", "d", "e", "f", "g"};

		String[] recovered = CommandLineArguments.recover(launcherArguments, "java ✓");

		requireThat(recovered, "recovered").isEqualTo(launcherArguments);
	}
}
