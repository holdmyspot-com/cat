package io.github.cowwoc.cat.common.runner.test;

import io.github.cowwoc.cat.common.runner.HarnessTemporaryRoot;
import java.nio.file.Path;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


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
	 * Verifies every runner operation defaults beneath the scope-provided CAT temporary root.
	 */
	@Test
	public void defaultsBelowCatTemporaryRoot()
	{
		Path result = HarnessTemporaryRoot.defaultRunRoot(Path.of("/caller-temp").toAbsolutePath());

		requireThat(result, "result").isEqualTo(Path.of("/caller-temp/runner-runs").toAbsolutePath());
	}
}
