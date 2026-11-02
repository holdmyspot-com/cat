package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestInstructions;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link SessionRequestInstructions}.
 */
public final class SessionRequestInstructionsTest
{
	/**
	 * Creates the test.
	 */
	public SessionRequestInstructionsTest()
	{
	}

	/**
	 * Verifies that the instructions say when to request a session, how many the session may request, which command
	 * makes a request and how to wait again, and that inputs and results pass as file paths.
	 */
	@Test
	public void explainsRequests()
	{
		// Requirement: R-I9
		Path launcher = Path.of("/artifact/bin/request-session").toAbsolutePath();
		String text = SessionRequestInstructions.text(3, launcher, false);
		String command = ShellQuoting.posix(launcher.toString());

		requireThat(text, "text").contains("new session").contains("plugin").contains("up to 3").
			doesNotContain("$").contains(command + " --harness").contains(command + " --await <id>").
			contains("--project").contains("--prompt").contains(" --model <name> --effort <level> ").
			contains("--plugin").contains("--await").contains("file path");
	}

	/**
	 * Verifies that the instructions tell a session whose task names no model or effort to choose them from the values
	 * that the harness lists and to state its choice, rather than leaving it without an instruction.
	 */
	@Test
	public void choosesModelAndEffortWhenTaskNamesNone()
	{
		Path launcher = Path.of("/artifact/bin/request-session").toAbsolutePath();
		String text = SessionRequestInstructions.text(1, launcher, false);

		requireThat(text, "text").contains("use the ones that your task names, and when it names none, choose them " +
			"from the values that the harness lists").contains("state your choice in your reply");
	}

	/**
	 * Verifies that the instructions name both runner launchers beside the request launcher as another way to request
	 * a session, with its records in an input file.
	 */
	@Test
	public void namesRunnerLaunchers()
	{
		// Requirement: R-I9, R-I11
		Path launcher = Path.of("/artifact/bin/request-session").toAbsolutePath();
		String text = SessionRequestInstructions.text(2, launcher, false);

		requireThat(text, "text").contains(ShellQuoting.posix(launcher.resolveSibling("claude-runner").toString())).
			contains(ShellQuoting.posix(launcher.resolveSibling("codex-runner").toString())).contains("--input").
			contains("\"type\":\"turn\"").contains("\"type\":\"complete\"");
	}

	/**
	 * Verifies that both POSIX commands quote a launcher path containing spaces.
	 */
	@Test
	public void quotesPosixLauncher()
	{
		// Requirement: R-I9
		Path launcher = Path.of("/artifact with spaces/bin/request-session").toAbsolutePath();
		String text = SessionRequestInstructions.text(1, launcher, false);

		requireThat(text, "text").contains("'" + launcher + "' --harness").
			contains("'" + launcher + "' --await <id>").doesNotContain("$");
	}

	/**
	 * Verifies that both PowerShell commands invoke a quoted launcher with the call operator.
	 */
	@Test
	public void quotesPowerShellLauncher()
	{
		// Requirement: R-I9
		Path launcher = Path.of("/artifact with spaces/bin/request-session.cmd").toAbsolutePath();
		String text = SessionRequestInstructions.text(1, launcher, true);

		requireThat(text, "text").contains("& '" + launcher + "' --harness").
			contains("& '" + launcher + "' --await <id>").doesNotContain("$");
	}

	/**
	 * Verifies that the command environment carries the explicitly selected shell form.
	 */
	@Test
	public void carriesExplicitShellFormToRequestCommand()
	{
		Path requests = Path.of("/run/requests");
		Path responses = Path.of("/run/responses");
		Path requestedRuns = Path.of("/run/requested-runs");
		Path posixLauncher = Path.of("/artifact/bin/request-session");
		Path powerShellLauncher = Path.of("C:/artifact/bin/request-session.cmd");
		SessionRequestAccess posix = new SessionRequestAccess(requests, responses, requestedRuns, posixLauncher,
			SessionRequestAccess.Shell.POSIX);
		SessionRequestAccess powerShell = new SessionRequestAccess(requests, responses, requestedRuns, powerShellLauncher,
			SessionRequestAccess.Shell.POWERSHELL);

		requireThat(posix.environment().get(SessionRequestAccess.SHELL_VARIABLE), "posixShell").isEqualTo("POSIX");
		requireThat(powerShell.environment().get(SessionRequestAccess.SHELL_VARIABLE), "powerShell").
			isEqualTo("POWERSHELL");
	}
}
