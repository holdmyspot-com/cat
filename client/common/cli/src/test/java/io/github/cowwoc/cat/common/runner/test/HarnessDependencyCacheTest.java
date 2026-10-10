package io.github.cowwoc.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessDependencyCache;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests {@link HarnessDependencyCache}.
 */
public final class HarnessDependencyCacheTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessDependencyCacheTest()
	{
	}

	/**
	 * Verifies isolated homes reuse the parent cache rather than creating a per-run npm cache.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void configuresParentNpmCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-parent-home-"));
			Map<String, String> isolated = new HashMap<>();

			HarnessDependencyCache.configureNpm(Map.of("HOME", home.toString()), isolated);

			requireThat(isolated.get("NPM_CONFIG_CACHE"), "npmCache").isEqualTo(home.resolve(".npm").toString());
			requireThat(Files.isDirectory(home.resolve(".npm")), "cacheDirectory").isTrue();
			requireThat(HarnessDependencyCache.verifyNpm(Map.of("HOME", home.toString())), "verifiedCache").
				isEqualTo(home.resolve(".npm"));
		}
	}
}
