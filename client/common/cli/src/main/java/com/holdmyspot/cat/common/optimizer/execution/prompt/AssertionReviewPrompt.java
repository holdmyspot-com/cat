package com.holdmyspot.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview;
import com.holdmyspot.cat.common.optimizer.proposal.PromptTemplate;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders a review of the evidence scope and split of each judged assertion in one prompt test.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class AssertionReviewPrompt
{
	private static final String PROMPT_RESOURCE = "/optimizer/assertions/assertion-review.md";
	private final JsonMapper json;
	private final PromptTemplate template;
	private final String resultSchema;

	/**
	 * Creates the assertion review prompt.
	 *
	 * @param json the operation-owned JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 * @throws UncheckedIOException if the packaged review prompt or result schema cannot be read
	 */
	public AssertionReviewPrompt(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
		this.template = PromptTemplate.load(PROMPT_RESOURCE);
		this.resultSchema = AssertionReview.resultSchema();
	}

	/**
	 * Renders the test's declared assertions, their tools, and the scope and requirement of each judged assertion.
	 * Statement and tool placeholders keep their original form.
	 *
	 * @param assertions the test's declared assertions
	 * @param requirementById the requirements of the contract, by id
	 * @return the reviewer's prompt
	 * @throws NullPointerException if any of the arguments or assertions is null
	 * @throws IllegalArgumentException if the requirements omit one that a judged assertion checks
	 */
	public String render(List<MappedAssertion> assertions, Map<String, Requirement> requirementById)
	{
		requireThat(assertions, "assertions").isNotNull();
		requireThat(requirementById, "requirementById").isNotNull();
		ArrayNode declarations = json.createArrayNode();
		for (MappedAssertion mapped : assertions)
		{
			Assertion assertion = mapped.assertion();
			ObjectNode declaration = declarations.addObject().
				put("id", assertion.id()).
				put("statement", assertion.statement()).
				put("tool", assertion.tool()).
				put("on_tool_failure", EnumNames.lowerCase(assertion.onToolFailure())).
				put("requirement_id", mapped.requirementId());
			EvidenceScope scope = AssertionReview.scopeOf(assertion).orElse(null);
			if (scope != null)
			{
				Requirement requirement = requirementById.get(mapped.requirementId());
				if (requirement == null)
				{
					throw new IllegalArgumentException("The assertion '" + assertion.id() + "' checks the requirement '" +
						mapped.requirementId() + "', which is not listed");
				}
				declaration.put("evidence_scope", EnumNames.lowerCase(scope)).
					putObject("requirement").
					put("description", requirement.description()).
					put("trigger", requirement.trigger());
			}
		}
		return template.fill(Map.of(
			"assertions", json.writerWithDefaultPrettyPrinter().writeValueAsString(declarations),
			"result_schema", resultSchema));
	}
}
