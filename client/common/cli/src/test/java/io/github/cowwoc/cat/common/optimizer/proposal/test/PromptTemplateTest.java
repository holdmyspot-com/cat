package io.github.cowwoc.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.proposal.PromptTemplate;
import java.util.HashMap;
import java.util.Map;

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
}
