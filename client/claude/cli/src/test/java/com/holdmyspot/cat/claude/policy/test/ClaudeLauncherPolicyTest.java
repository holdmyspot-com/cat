package com.holdmyspot.cat.claude.policy.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.policy.ClaudePolicyReader;
import com.holdmyspot.cat.claude.runner.ClaudeSessionLauncherCli;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.RealPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the sandbox policy that the Claude Code session launcher serves its session with: the policy that the
 * session's settings give when the launcher starts, and later the policy of that same Claude Code process only.
 */
public final class ClaudeLauncherPolicyTest
{
	/**
	 * Creates the test.
	 */
	public ClaudeLauncherPolicyTest()
	{
	}

	/**
	 * Skips the tests on native Windows, where Claude Code has no sandbox and CAT starts no session launcher, so that
	 * no Claude Code session's sandbox policy is read there.
	 */
	@BeforeClass
	public void requireSandboxPlatform()
	{
		if (Platform.current().isWindows())
		{
			throw new SkipException("Claude Code has no sandbox on native Windows, so CAT reads no session's " +
				"policy there");
		}
	}

	/**
	 * Verifies that the launcher reads its session's policy from the session's settings, and refuses a later
	 * reading once another process has taken the Claude Code process's identifier.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the first reading is refused
	 */
	@Test
	public void laterReadingRefusesReplacedProcess() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.userSettings("""
				{"sandbox": {"enabled": true, "network": {"allowedDomains": ["api.example.com"]}}}""");
			ParentPolicySource source = ClaudeSessionLauncherCli.policySource(fixture.reader(), fixture::session);

			ParentPolicy first = source.read();
			requireThat(source.harnessName(), "harnessName").isEqualTo(ClaudePolicyReader.HARNESS_NAME);
			requireThat(first.sandboxed(), "sandboxed").isTrue();
			requireThat(first.hosts().allows("api.example.com"), "host").isTrue();
			// A later reading names each path by the file that it reaches, such as /private/tmp/claude for /tmp/claude
			// on macOS
			ParentPolicy resolvedFirst = new ParentPolicy(first.sandboxed(), RealPaths.resolveAll(first.writeRoots()),
				RealPaths.resolveAll(first.writeDenials()), RealPaths.resolveAll(first.readDenials()),
				RealPaths.resolveAll(first.readReallowances()), first.hosts(), first.allowLocalBinding(),
				RealPaths.resolveAll(first.protectedSources()));
			requireThat(source.read(), "second").isEqualTo(resolvedFirst);

			fixture.reportedStart = Instant.parse("2026-11-05T13:00:00Z");
			try
			{
				ParentPolicy policy = source.read();
				throw new AssertionError("The later reading was not refused: " + policy);
			}
			catch (PolicyRefusal e)
			{
				requireThat(e.getMessage(), "refusal").contains("another process");
			}
		}
	}

	/**
	 * Supplies the writes and hosts that a child's sandbox lacks and the instruction that names the Claude Code
	 * settings which allow them.
	 *
	 * @return the missing writes, the missing hosts and the instruction
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
	 * Verifies that the launcher tells the user which Claude Code settings to add a missing write and host to.
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
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			ParentPolicySource source = ClaudeSessionLauncherCli.policySource(fixture.reader(), fixture::session);

			requireThat(source.permissionEntries(writes, hosts), "entries").isEqualTo(expected);
		}
	}
}
