package com.holdmyspot.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.proposal.PromptTemplate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.testng.annotations.Test;

/**
 * Tests the placeholder-name boundary of {@link PromptTemplate}.
 */
public final class PromptTemplateTest
{
	/**
	 * Creates the test.
	 */
	public PromptTemplateTest()
	{
	}

	/**
	 * Verifies that a named value replaces its placeholder without changing placeholder syntax inside the value.
	 */
	@Test
	public void insertsNamedValueOnce()
	{
		PromptTemplate template = new PromptTemplate("Value: {{value}}");

		requireThat(template.fill(Map.of("value", "{{literal}}")), "prompt").isEqualTo("Value: {{literal}}");
	}

	/**
	 * Verifies that a null placeholder name is rejected even when the prompt contains the literal {@code {{null}}}.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullPlaceholderName()
	{
		PromptTemplate template = new PromptTemplate("Value: {{null}}");
		Map<String, String> values = new HashMap<>();
		values.put(null, "supplied");

		template.fill(values);
	}

	/**
	 * Verifies that no role brief CAT sends holds the design goals or comments that guide its authors, while each
	 * brief keeps its guidance.
	 */
	@Test
	public void briefsOmitSourceOnlyContent()
	{
		// Requirement: R-I10
		for (String resource : List.of("/optimizer/judge/judge.md", "/optimizer/triage/triage.md",
			"/optimizer/values/values.md", "/optimizer/prompt-author/prompt-author.md",
			"/optimizer/proposal/contract-author.md", "/optimizer/proposal/test-revision.md"))
		{
			String text = PromptTemplate.load(resource).text();
			requireThat(text, resource).doesNotContain("\n## Design Goals\n").doesNotContain("<!-- ").
				contains("\n## ");
		}
	}

	/**
	 * Verifies that the instructions of the contract author and of the test revision author tell the author to decide an
	 * action that no fixed built-in tool decides with a check program, and that each brief that includes the shared
	 * prompt test rules documents the program form, its input directory, the formats of its input files, the exit
	 * statuses, the limits, the example directories and the refusals.
	 */
	@Test
	public void authorInstructionsDocumentCheckPrograms()
	{
		// Requirement: R-L22
		String rules = PromptTemplate.load("/optimizer/proposal/prompt-tests.md").text();
		for (String resource : List.of("/optimizer/proposal/contract-author.md", "/optimizer/proposal/test-revision.md"))
		{
			String author = PromptTemplate.load(resource).text();
			requireThat(author, resource).contains("`check-program`").contains("checks/").
				doesNotContain("Never write code.");
			String brief = (author + rules).replaceAll("\\s+", " ");
			requireThat(brief, "brief").
				contains("`check-program checks/<file> [<argument>...]`").
				contains("`checks/<Name>.java`").
				contains("`CAT_CHECK_INPUT`").contains("`CAT_CHECK_SCRATCH`").
				contains("`project/`").contains("`initial/`").contains("`activity.json`").contains("`replies.json`").
				contains("exits with status 0").contains("status 1").contains("program error").
				contains("`check_program_failed`").contains("`check_program_error`").
				contains("60 seconds").contains("16 KiB").
				contains("`examples/<assertion id>/<example id>/`").
				contains("native Windows").contains("bubblewrap").
				contains("`on_tool_failure: judge`").contains("`evidence_scope: response`");
		}
		String examples = section(rules, "### Assertion examples");
		requireThat(examples, "examples").contains("`check-program`").contains("twice");
	}

	/**
	 * Verifies that no instruction that CAT sends to an author cites a design, plan or requirement identifier.
	 */
	@Test
	public void authorInstructionsCiteNoPlanId()
	{
		Pattern identifier = Pattern.compile("\\b(?:D\\d+|WI-\\d+|R-[A-Z]\\d+|Q\\d+|P\\d+|H\\d+)\\b|\u00a7|user ruling");
		for (String resource : List.of("/optimizer/proposal/contract-author.md", "/optimizer/proposal/prompt-tests.md",
			"/optimizer/proposal/test-revision.md"))
		{
			requireThat(identifier.matcher(PromptTemplate.load(resource).text()).find(), resource).isFalse();
		}
	}

	/**
	 * Returns the part of a document from a heading to the next heading of the same level.
	 *
	 * @param document the document
	 * @param heading the heading line, such as {@code ### Assertion examples}
	 * @return the heading and the text that follows it
	 */
	private static String section(String document, String heading)
	{
		int start = document.indexOf(heading);
		requireThat(start, "start").isNotNegative();
		int end = document.indexOf("\n### ", start + heading.length());
		if (end == -1)
			end = document.length();
		return document.substring(start, end);
	}
}
