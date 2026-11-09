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
	 * Verifies that the server refuses to run in a session of a CAT run, on every platform, so that a session that
	 * starts it as a background command is not left with a command that never ends and keeps its turn open, and that
	 * any other session gets only the platform's refusal, if there is one.
	 */
	@Test
	public void refusesInRun()
	{
		Platform platform = Platform.current();
		Map<String, String> inRun = Map.of(HarnessEnvironment.RUN_VARIABLE, HarnessEnvironment.RUN_VALUE);

		requireThat(ClaudeRequestServerCli.refusal(platform, inRun).orElseThrow(), "refusal").
			contains("no request server serves such a session");
		requireThat(ClaudeRequestServerCli.refusal(platform, Map.of("PATH", "/bin")), "outsideRun").
			isEqualTo(ClaudeSessionSandbox.refusal(platform));
	}
}
