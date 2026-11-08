package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.runner.HarnessMarkers;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that {@link HarnessMarkers} reads which harnesses run a command, and that the environment that CAT's own
 * starters give a runner names no harness.
 */
public final class HarnessMarkersTest
{
	/**
	 * The markers of two harnesses.
	 */
	private static final HarnessMarkers MARKERS = new HarnessMarkers(List.of(
		HarnessMarkers.Marker.equalTo("harness-a", "HARNESS_A_SESSION", "1"),
		HarnessMarkers.Marker.notBlank("harness-b", "HARNESS_B_THREAD")));

	/**
	 * Creates the test.
	 */
	public HarnessMarkersTest()
	{
	}

	/**
	 * Verifies that an environment without the markers names no harness, so that a runner that CAT's own starter
	 * starts with it does not take its launch for one that a harness session made, and that every other variable is
	 * kept.
	 */
	@Test
	public void environmentWithoutMarkersNamesNoHarness()
	{
		Map<String, String> environment = Map.of("HARNESS_A_SESSION", "1", "HARNESS_B_THREAD", "thread", "PATH",
			"programs");
		requireThat(MARKERS.running(environment), "running").isEqualTo(Set.of("harness-a", "harness-b"));

		Map<String, String> without = MARKERS.without(environment);

		requireThat(without, "without").isEqualTo(Map.of("PATH", "programs"));
		requireThat(MARKERS.running(without), "runningWithout").isEmpty();
	}
}
