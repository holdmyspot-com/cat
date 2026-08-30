package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessTemporaryRoot}.
 */
public final class HarnessTemporaryRootTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessTemporaryRootTest()
	{
	}

	/**
	 * Verifies every runner operation defaults beneath the scope-provided CAT work temporary root.
	 */
	@Test
	public void defaultsBelowCatTemporaryRoot()
	{
		Path result = HarnessTemporaryRoot.defaultRunRoot(Path.of("/caller-temp"));

		requireThat(result, "result").isEqualTo(Path.of("/caller-temp/runner-runs"));
	}
}
