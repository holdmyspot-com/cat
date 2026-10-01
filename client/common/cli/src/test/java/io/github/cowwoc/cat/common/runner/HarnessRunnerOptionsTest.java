package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessRunnerOptions}.
 */
public final class HarnessRunnerOptionsTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessRunnerOptionsTest()
	{
	}

	/**
	 * Verifies every runner can expose one complete help text without parsing a bridge invocation.
	 */
	@Test
	public void exposesUsage()
	{
		requireThat(HarnessRunnerOptions.usage("harness-runner"), "usage").startsWith("Usage: harness-runner ").
			contains("--artifact <path>").
			contains("--max-concurrency <1..16>");
	}

	/**
	 * Verifies bridge options select explicit isolated paths, retained evidence, and CAT installation.
	 */
	@Test
	public void parsesBridgeOptions()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", "/artifact",
			"--project", "/project", "--run-root", "/caller-temp/runs", "--executable", "/bin/harness", "--model",
			"test-model", "--effort", "high", "--output", "/output", "--receipt", "/receipt", "--keep-run",
			"--install-cat"},
			"harness", Path.of("/caller-temp"));

		requireThat(result.artifact(), "artifact").isEqualTo(Path.of("/artifact"));
		requireThat(result.project(), "project").isEqualTo(Path.of("/project"));
		requireThat(result.runRoot(), "runRoot").isEqualTo(Path.of("/caller-temp/runs"));
		requireThat(result.executable(), "executable").isEqualTo("/bin/harness");
		requireThat(result.model(), "model").isEqualTo("test-model");
		requireThat(result.effort(), "effort").isEqualTo("high");
		requireThat(result.output(), "output").isEqualTo(Path.of("/output"));
		requireThat(result.receipt(), "receipt").isEqualTo(Path.of("/receipt"));
		requireThat(result.keepRun(), "keepRun").isTrue();
		requireThat(result.installCat(), "installCat").isTrue();
		requireThat(result.includeUserRules(), "includeUserRules").isTrue();
	}

	/**
	 * Verifies controlled evaluations can install CAT without inheriting personal guidance.
	 */
	@Test
	public void excludesUserRulesWhenRequested()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", "/artifact",
			"--install-cat", "--exclude-user-rules"}, "harness", Path.of("/caller-temp"));

		requireThat(result.installCat(), "installCat").isTrue();
		requireThat(result.includeUserRules(), "includeUserRules").isFalse();
	}

	/**
	 * Verifies CAT installation is opt-in for bridge requests.
	 */
	@Test
	public void defaultsToNoCatInstallation()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", "/artifact"},
			"harness", Path.of("/caller-temp"));

		requireThat(result.installCat(), "installCat").isFalse();
	}

	/**
	 * Verifies the artifact is required.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMissingArtifact()
	{
		HarnessRunnerOptions.parse(new String[0], "harness", Path.of("/caller-temp"));
	}

	/**
	 * Verifies a receipt has the bridge transcript and retained run that make it independently usable.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsIncompleteReceiptEvidence()
	{
		HarnessRunnerOptions.parse(new String[] {"--artifact", "/artifact", "--receipt", "/receipt"}, "harness",
			Path.of("/caller-temp"));
	}

	/**
	 * Verifies exclusion cannot silently suggest that a non-CAT session isolates injected guidance.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsUserRuleExclusionWithoutCat()
	{
		HarnessRunnerOptions.parse(new String[] {"--artifact", "/artifact", "--exclude-user-rules"}, "harness",
			Path.of("/caller-temp"));
	}

	/**
	 * Verifies the shared capacity ceiling is the default.
	 */
	@Test
	public void defaultsToCapacityCeiling()
	{
		// Requirement: R-E8
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", "/artifact"},
			"harness", Path.of("/caller-temp"));

		requireThat(result.maximumConcurrency(), "maximumConcurrency").isEqualTo(16);
	}

	/**
	 * Verifies both ends of the accepted {@code --max-concurrency} range.
	 */
	@Test
	public void acceptsMaximumConcurrencyRange()
	{
		// Requirement: R-E8
		HarnessRunnerOptions lowest = HarnessRunnerOptions.parse(new String[] {"--artifact", "/artifact",
			"--max-concurrency", "1"}, "harness", Path.of("/caller-temp"));
		HarnessRunnerOptions highest = HarnessRunnerOptions.parse(new String[] {"--artifact", "/artifact",
			"--max-concurrency", "16"}, "harness", Path.of("/caller-temp"));

		requireThat(lowest.maximumConcurrency(), "lowest").isEqualTo(1);
		requireThat(highest.maximumConcurrency(), "highest").isEqualTo(16);
	}

	/**
	 * Verifies zero is outside the accepted {@code --max-concurrency} range.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMaximumConcurrencyBelowRange()
	{
		// Requirement: R-E8
		HarnessRunnerOptions.parse(new String[] {"--artifact", "/artifact", "--max-concurrency", "0"}, "harness",
			Path.of("/caller-temp"));
	}

	/**
	 * Verifies 17 is outside the accepted {@code --max-concurrency} range.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMaximumConcurrencyAboveRange()
	{
		// Requirement: R-E8
		HarnessRunnerOptions.parse(new String[] {"--artifact", "/artifact", "--max-concurrency", "17"}, "harness",
			Path.of("/caller-temp"));
	}
}
