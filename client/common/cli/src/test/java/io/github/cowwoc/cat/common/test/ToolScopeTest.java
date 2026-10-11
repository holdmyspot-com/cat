package io.github.cowwoc.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link ToolScope}.
 */
public final class ToolScopeTest
{
	/**
	 * Creates the test fixture.
	 */
	public ToolScopeTest()
	{
	}

	/**
	 * Verifies a tool operation defaults to the project that encloses the process's working directory. The test
	 * compares against an explicit scope for that directory rather than a fixed checkout layout, because a
	 * developer's checkout may hold a nested {@code .cat} directory that makes a subdirectory the nearest project.
	 */
	@Test
	public void defaultsToWorkingDirectory()
	{
		try (ToolScope scope = new ToolScope();
			ToolScope explicit = ToolScope.forWorkingDirectory(Path.of("").toAbsolutePath()))
		{
			requireThat(scope.catWork(), "catWork").isEqualTo(explicit.catWork());
			requireThat(scope.temporaryWorkRoot(), "temporaryWorkRoot").isEqualTo(explicit.temporaryWorkRoot());
		}
	}

	/**
	 * Resolves CAT work storage at an enclosing project root rather than a nested working directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void resolvesEnclosingProjectRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-project-"));
			Files.createDirectories(root.resolve(".cat"));
			Path nestedDirectory = Files.createDirectories(root.resolve("client/common"));

			try (ToolScope scope = ToolScope.forWorkingDirectory(nestedDirectory))
			{
				requireThat(scope.catWork(), "catWork").isEqualTo(root.resolve(".cat/work"));
				requireThat(scope.temporaryWorkRoot(), "temporaryWorkRoot").isEqualTo(root.resolve(".cat/work/temp"));
			}
		}
	}
}
