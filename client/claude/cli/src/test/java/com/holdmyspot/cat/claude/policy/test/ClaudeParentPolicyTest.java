package com.holdmyspot.cat.claude.policy.test;

import com.holdmyspot.cat.claude.policy.ClaudeParentPolicy;
import com.holdmyspot.cat.claude.policy.ClaudePolicyReader;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a runner of CAT's Claude Code plugin finds the sandbox policy of the session whose command runs it from
 * the command's environment, and names the Claude Code settings that would allow what a child lacks.
 */
public final class ClaudeParentPolicyTest
{
	/**
	 * The user's home folder, which no test reads.
	 */
	private static final Path HOME = Path.of("/home/user").toAbsolutePath();

	/**
	 * Creates the test.
	 */
	public ClaudeParentPolicyTest()
	{
	}

	/**
	 * Returns the environments of commands that no sandbox confines: one that no harness runs, and one that Claude
	 * Code runs outside its sandbox.
	 *
	 * @return each environment
	 */
	@DataProvider
	public Object[][] unsandboxedEnvironments()
	{
		return new Object[][]{{Map.of("PATH", "/bin")}, {Map.of("CLAUDECODE", "1", "CLAUDE_PID", "4242")}};
	}

	/**
	 * Verifies that a command that no sandbox confines has the unsandboxed policy, which gives a child exactly what it
	 * needs.
	 *
	 * @param environment the command's environment
	 * @throws IOException   if the policy cannot be read
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test(dataProvider = "unsandboxedEnvironments")
	public void unconfinedCommandIsUnsandboxed(Map<String, String> environment) throws IOException, PolicyRefusal
	{
		ParentPolicy policy = new ClaudeParentPolicy(new ClaudePolicyReader(), environment, HOME, "user").read();

		requireThat(policy, "policy").isEqualTo(ParentPolicy.UNSANDBOXED);
	}

	/**
	 * Verifies that a command that Codex runs is refused with the plugin whose runner reads a Codex session's policy.
	 *
	 * @throws IOException   if the policy cannot be read
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void refusesCodexCommand() throws IOException, PolicyRefusal
	{
		ClaudeParentPolicy source = new ClaudeParentPolicy(new ClaudePolicyReader(),
			Map.of("CODEX_THREAD_ID", "01a1a8eb-3d83-7d73-b243-c636c2041ee1"), HOME, "user");
		try
		{
			ParentPolicy policy = source.read();
			throw new AssertionError("The command was not refused: " + policy);
		}
		catch (SessionRefusedException e)
		{
			requireThat(e.getMessage(), "message").contains("Codex runs this command").
				contains("Run the runner of CAT's Codex plugin instead");
		}
	}

	/**
	 * Verifies that a command that Claude Code runs in its sandbox reads the policy of the session that its
	 * environment names, which Claude Code's record of the session identifies, and is refused when Claude Code keeps no
	 * such record.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void sandboxedCommandReadsItsSession() throws IOException
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			ClaudeParentPolicy source = new ClaudeParentPolicy(new ClaudePolicyReader(), Map.of("CLAUDECODE", "1",
				"SANDBOX_RUNTIME", "1", "CLAUDE_PID", "4242", "CLAUDE_CODE_SESSION_ID",
				"5d2c4a8e-1f3b-4c6d-9e7f-8a9b0c1d2e3f"), fixture.home, "user");
			try
			{
				ParentPolicy policy = source.read();
				throw new AssertionError("The command was not refused: " + policy);
			}
			catch (PolicyRefusal e)
			{
				requireThat(e.reason(), "reason").contains(fixture.configuration.resolve("sessions/4242.json").
					toString());
			}
		}
	}

	/**
	 * Returns the writes and hosts that a session's sandbox lacks, and the instruction that names the settings that
	 * allow them.
	 *
	 * @return the writes, the hosts and the instruction
	 */
	@DataProvider
	public Object[][] grantInstructions()
	{
		return new Object[][]
			{
				{List.of(Path.of("/data/m2"), Path.of("/data/gradle")), Set.of("a.example.com", "b.example.com"),
					"add \"/data/m2\", \"/data/gradle\" to sandbox.filesystem.allowWrite and \"a.example.com\", " +
						"\"b.example.com\" to sandbox.network.allowedDomains in your Claude Code settings"},
				{List.of(Path.of("/data/m2")), Set.of(),
					"add \"/data/m2\" to sandbox.filesystem.allowWrite in your Claude Code settings"},
				{List.of(), Set.of("api.example.com"),
					"add \"api.example.com\" to sandbox.network.allowedDomains in your Claude Code settings"}
			};
	}

	/**
	 * Verifies that a refused launch tells the user which Claude Code settings to add a missing write and host to.
	 *
	 * @param writes   the paths that the session's sandbox lacks
	 * @param hosts    the hosts that the session's sandbox lacks
	 * @param expected the instruction
	 */
	@Test(dataProvider = "grantInstructions")
	public void namesSettingsThatAllowMissingAccess(List<Path> writes, Set<String> hosts, String expected)
	{
		ClaudeParentPolicy source = new ClaudeParentPolicy(new ClaudePolicyReader(), Map.of(), HOME, "user");

		requireThat(source.permissionEntries(writes, hosts), "entries").isEqualTo(expected);
	}
}
