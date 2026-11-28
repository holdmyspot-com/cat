package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.execution.StartupHistory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.testng.annotations.Test;

/**
 * Verifies what a launcher's earlier runs say about how long it takes to become ready.
 */
public final class StartupHistoryTest
{
	/**
	 * Creates the test.
	 */
	public StartupHistoryTest()
	{
	}

	/**
	 * A launcher that no run has started has no normal time.
	 */
	@Test
	public void emptyHistoryHasNoSummary()
	{
		// Requirement: R-L23
		requireThat(new StartupHistory().summary(), "summary").isEqualTo(Optional.empty());
	}

	/**
	 * The summary gives the number of runs, the middle time and the longest time.
	 */
	@Test
	public void summarizesMedianAndSlowest()
	{
		// Requirement: R-L23
		StartupHistory history = new StartupHistory();
		for (long millis : new long[]{3_000, 1_000, 2_000})
			history.recordReady(Duration.ofMillis(millis));

		StartupHistory.Summary summary = history.summary().orElseThrow();

		requireThat(summary.runs(), "runs").isEqualTo(3);
		requireThat(summary.median(), "median").isEqualTo(Duration.ofMillis(2_000));
		requireThat(summary.slowest(), "slowest").isEqualTo(Duration.ofMillis(3_000));
	}

	/**
	 * Only the last twenty runs count, so that a launcher that was slow once is not slow forever.
	 */
	@Test
	public void keepsTheLastTwentyRuns()
	{
		// Requirement: R-L23
		StartupHistory history = new StartupHistory();
		history.recordReady(Duration.ofSeconds(90));
		for (int i = 0; i < 20; ++i)
			history.recordReady(Duration.ofSeconds(1));

		StartupHistory.Summary summary = history.summary().orElseThrow();

		requireThat(summary.runs(), "runs").isEqualTo(20);
		requireThat(summary.slowest(), "slowest").isEqualTo(Duration.ofSeconds(1));
	}

	/**
	 * Runs with the same sandbox program and Java executable share one history, and another pair has its own.
	 */
	@Test
	public void sharesOneHistoryPerSandboxAndJava()
	{
		// Requirement: R-L23
		String unique = "/shared-history-test-" + UUID.randomUUID();
		Path sandbox = Path.of(unique, "bwrap");
		Path java = Path.of(unique, "bin", "java");
		StartupHistory.shared(sandbox, java).recordReady(Duration.ofSeconds(2));

		Optional<StartupHistory.Summary> same = StartupHistory.shared(sandbox, java).summary();
		Optional<StartupHistory.Summary> other = StartupHistory.shared(sandbox, Path.of(unique, "other")).
			summary();

		requireThat(same.orElseThrow().runs(), "sameRuns").isEqualTo(1);
		requireThat(other, "other").isEqualTo(Optional.empty());
	}
}
