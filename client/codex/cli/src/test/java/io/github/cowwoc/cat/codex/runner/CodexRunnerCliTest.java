package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexRunnerCli}.
 */
public final class CodexRunnerCliTest
{
	/**
	 * Verifies the bridge command reads the session's tool-output token limit, and that a command without one keeps
	 * Codex's default.
	 */
	@Test
	public void readsTheToolOutputTokenLimit()
	{
		requireThat(CodexRunnerCli.toolOutputTokenLimit(new String[]{"serve", "--artifact", "/artifact",
			"--tool-output-token-limit", "5000", "--keep-run"}), "limited").isEqualTo(5000);
		requireThat(CodexRunnerCli.toolOutputTokenLimit(new String[]{"serve", "--artifact", "/artifact"}),
			"unlimited").isEqualTo(0);
	}

	/**
	 * Returns tool-output token limits that are missing or not a positive number.
	 *
	 * @return the bridge commands
	 */
	@DataProvider
	public Object[][] invalidLimits()
	{
		return new Object[][]{{new String[]{"serve", "--tool-output-token-limit"}},
			{new String[]{"serve", "--tool-output-token-limit", "--keep-run"}},
			{new String[]{"serve", "--tool-output-token-limit", "0"}},
			{new String[]{"serve", "--tool-output-token-limit", "many"}}};
	}

	/**
	 * Verifies a tool-output token limit that is missing or not a positive number is refused.
	 *
	 * @param args the bridge command
	 */
	@Test(dataProvider = "invalidLimits", expectedExceptions = IllegalArgumentException.class)
	public void refusesAnInvalidToolOutputTokenLimit(String[] args)
	{
		CodexRunnerCli.toolOutputTokenLimit(args);
	}
}
