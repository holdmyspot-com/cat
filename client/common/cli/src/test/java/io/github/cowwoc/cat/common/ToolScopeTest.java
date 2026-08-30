package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.evaluation.PromptEvaluationOperation;

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
	 * Verifies a tool operation defaults its CAT work root to the gitignored project directory.
	 */
	@Test
	public void defaultsWorkToProjectDirectory()
	{
		try (ToolScope scope = new ToolScope())
		{
			Path projectRoot = Path.of("../../..").toAbsolutePath().normalize();
			requireThat(scope.catWork(), "catWork").isEqualTo(projectRoot.resolve(".cat/work"));
			requireThat(scope.temporaryWorkRoot(), "temporaryWorkRoot").isEqualTo(projectRoot.resolve(".cat/work/temp"));
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

	/**
	 * Verifies a closed tool scope rejects JSON mapper access.
	 */
	@SuppressWarnings("try")
	@Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = ".*Scope is closed.*")
	public void rejectsJsonAccessAfterClose()
	{
		try (ToolScope scope = new ToolScope())
		{
			scope.close();

			scope.json();
		}
	}

	/**
	 * Verifies operation services cannot access a mapper after their owning scope closes.
	 */
	@SuppressWarnings("try")
	@Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = ".*Scope is closed.*")
	public void rejectsOperationJsonAfterClose()
	{
		try (ToolScope scope = new ToolScope())
		{
			PromptEvaluationOperation operation = new PromptEvaluationOperation(scope);
			scope.close();

			operation.json();
		}
	}
}
