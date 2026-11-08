package com.holdmyspot.cat.common.release.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.release.EntryCommand;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests the entry command that a message tells the user to run. The user runs it in a terminal whose environment
 * the process that wrote the message cannot see, so the command carries the variables that select the install
 * location whenever that terminal may not have the same values.
 */
public final class EntryCommandTest
{
	/**
	 * The home directory that the user database records for the account.
	 */
	private static final Path ACCOUNT_HOME = Path.of("/home/account");

	/**
	 * Creates a test instance.
	 */
	public EntryCommandTest()
	{
	}

	/**
	 * Verifies that a process whose {@code HOME} is the account's home gets the plain command, which the user's
	 * terminal runs against the same home.
	 */
	@Test
	public void sameHomeGivesPlainCommand()
	{
		EntryCommand command = new EntryCommand(Platform.LINUX_X64, Map.of("HOME", "/home/account"), ACCOUNT_HOME);

		requireThat(command.forHarness("claude"), "command").
			isEqualTo("cat.sh --harness claude");
	}

	/**
	 * Verifies that a path that differs from the account's home only by a trailing separator or a {@code ..} segment
	 * is the same home.
	 */
	@Test
	public void sameHomeSpelledDifferentlyIsPlain()
	{
		EntryCommand command = new EntryCommand(Platform.LINUX_X64, Map.of("HOME", "/home/other/../account/"),
			ACCOUNT_HOME);

		requireThat(command.forHarness("claude"), "command").
			isEqualTo("cat.sh --harness claude");
	}

	/**
	 * Verifies that a process whose {@code HOME} differs from the account's home gets the command that sets
	 * {@code HOME} to the process's value.
	 */
	@Test
	public void differentHomeGivesPrefixedCommand()
	{
		EntryCommand command = new EntryCommand(Platform.LINUX_X64, Map.of("HOME", "/home/session"), ACCOUNT_HOME);

		requireThat(command.forHarness("claude"), "command").
			isEqualTo("HOME=/home/session cat.sh --harness claude");
	}

	/**
	 * Verifies that when the account's home cannot be determined, the command sets {@code HOME} to the process's
	 * value, because nothing shows that the user's terminal has it.
	 */
	@Test
	public void unknownAccountHomeGivesPrefixedCommand()
	{
		EntryCommand command = new EntryCommand(Platform.LINUX_X64, Map.of("HOME", "/home/account"), null);

		requireThat(command.forHarness("codex"), "command").
			isEqualTo("HOME=/home/account cat.sh --harness codex");
	}

	/**
	 * Verifies that a {@code HOME} holding a space and an apostrophe is quoted so that a POSIX shell passes it
	 * unchanged.
	 */
	@Test
	public void quotesHomeWithSpaceAndApostrophe()
	{
		EntryCommand command = new EntryCommand(Platform.LINUX_X64, Map.of("HOME", "/home/Gili's files"),
			ACCOUNT_HOME);

		requireThat(command.forHarness("claude"), "command").
			isEqualTo("HOME='/home/Gili'\"'\"'s files' cat.sh --harness claude");
	}

	/**
	 * Verifies that the harness's home variable is written when it names a directory other than the default one in
	 * the home, and that {@code HOME} is then omitted because it equals the account's home.
	 */
	@Test
	public void writesNonDefaultHarnessHome()
	{
		EntryCommand command = new EntryCommand(Platform.LINUX_X64,
			Map.of("HOME", "/home/account", "CLAUDE_CONFIG_DIR", "/data/claude home"), ACCOUNT_HOME);

		requireThat(command.forHarness("claude"), "command").
			isEqualTo("CLAUDE_CONFIG_DIR='/data/claude home' cat.sh --harness claude");
	}

	/**
	 * Verifies that a harness home variable that names the default directory in the home is omitted, because the entry
	 * script derives it from {@code HOME}, and that the other harness's variable is not written.
	 */
	@Test
	public void omitsDerivedAndOtherHarnessVariables()
	{
		EntryCommand command = new EntryCommand(Platform.LINUX_X64,
			Map.of("HOME", "/home/session", "CODEX_HOME", "/home/session/.codex", "CLAUDE_CONFIG_DIR", "/data/claude"),
			ACCOUNT_HOME);

		requireThat(command.forHarness("codex"), "command").
			isEqualTo("HOME=/home/session cat.sh --harness codex");
	}

	/**
	 * Verifies that on Windows the command is the PowerShell form, and that, because the entry script there takes the
	 * home from the user profile and not from {@code HOME}, only the harness's home variable is written.
	 */
	@Test
	public void windowsWritesOnlyHarnessHome()
	{
		EntryCommand command = new EntryCommand(Platform.WINDOWS_X64,
			Map.of("USERPROFILE", "C:\\Users\\session", "HOME", "C:\\Users\\session", "CODEX_HOME", "D:\\codex"),
			Path.of("C:\\Users\\account"));

		requireThat(command.forHarness("codex"), "command").
			isEqualTo("$env:CODEX_HOME='D:\\codex'; cat.ps1 -Harness codex");
	}

	/**
	 * Verifies that on Windows a process whose environment selects the default homes gets the plain PowerShell
	 * command.
	 */
	@Test
	public void windowsDefaultHomesGivePlainCommand()
	{
		EntryCommand command = new EntryCommand(Platform.WINDOWS_X64,
			Map.of("USERPROFILE", "C:\\Users\\account", "HOME", "C:\\Users\\session"), Path.of("C:\\Users\\account"));

		requireThat(command.forHarness("claude"), "command").isEqualTo("cat.ps1 -Harness claude");
	}
}
