package com.holdmyspot.cat.common.installation.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installation.CapacityLocks;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link CapacityLocks}.
 */
public final class CapacityLocksTest
{
	/**
	 * Each harness's capacity locks are a separate directory inside the user's {@code .cat/locks} directory.
	 */
	@Test
	public void harnessLocksAreInsideTheLockRoot()
	{
		Path home = Path.of("home-a");

		requireThat(CapacityLocks.root(home), "root").isEqualTo(home.resolve(".cat/locks"));
		requireThat(CapacityLocks.harnessCapacity(home, "harness-a"), "harnessCapacity").
			isEqualTo(home.resolve(".cat/locks/harness-capacity/harness-a"));
	}
}
