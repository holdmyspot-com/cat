package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.claude.runner.ClaudeRequestServerCli;
import com.holdmyspot.cat.claude.runner.ClaudeSessionSandbox;
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
	 * starts it as a background command is not left with a command that never ends and keeps its turn open, and that
	 * any other session gets only the platform's refusal, if there is one.
	 */
	@Test
	public void refusesInNestedSession()
	{
		Platform platform = Platform.current();
		Map<String, String> inNestedSession = Map.of(HarnessEnvironment.NESTED_SESSION_VARIABLE,
			HarnessEnvironment.NESTED_SESSION_VALUE);

		requireThat(ClaudeRequestServerCli.refusal(platform, inNestedSession).orElseThrow(), "refusal").
			contains("no request server serves such a session");
		requireThat(ClaudeRequestServerCli.refusal(platform, Map.of("PATH", "/bin")), "outsideNestedSession").
			isEqualTo(ClaudeSessionSandbox.refusal(platform));
	}
}
