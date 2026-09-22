package io.github.cowwoc.cat.common.runner;

import java.nio.file.Path;
import java.time.Duration;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests {@link HarnessRunnerMaintenanceOptions}.
 */
public final class HarnessRunnerMaintenanceOptionsTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessRunnerMaintenanceOptionsTest()
	{
	}

	/**
	 * Verifies explicit deletion keeps an opaque run ID separate from its root path.
	 */
	@Test
	public void parsesExplicitDeletion()
	{
		HarnessRunnerMaintenanceOptions result = HarnessRunnerMaintenanceOptions.parse(new String[] {"--delete-run",
			"--run-id", "abc-123", "--run-root", "/caller-temp/runs"}, Path.of("/caller-temp"));

		requireThat(result.operation(), "operation").isEqualTo(HarnessRunnerMaintenanceOptions.Operation.DELETE_RUN);
		requireThat(result.runId(), "runId").isEqualTo("abc-123");
		requireThat(result.runRoot(), "runRoot").isEqualTo(Path.of("/caller-temp/runs"));
	}

	/**
	 * Verifies stale cleanup accepts an explicit ISO-8601 retention period.
	 */
	@Test
	public void parsesStaleCleanup()
	{
		HarnessRunnerMaintenanceOptions result = HarnessRunnerMaintenanceOptions.parse(new String[] {
			"--delete-stale-runs", "--older-than", "PT48H", "--run-root", "/caller-temp/runs"},
			Path.of("/caller-temp"));

		requireThat(result.operation(), "operation").isEqualTo(
			HarnessRunnerMaintenanceOptions.Operation.DELETE_STALE_RUNS);
		requireThat(result.retention(), "retention").isEqualTo(Duration.ofHours(48));
	}

	/**
	 * Verifies terminal runtime pruning accepts only its explicit owned run root.
	 */
	@Test
	public void parsesTerminalRuntimePruning()
	{
		HarnessRunnerMaintenanceOptions result = HarnessRunnerMaintenanceOptions.parse(new String[] {
			"--prune-terminal-runtimes", "--run-root", "/caller-temp/runs"}, Path.of("/caller-temp"));

		requireThat(result.operation(), "operation").isEqualTo(
			HarnessRunnerMaintenanceOptions.Operation.PRUNE_TERMINAL_RUNTIMES);
		requireThat(result.runRoot(), "runRoot").isEqualTo(Path.of("/caller-temp/runs"));
	}

	/**
	 * Verifies dependency-cache validation is a lifecycle-only operation with no run target.
	 */
	@Test
	public void parsesDependencyCacheValidation()
	{
		HarnessRunnerMaintenanceOptions result = HarnessRunnerMaintenanceOptions.parse(new String[] {
			"--verify-dependency-cache"}, Path.of("/caller-temp"));

		requireThat(result.operation(), "operation").isEqualTo(
			HarnessRunnerMaintenanceOptions.Operation.VERIFY_DEPENDENCY_CACHE);
	}

	/**
	 * Verifies a capture supervisor can hand an owned run's observed exit status and diagnostic back to its runner.
	 */
	@Test
	public void parsesObservedRunFailure()
	{
		HarnessRunnerMaintenanceOptions result = HarnessRunnerMaintenanceOptions.parse(new String[] {"--fail-run",
			"--run-id", "abc-123", "--exit-code", "143", "--diagnostic", "capture timed out after 600 seconds",
			"--run-root", "/caller-temp/runs"}, Path.of("/caller-temp"));

		requireThat(result.operation(), "operation").isEqualTo(HarnessRunnerMaintenanceOptions.Operation.FAIL_RUN);
		requireThat(result.runId(), "runId").isEqualTo("abc-123");
		requireThat(result.exitCode(), "exitCode").isEqualTo(143);
		requireThat(result.diagnostic(), "diagnostic").isEqualTo("capture timed out after 600 seconds");
	}

	/**
	 * Verifies deletion rejects a path-like run identifier.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsPathLikeRunIdentifier()
	{
		new HarnessRun("harness", "../outside", Path.of("/runs/harness/outside"));
	}
}
