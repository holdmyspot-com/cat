package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.testng.annotations.Test;

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
