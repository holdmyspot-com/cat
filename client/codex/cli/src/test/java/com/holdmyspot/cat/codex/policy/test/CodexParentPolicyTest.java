package com.holdmyspot.cat.codex.policy.test;

import com.holdmyspot.cat.codex.policy.CodexParentPolicy;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a runner of CAT's Codex plugin finds the sandbox policy of the Codex thread whose command runs it from the
 * command's environment and the thread's rollout, and names the Codex settings that would allow what a child lacks.
 */
public final class CodexParentPolicyTest
{
	/**
	 * Creates the test.
	 */
	public CodexParentPolicyTest()
	{
	}

	/**
	 * Verifies that a command that no harness runs has the unsandboxed policy, which gives a child exactly what it
	 * needs, and that a command that Claude Code runs is refused with the plugin whose runner reads a Claude Code
	 * session's policy.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void detectsCommandHarness() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));

			requireThat(new CodexParentPolicy(session.reader(), Map.of("PATH", "/bin"), session.home(),
				session.project()).read(), "noHarness").isEqualTo(ParentPolicy.UNSANDBOXED);
			try
			{
				ParentPolicy policy = new CodexParentPolicy(session.reader(), Map.of("CLAUDECODE", "1"),
					session.home(), session.project()).read();
				throw new AssertionError("The command was not refused: " + policy);
			}
			catch (SessionRefusedException e)
			{
				requireThat(e.getMessage(), "message").contains("Claude Code runs this command").
					contains("Run the runner of CAT's Claude Code plugin instead");
			}
		}
	}

	/**
	 * Verifies that the thread's working directory is the one that its rollout records, not the folder that the
	 * command runs in.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void workingDirectoryComesFromRollout() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			session.write("home/.codex/config.toml", """
				sandbox_mode = "workspace-write"
				[sandbox_workspace_write]
				network_access = true
				""");
			session.writeRollout(CodexParentSession.SESSION_ID, "");
			Path elsewhere = session.folder("other/elsewhere");

			ParentPolicy policy = new CodexParentPolicy(session.reader(),
				session.environment(CodexParentSession.SESSION_ID), session.home(), elsewhere).read();

			requireThat(policy.writeRoots(), "writeRoots").contains(session.project()).doesNotContain(elsewhere);
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
		String where = " in Codex's config.toml, where <profile> is the permissions profile that default_permissions " +
			"names";
		String legacy = "; a session that sets sandbox_mode instead lists the paths in " +
			"sandbox_workspace_write.writable_roots";
		return new Object[][]
			{
				{List.of(Path.of("/data/m2"), Path.of("/data/gradle")), Set.of("a.example.com", "b.example.com"),
					"add \"/data/m2\" = \"write\" and \"/data/gradle\" = \"write\" under " +
						"[permissions.<profile>.filesystem] and \"a.example.com\" = \"allow\" and " +
						"\"b.example.com\" = \"allow\" under [permissions.<profile>.network.domains]" + where + legacy},
				{List.of(Path.of("/data/m2")), Set.of(),
					"add \"/data/m2\" = \"write\" under [permissions.<profile>.filesystem]" + where + legacy},
				{List.of(), Set.of("api.example.com"),
					"add \"api.example.com\" = \"allow\" under [permissions.<profile>.network.domains]" + where}
			};
	}

	/**
	 * Verifies that a refused launch tells the user which Codex settings to add a missing write and host to.
	 *
	 * @param writes   the paths that the session's sandbox lacks
	 * @param hosts    the hosts that the session's sandbox lacks
	 * @param expected the instruction
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "grantInstructions")
	public void namesSettingsThatAllowMissingAccess(List<Path> writes, Set<String> hosts, String expected)
		throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			CodexParentPolicy source = new CodexParentPolicy(session.reader(), Map.of(), session.home(),
				session.project());

			requireThat(source.permissionEntries(writes, hosts), "entries").isEqualTo(expected);
		}
	}
}
