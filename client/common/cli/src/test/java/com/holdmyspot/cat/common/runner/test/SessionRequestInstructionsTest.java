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
			contains("--plugin").contains("--await").contains("file path").contains("[--extra-permissions <file>]");
	}

	/**
	 * Verifies that the instructions open with the decision, before they state how many sessions the session may
	 * request: a session is requested only for work that needs a separate, isolated session, of which they name the
	 * four kinds, and other work that is handed off goes to a subagent or, when none can start, stays with the session.
	 * They do not tell the session to hand off every task that a subagent can do.
	 */
	@Test
	public void decidesOnIsolationBeforeCount()
	{
		Path launcher = Path.of("/artifact/bin/request-session").toAbsolutePath();
		String text = SessionRequestInstructions.text(1, launcher, false);

		int isolation = text.indexOf("Request a session only when the work needs a separate, isolated session");
		int subagent = text.indexOf("Other work that you hand off goes to a subagent; when you cannot start one, that " +
			"work stays with you, and you do not request a session for it");
		int count = text.indexOf("You may request up to 1 session");
		requireThat(isolation, "isolation").isNotNegative();
		requireThat(subagent, "subagent").isGreaterThan(isolation);
		requireThat(count, "count").isGreaterThan(subagent);
		requireThat(text, "text").contains("running the other harness").
			contains("checking a plugin that you wrote or a fresh CAT installation").
			contains("starting from a fresh copy of the project").contains("keeping the work out of your own context").
			doesNotContain("Give work that a subagent can do to a subagent");
	}

	/**
	 * Verifies that the instructions tell a session whose task names no model or effort to choose them from the values
	 * that the harness lists and to state the model and the effort it chose, rather than leaving it without an
	 * instruction.
	 */
	@Test
	public void choosesModelAndEffortWhenTaskNamesNone()
	{
		Path launcher = Path.of("/artifact/bin/request-session").toAbsolutePath();
		String text = SessionRequestInstructions.text(1, launcher, false);

		requireThat(text, "text").contains("use the ones that your task names, and when it names none, choose them " +
			"from the values that the harness lists").contains("state the model and the effort that you chose in your reply");
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
	 * Verifies that the instructions describe a conversation with a requested session: the option of every launcher
	 * that holds one, the folder's two files, each message, the line that ends a turn, and the refused launch's exit
	 * status.
	 */
	@Test
	public void describesConversation()
	{
		Path launcher = Path.of("/artifact/bin/request-session").toAbsolutePath();
		String text = SessionRequestInstructions.text(2, launcher, false);

		requireThat(text, "text").contains("--conversation <folder>").contains("<folder>/to-child.jsonl").
			contains("<folder>/from-child.jsonl").contains("{\"turn\": \"<text>\"}").
			contains("{\"interrupt\": true}").contains("{\"end\": true}").
			contains("{\"turn_end\": <n>, \"status\": \"<status>\"}").contains("background command").
			contains("2 when the run refused the launch");
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
