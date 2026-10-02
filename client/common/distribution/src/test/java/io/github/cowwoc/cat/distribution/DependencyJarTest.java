package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

import java.nio.file.Path;

/**
 * Tests that a third-party dependency's Maven coordinates name the version of the JAR that ships.
 */
public final class DependencyJarTest
{
	/**
	 * Creates the test.
	 */
	public DependencyJarTest()
	{
	}

	/**
	 * Verifies that the coordinates take the version from the JAR's file name.
	 */
	@Test
	public void coordinatesNameTheShippedVersion()
	{
		DependencyJar jar = new DependencyJar("tools.jackson.core", "jackson-core",
			Path.of("modules/jackson-core-3.2.3.jar"));
		requireThat(jar.coordinates(), "coordinates").isEqualTo("tools.jackson.core:jackson-core:3.2.3");
	}

	/**
	 * Verifies that a JAR whose file name does not start with the artifact identifier is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsJarOfAnotherArtifact()
	{
		new DependencyJar("org.slf4j", "slf4j-api", Path.of("slf4j-nop-2.0.20.jar"));
	}
}
