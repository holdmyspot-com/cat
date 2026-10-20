package com.holdmyspot.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessDependencyCache;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
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
	 * Verifies isolated homes reuse the parent's npm and Maven caches rather than creating per-run caches, under the
	 * home directory that the parent's platform names, and keep the parent's Maven options, which can name the Maven
	 * repository.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void sharesParentCaches() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-parent-home-"));
			String mavenOptions = "-Dmaven.repo.local=" + home.resolve(".m2/repository");
			Map<String, String> parent = Map.of(Platform.current().homeVariable(), home.toString(), "MAVEN_OPTS",
				mavenOptions);

			HarnessDependencyCaches caches = HarnessDependencyCache.verify(parent);

			requireThat(caches.directories(), "directories").
				isEqualTo(List.of(home.resolve(".npm"), home.resolve(".m2")));
			requireThat(caches.environment(), "environment").
				isEqualTo(Map.of("NPM_CONFIG_CACHE", home.resolve(".npm").toString(), "MAVEN_OPTS", mavenOptions));
			requireThat(Files.isDirectory(home.resolve(".npm")), "npmCache").isTrue();
			requireThat(Files.isDirectory(home.resolve(".m2")), "mavenCache").isTrue();
			requireThat(HarnessDependencyCache.verifyNpm(parent), "verifiedCache").
				isEqualTo(home.resolve(".npm"));
		}
	}

	/**
	 * Verifies that the isolated environment gets no Maven options when the parent has none.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void omitsAbsentMavenOptions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-parent-home-"));
			Map<String, String> parent = Map.of(Platform.current().homeVariable(), home.toString());

			HarnessDependencyCaches caches = HarnessDependencyCache.verify(parent);

			requireThat(caches.environment(), "environment").
				isEqualTo(Map.of("NPM_CONFIG_CACHE", home.resolve(".npm").toString()));
		}
	}
}
