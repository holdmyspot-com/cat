package com.holdmyspot.cat.common.optimizer.execution.prompt.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.execution.prompt.AssertionReviewPrompt;
import com.holdmyspot.cat.common.optimizer.proposal.PromptTemplate;

import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the instructions for reviewing a test's assertion scopes.
 */
public final class AssertionReviewPromptTest
{
	/**
	 * Creates the test.
	 */
	public AssertionReviewPromptTest()
	{
	}

	/**
	 * Verifies that the packaged review instructions give the reviewer the evidence boundaries and each decision.
	 */
	@Test
	public void directsAllThreeDecisions()
	{
		// Requirement: R-L16
		// Requirement: R-L19
		// Requirement: R-L20
		String prompt = PromptTemplate.load("/optimizer/assertions/assertion-review.md").fill(Map.of(
			"assertions", "[{\"id\":\"ask_first\"}]", "result_schema", "{}"));
		requireThat(prompt, "prompt").contains("\"id\":\"ask_first\"").
			contains("response_depends_on_actions").contains("actions_depends_only_on_replies").
			contains("action_decidable_by_tool").contains("assistant messages").contains("test case");
	}

	/**
	 * Checks that rendering includes tools and scopes without expanding placeholders or including unrelated requirements.
	 */
	@Test
	public void rendersOnlyReviewDeclarations()
	{
		// Requirement: R-L16
		// Requirement: R-L19
		// Requirement: R-L20
		Requirement explain = new Requirement("explain", "Explain why a backup is required.", "rules/backup.md",
			"The user requests a deletion.", List.of("unexplained"));
		Requirement unused = new Requirement("unrelated", "sentinel unrelated requirement", "rules/other.md",
			null, List.of("unrelated_failure"));
		MappedAssertion judged = new MappedAssertion(new Assertion("explain_backup", AssertionKind.EFFECT,
			"pattern {{target}}", OnToolFailure.JUDGE, "Explain {{target}} using {{expected_outcome}}.", null,
			EvidenceScope.RESPONSE), "explain");
		MappedAssertion legacy = new MappedAssertion(new Assertion("brief", AssertionKind.EFFECT, null,
			"The tool call's brief is sufficient."), "explain");
		MappedAssertion mechanical = new MappedAssertion(new Assertion("delegated", AssertionKind.EFFECT,
			"tool-used delegate", "The delegation happened."), "explain");

		JsonMapper json = JsonMapper.builder().build();
		String prompt = new AssertionReviewPrompt(json).render(List.of(judged, legacy, mechanical),
			Map.of("explain", explain, "unrelated", unused));
		int start = prompt.indexOf("<assertions>") + "<assertions>".length();
		int end = prompt.indexOf("</assertions>", start);
		JsonNode assertions = json.readTree(prompt.substring(start, end));
		requireThat(assertions.size(), "assertionCount").isEqualTo(3);
		JsonNode response = assertions.get(0);
		requireThat(response.get("id").asString(), "responseId").isEqualTo("explain_backup");
		requireThat(response.get("statement").asString(), "statement").
			isEqualTo("Explain {{target}} using {{expected_outcome}}.");
		requireThat(response.get("tool").asString(), "fallbackTool").isEqualTo("pattern {{target}}");
		requireThat(response.get("on_tool_failure").asString(), "fallback").isEqualTo("judge");
		requireThat(response.get("evidence_scope").asString(), "responseScope").isEqualTo("response");
		requireThat(response.get("requirement").get("description").asString(), "description").
			isEqualTo(explain.description());
		requireThat(response.get("requirement").get("trigger").asString(), "trigger").isEqualTo(explain.trigger());
		requireThat(assertions.get(1).get("evidence_scope").asString(), "legacyScope").isEqualTo("actions");
		JsonNode tool = assertions.get(2);
		requireThat(tool.get("tool").asString(), "mechanicalTool").isEqualTo("tool-used delegate");
		requireThat(tool.has("evidence_scope"), "mechanicalScope").isFalse();
		requireThat(tool.has("requirement"), "mechanicalRequirementText").isFalse();
		requireThat(prompt.contains(unused.description()), "unrelatedRequirement").isFalse();
	}
}
