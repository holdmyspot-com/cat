package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.lang.management.ManagementFactory;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests the JVM options that the build gives the JVM that runs the tests.
 */
public final class TestJvmOptionsTest
{
	/**
	 * Verifies the test JVM runs with shared performance counters disabled. Otherwise it writes
	 * {@code /tmp/hsperfdata_<user>/<pid>}, which another JVM in a separate PID namespace that shares {@code /tmp} and
	 * reuses the same PID would overwrite.
	 */
	@Test
	public void sharedPerformanceMemoryIsDisabled()
	{
		List<String> options = ManagementFactory.getRuntimeMXBean().getInputArguments();

		requireThat(options, "options").contains("-XX:+PerfDisableSharedMem");
	}
}
