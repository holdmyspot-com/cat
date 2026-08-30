package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link SourceDocumentationInventory}.
 */
public final class SourceDocumentationInventoryTest
{
	/**
	 * Creates the test fixture.
	 */
	public SourceDocumentationInventoryTest()
	{
	}

	/**
	 * Verifies source comments are listed while comment-looking literals and shell shebangs are excluded.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void inventoriesSupportedSourceComments() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path sourceRoot = cleanup.add(Files.createTempDirectory("source-documentation-inventory"));
			Path output = sourceRoot.resolve("inventory.md");
			Files.writeString(sourceRoot.resolve("Example.java"), "/** Public contract. */\n" +
				"class Example\n{\n" +
				"  String lineLiteral = \"// not a comment\";\n" +
				"  String blockLiteral = \"/* not a comment */\";\n" +
				"  String textBlock = \"\"\"\n" +
				"    // not a comment\n" +
				"    \"\"\";\n" +
				"  /* Implementation constraint. */\n" +
				"  // Reader note.\n}\n");
			Files.writeString(sourceRoot.resolve("script.sh"), """
				#!/bin/sh
				value='# not a comment'
				# Shell note.
				""");
			Path generatedOutput = Files.createDirectory(sourceRoot.resolve("target"));
			Files.writeString(generatedOutput.resolve("Generated.java"), "/** Generated output. */\n");

			SourceDocumentationInventory.main(new String[]{output.toString(), sourceRoot.toString()});

			String contents = Files.readString(output);
			requireThat(contents, "contents").contains("Example.java:1: Javadoc").
				contains("Example.java:9: block comment").contains("Example.java:10: line comment").
				contains("script.sh:3: line comment").doesNotContain("not a comment").
				doesNotContain("script.sh:1").doesNotContain("Generated.java");

			Path generatedOutputInventory = sourceRoot.resolve("generated-inventory.md");
			SourceDocumentationInventory.main(new String[]{generatedOutputInventory.toString(), generatedOutput.toString()});
			requireThat(Files.readString(generatedOutputInventory), "generatedOutputInventory").
				doesNotContain("Generated.java");
		}
	}
}
