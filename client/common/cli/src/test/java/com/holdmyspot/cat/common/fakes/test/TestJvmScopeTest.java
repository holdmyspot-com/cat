package com.holdmyspot.cat.common.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link TestJvmScope}.
 */
public final class TestJvmScopeTest
{
	/**
	 * Verifies the scope creates its normalized test temporary directory.
	 */
	@Test
	public void createsTestTemporaryDirectory() throws IOException
	{
		try (TestJvmScope build = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(build.temporaryDirectory(), "test-jvm-"));
			Path target = root.resolve("created");
			try (TestJvmScope scope = new TestJvmScope(target.resolve("../created")))
			{
				requireThat(scope.temporaryDirectory(), "temporaryDirectory").isEqualTo(target);
				requireThat(target, "target").isDirectory();
			}
		}
	}

	/**
	 * Verifies closed scopes refuse temporary-directory access.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void rejectsAccessAfterClose() throws IOException
	{
		TestJvmScope scope = new TestJvmScope();
		try (scope)
		{
		}
		scope.temporaryDirectory();
	}
}
