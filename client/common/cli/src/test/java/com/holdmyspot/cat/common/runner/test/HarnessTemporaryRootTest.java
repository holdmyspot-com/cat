package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
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
	 * Verifies every runner operation defaults beneath the session's temporary directory.
	 */
	@Test
	public void defaultsBelowCatTemporaryRoot()
	{
		Path result = HarnessTemporaryRoot.of(Path.of("/caller-temp").toAbsolutePath()).defaultRunRoot();

		requireThat(result, "result").isEqualTo(Path.of("/caller-temp/runner-runs").toAbsolutePath());
	}
}
