package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;

import java.io.IOException;

import org.testng.annotations.Test;

/**
 * Tests terminal-result handling by {@link ClaudeHarnessProcess}.
 */
public final class ClaudeHarnessProcessTest
{
	/**
	 * Verifies a Claude provider error remains a provider failure instead of becoming an invalid CAT route response.
	 *
	 * @throws IOException if the terminal-result fixture cannot be parsed
	 */
	@Test
	public void reportsProviderFailureFromErrorResult() throws IOException
	{
		try (HarnessRunnerScope scope = new HarnessRunnerScope())
		{
			String failure = ClaudeHarnessProcess.providerFailureMessage(scope.json().readTree("""
				{"type":"result","is_error":true,"api_error_status":503,
				 "result":"Service temporarily unavailable"}
				""")).orElseThrow();

			requireThat(failure, "failure").isEqualTo(
				"Claude provider request failed with HTTP 503: Service temporarily unavailable");
		}
	}
}
