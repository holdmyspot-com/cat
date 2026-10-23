package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.distribution.bundle.SourceOnlyContent;
import java.io.IOException;
import org.testng.annotations.Test;

/**
 * Tests {@link SourceOnlyContent}.
 */
public final class SourceOnlyContentTest
{
	/** Creates a test instance. */
	public SourceOnlyContentTest()
	{
	}

	/**
	 * Verifies the design goals section ends at the next heading of level one or two, keeping its subsections' text
	 * out, and that a file whose last section is the design goals ends with one line break.
	 */
	@Test
	public void removesDesignGoalsUpToNextSection() throws IOException
	{
		// Requirement: R-I10
		requireThat(SourceOnlyContent.remove("# Title\n\n## Design Goals\n\n- Goal.\n\n### Detail\n\nMore.\n\n" +
			"# Shared First Use\n\nBody.\n", "file.md"), "nextTitle").
			isEqualTo("# Title\n\n# Shared First Use\n\nBody.\n");
		requireThat(SourceOnlyContent.remove("# Title\n\nIntro.\n\n## Design Goals\n\n- Goal.\n\n", "file.md"),
			"lastSection").isEqualTo("# Title\n\nIntro.\n");
	}

	/**
	 * Verifies a comment that fills its lines leaves no blank line behind, an inline comment leaves the rest of its
	 * line, and directives stay.
	 */
	@Test
	public void removesCommentsButKeepsDirectives() throws IOException
	{
		// Requirement: R-I10
		requireThat(SourceOnlyContent.remove("- One.\n  <!-- why\n  it holds -->\n- Two.\n", "file.md"), "indented").
			isEqualTo("- One.\n- Two.\n");
		requireThat(SourceOnlyContent.remove("Keep <!-- a --> this <!-- b --> text.\n", "file.md"), "inline").
			isEqualTo("Keep  this  text.\n");
		requireThat(SourceOnlyContent.remove("<!-- cat:include body.md -->\n", "file.md"), "directive").
			isEqualTo("<!-- cat:include body.md -->\n");
	}

	/**
	 * Verifies fenced code blocks keep their content, including a fence of a different marker inside them.
	 */
	@Test
	public void keepsFencedExamples() throws IOException
	{
		// Requirement: R-I10
		String content = "````markdown\n```\n<!-- inner -->\n```\n## Design Goals\n````\n<!-- outer -->\nAfter.\n";
		requireThat(SourceOnlyContent.remove(content, "file.md"), "fenced").
			isEqualTo("````markdown\n```\n<!-- inner -->\n```\n## Design Goals\n````\nAfter.\n");
	}

	/**
	 * Verifies a file without source-only content is returned unchanged, without normalizing its trailing lines.
	 */
	@Test
	public void leavesCleanContentUnchanged() throws IOException
	{
		requireThat(SourceOnlyContent.remove("Text.\n\n\n", "file.md"), "clean").isEqualTo("Text.\n\n\n");
	}

	/**
	 * Verifies a comment that never ends fails instead of removing the rest of the file.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "A comment in file.md never ends")
	public void rejectsUnterminatedComment() throws IOException
	{
		SourceOnlyContent.remove("Text <!-- open\nmore\n", "file.md");
	}
}
