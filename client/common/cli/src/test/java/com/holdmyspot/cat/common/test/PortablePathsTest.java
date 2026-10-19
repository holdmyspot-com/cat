package com.holdmyspot.cat.common.test;

import com.holdmyspot.cat.common.PortablePaths;
import java.nio.file.Path;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that {@link PortablePaths} writes relative paths with {@code /} on every platform and
 * absolute paths in the platform's form.
 */
public final class PortablePathsTest
{
	/**
	 * Creates the test fixture.
	 */
	public PortablePathsTest()
	{
	}

	/**
	 * Verifies that a path built from several names separates them with {@code /}.
	 */
	@Test
	public void separatesNamesWithSlashes()
	{
		Path path = Path.of("rules", "common", "safety.md");

		requireThat(PortablePaths.text(path), "text").isEqualTo("rules/common/safety.md");
	}

	/**
	 * Verifies that the empty path is the empty string.
	 */
	@Test
	public void emptyPathIsEmpty()
	{
		requireThat(PortablePaths.text(Path.of("")), "text").isEmpty();
	}

	/**
	 * Verifies that an absolute path keeps the platform's form, because it names a file on one machine only.
	 */
	@Test
	public void keepsAbsolutePath()
	{
		Path path = Path.of("").toAbsolutePath().resolve("rules");

		requireThat(PortablePaths.text(path), "text").isEqualTo(path.toString());
	}
}
