package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Checks supported inspection launcher command binding.
 */
public final class OptimizerToolCommandTest
{
	/**
	 * Creates the command binding test.
	 */
	public OptimizerToolCommandTest()
	{
	}

	/**
	 * Binding a directly quoted inspection launcher appends its exact host identities in option order.
	 */
	@Test
	public void bindsDirectQuotedInspectionLauncher()
	{
		String command = "\"/project/client/bin/optimizer-inspect\" --target prompt.md";
		String bound = OptimizerToolCommand.findInspectionCall(command).orElseThrow().
			bindHostIdentity("session one", "turn two", "tool three", "model four");

		requireThat(bound, "boundCommand").isEqualTo("\"/project/client/bin/optimizer-inspect\"" +
			" --host-session-id 'session one' --host-turn-id 'turn two' --host-tool-use-id 'tool three'" +
			" --host-model-id 'model four' --target prompt.md");
	}

	/**
	 * Binding a single-quoted launcher preserves apostrophes as POSIX shell argument text.
	 */
	@Test
	public void bindsSingleQuotedLauncherApostrophes()
	{
		String command = " '/project/client/bin/optimizer-inspect'";
		String bound = OptimizerToolCommand.findInspectionCall(command).orElseThrow().
			bindHostIdentity("session's", "turn", "tool", "model's value");

		requireThat(bound, "boundCommand").isEqualTo(" '/project/client/bin/optimizer-inspect'" +
			" --host-session-id 'session'\"'\"'s' --host-turn-id 'turn' --host-tool-use-id 'tool'" +
			" --host-model-id 'model'\"'\"'s value'");
	}

	/**
	 * Binding a wrapped inspection launcher preserves the nested shell quoting required by its host command.
	 */
	@Test
	public void bindsWrappedInspectionLauncher()
	{
		String command = "/bin/bash -lc '\"/project/client/bin/optimizer-inspect\" --target prompt.md'";
		String bound = OptimizerToolCommand.findInspectionCall(command).orElseThrow().
			bindHostIdentity("session's", "turn", "tool", "model");

		requireThat(bound, "boundCommand").isEqualTo("/bin/bash -lc '\"/project/client/bin/optimizer-inspect\"" +
			" --host-session-id '\"'\"'session'\"'\"'\"'\"'\"'\"'\"'\"'s'\"'\"'" +
			" --host-turn-id '\"'\"'turn'\"'\"' --host-tool-use-id '\"'\"'tool'\"'\"'" +
			" --host-model-id '\"'\"'model'\"'\"' --target prompt.md'");
	}

	/**
	 * Finding unsupported or unrelated commands returns an empty result.
	 */
	@Test
	public void returnsEmptyForUnsupportedCommands()
	{
		requireThat(OptimizerToolCommand.findInspectionCall("echo unrelated"), "unrelatedCommand").
			isEqualTo(Optional.empty());
		requireThat(OptimizerToolCommand.findInspectionCall("/bin/bash -lc 'echo unrelated'"), "wrappedOtherCommand").
			isEqualTo(Optional.empty());
		requireThat(OptimizerToolCommand.findInspectionCall("/project/client/bin/optimizer-inspect"),
			"unquotedLauncher").isEqualTo(Optional.empty());
	}

	/**
	 * Finding an inspection call rejects a null command.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullCommand()
	{
		OptimizerToolCommand.findInspectionCall(null);
	}

	/**
	 * Binding a command rejects a null host session identity.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullSessionIdentity()
	{
		command().bindHostIdentity(null, "turn", "tool", "model");
	}

	/**
	 * Binding a command rejects a null host turn identity.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullTurnIdentity()
	{
		command().bindHostIdentity("session", null, "tool", "model");
	}

	/**
	 * Binding a command rejects a null host tool-use identity.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullToolUseIdentity()
	{
		command().bindHostIdentity("session", "turn", null, "model");
	}

	/**
	 * Binding a command rejects a null host model identity.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullModelIdentity()
	{
		command().bindHostIdentity("session", "turn", "tool", null);
	}

	/**
	 * Binding a command rejects an empty host session identity.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsEmptySessionIdentity()
	{
		command().bindHostIdentity("", "turn", "tool", "model");
	}

	/**
	 * Binding a command rejects a whitespace-only host turn identity.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankTurnIdentity()
	{
		command().bindHostIdentity("session", " \t", "tool", "model");
	}

	/**
	 * Binding a command rejects a whitespace-only host tool-use identity.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankToolUseIdentity()
	{
		command().bindHostIdentity("session", "turn", "\n", "model");
	}

	/**
	 * Binding a command rejects a whitespace-only host model identity.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankModelIdentity()
	{
		command().bindHostIdentity("session", "turn", "tool", " ");
	}

	/**
	 * Finds a directly quoted supported inspection launcher for identity-binding tests.
	 *
	 * @return supported inspection command
	 */
	private static OptimizerToolCommand command()
	{
		return OptimizerToolCommand.findInspectionCall("\"/project/client/bin/optimizer-inspect\"").
			orElseThrow();
	}
}
