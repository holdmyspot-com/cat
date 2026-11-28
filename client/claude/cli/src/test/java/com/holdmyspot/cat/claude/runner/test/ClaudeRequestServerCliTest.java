package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.claude.runner.ClaudeRequestServerCli;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import java.util.Map;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests {@link ClaudeRequestServerCli}.
 */
public final class ClaudeRequestServerCliTest
{
	/**
	 * Creates the test fixture.
	 */
	public ClaudeRequestServerCliTest()
	{
	}

	/**
	 * Verifies that the server refuses to run in a nested session, on every platform, so that a session that
	 * starts it as a background command is not left with a command that never ends and keeps its turn open.
	 */
	@Test
	public void refusesInNestedSession()
	{
		Map<String, String> inNestedSession = Map.of(HarnessEnvironment.NESTED_SESSION_VARIABLE,
			HarnessEnvironment.NESTED_SESSION_VALUE);

		for (Platform platform : Platform.values())
		{
			requireThat(ClaudeRequestServerCli.refusal(platform, inNestedSession).orElseThrow(), "refusal").
				withContext(platform, "platform").contains("no request server serves such a session");
		}
	}

	/**
	 * Verifies that outside a nested session the server runs on every platform, native Windows included, where a
	 * Claude Code session has no sandbox and its children get none.
	 */
	@Test
	public void runsOnEveryPlatform()
	{
		// Requirement: R-I44
		for (Platform platform : Platform.values())
		{
			requireThat(ClaudeRequestServerCli.refusal(platform, Map.of("PATH", "/bin")), "refusal").
				withContext(platform, "platform").isEmpty();
		}
	}
}
