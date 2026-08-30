package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link PromptFileCheck}.
 */
public final class PromptFileCheckTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptFileCheckTest()
	{
	}

	/**
	 * Verifies substantive prompt files with Design Goals are accepted.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsPromptFilesWithDesignGoals() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("prompt-file-check"));
			Files.writeString(directory.resolve("prompt.md"), "# Prompt\n\n## Design Goals\n\n- Test outcome.\n");

			PromptFileCheck.main(new String[]{directory.toString()});
		}
	}

	/**
	 * Verifies one command checks every explicitly selected prompt directory.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsMultiplePromptDirectories() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path first = cleanup.add(Files.createTempDirectory("prompt-file-check"));
			Path second = cleanup.add(Files.createTempDirectory("prompt-file-check"));
			Files.writeString(first.resolve("first.md"), "# First\n\n## Design Goals\n\n- Test outcome.\n");
			Files.writeString(second.resolve("second.md"), "# Second\n\n## Design Goals\n\n- Test outcome.\n");

			PromptFileCheck.main(new String[]{first.toString(), second.toString()});
		}
	}

	/**
	 * Verifies one isolated substantive prompt file can be included when its source directory also contains wrappers
	 * whose includes are assembled only in a later packaging step.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsIndividualPromptFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("prompt-file-check"));
			Path prompt = directory.resolve("prompt.md");
			Files.writeString(prompt, "# Prompt\n\n## Design Goals\n\n- Test outcome.\n");

			PromptFileCheck.main(new String[]{prompt.toString()});
		}
	}

	/**
	 * Verifies an explicitly selected source file must be Markdown prompt guidance.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*must be Markdown.*")
	public void rejectsIndividualNonMarkdownFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("prompt-file-check"));
			Path source = directory.resolve("source.txt");
			Files.writeString(source, "Not a prompt");

			PromptFileCheck.main(new String[]{source.toString()});
		}
	}

	/**
	 * Verifies the standard metadata-only skill wrapper is exempt.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsMetadataOnlySkillWrapper() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("prompt-file-check"));
			String firstUseRoute = """
				If you have not already read this skill's `first-use.md` in the current
				conversation context, read it now and follow it exactly. Otherwise, reuse its guidance.
				Read `first-use.md` again after the context resets or is compacted.
				""";
			Files.writeString(directory.resolve("SKILL.md"), "---\ndescription: Example\n---\n\n" + firstUseRoute);
			Files.writeString(directory.resolve("first-use.md"), "# Prompt\n\n## Design Goals\n\n- Test outcome.\n");

			PromptFileCheck.main(new String[]{directory.toString()});
		}
	}

	/**
	 * Verifies a source-only include may supply the substantive prompt's Design Goals.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsSourceOnlyIncludedBody() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("prompt-file-check"));
			Path body = directory.resolve("body.md");
			Files.writeString(body, "# Prompt\n\n## Design Goals\n\n- Test outcome.\n");
			Files.writeString(directory.resolve("prompt.md"), "<!-- cat:include body.md -->\n");

			PromptFileCheck.main(new String[]{directory.toString()});
		}
	}

	/**
	 * Verifies generated routing indexes are not treated as reader-facing prompt files.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsGeneratedRoutingIndexes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("prompt-file-check"));
			Files.writeString(directory.resolve("prompt.md"), "# Prompt\n\n## Design Goals\n\n- Test outcome.\n");
			Files.writeString(directory.resolve(".cat-path-scoped-rules.md"), "# CAT Path-Scoped Rules\n");
			Files.writeString(directory.resolve(".cat-lazy-rule-dependencies.md"), "# CAT Lazy Rule Dependencies\n");

			PromptFileCheck.main(new String[]{directory.toString()});
		}
	}

	/**
	 * Verifies a README that documents a prompt directory is not treated as executable guidance.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsDocumentationReadme() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("prompt-file-check"));
			Files.writeString(directory.resolve("prompt.md"), "# Prompt\n\n## Design Goals\n\n- Test outcome.\n");
			Files.writeString(directory.resolve("README.md"), "# Prompt directory\n");

			PromptFileCheck.main(new String[]{directory.toString()});
		}
	}

	/**
	 * Verifies the inventory distinguishes executable guidance from explicit non-prompt files.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void writesPromptInventory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("prompt-file-check"));
			Path inventory = directory.resolve("inventory.md");
			Files.writeString(directory.resolve("prompt.md"), "# Prompt\n\n## Design Goals\n\n- Test outcome.\n");
			Files.writeString(directory.resolve("README.md"), "# Prompt directory\n");

			PromptFileCheck.main(new String[]{"--inventory", inventory.toString(), directory.toString()});

			String contents = Files.readString(inventory);
			requireThat(contents, "contents").contains("## Included").contains(directory.resolve("prompt.md").toString()).
				contains("## Excluded").contains(directory.resolve("README.md").toString()).
				contains("directory documentation");
		}
	}

	/**
	 * Verifies substantive prompt files without Design Goals are rejected.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*lacks Design Goals.*")
	public void rejectsPromptFileWithoutDesignGoals() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("prompt-file-check"));
			Files.writeString(directory.resolve("prompt.md"), "# Prompt\n\nGuidance\n");

			PromptFileCheck.main(new String[]{directory.toString()});
		}
	}
}
