package com.holdmyspot.cat.common.optimizer.execution.prompt.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.ApproachAssignment;
import com.holdmyspot.cat.common.optimizer.campaign.ApproachOrder;
import com.holdmyspot.cat.common.optimizer.campaign.AttemptResult;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.TrialFiles;
import com.holdmyspot.cat.common.optimizer.campaign.OptimizationEvidence;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorApproach;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorDiagnosis;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorBrief;
import com.holdmyspot.cat.common.optimizer.execution.prompt.ApproachRankingPrompt;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests ranking decisions and evidence delivery through {@link ApproachRankingPrompt}.
 */
public final class ApproachRankingPromptTest
{
	private final JsonMapper json = JsonMapper.builder().build();
	private final ApproachRankingPrompt prompt = new ApproachRankingPrompt(json, Path.of("ranking"));

	/**
	 * Equal-fit approaches use exact promotion shares; untried approaches precede zero-share approaches.
	 */
	// Requirement: R-D3
	@Test
	public void tiesUsePromotionRecord()
	{
		OptimizationEvidence evidence = evidence();
		ApproachOrder order = prompt.order(completed(output(true)), "safety", evidence).orElseThrow();
		requireThat(order.approaches().getFirst(), "bestShare").isEqualTo(PromptAuthorApproach.COMBINE_INSTRUCTIONS);
		requireThat(order.approaches().get(1), "halfShare").isEqualTo(PromptAuthorApproach.CORRECT_INSTRUCTION);
		requireThat(order.approaches().get(2), "untried").isEqualTo(PromptAuthorApproach.ADD_GUIDANCE);
		requireThat(order.approaches().getLast(), "zeroShare").isEqualTo(PromptAuthorApproach.REWRITE_INSTRUCTIONS);
		requireThat(order.fallback(), "fallback").isFalse();
	}

	/**
	 * A better promotion record cannot move an approach across a different fit group.
	 */
	// Requirement: R-D3
	@Test
	public void fitPrecedesPromotionRecord()
	{
		ApproachOrder order = prompt.order(completed(output(false)), "safety", evidence()).orElseThrow();
		requireThat(order.approaches(), "fitOrder").isEqualTo(List.of(PromptAuthorApproach.values()));
	}

	/**
	 * The ranking request includes the authors' failure records and earlier-attempt table, with no evidence sections.
	 */
	// Requirement: R-D3
	@Test
	public void briefHasFailuresAndEveryApproach()
	{
		Failure failure = new Failure(Builder.testCase("safety", "logs"), "The reply omitted the confirmation.",
			List.of(), null, List.of(), List.of(),
			new TrialFiles(Path.of("transcript"), Path.of("effects"), Path.of("initial")));
		PromptAuthorDiagnosis diagnosis = new PromptAuthorDiagnosis(1, 1, "safety", Builder.snapshot("working"),
			List.of(), List.of(), List.of(), Path.of("transcript"), Path.of("effects"));
		PromptAuthorBrief brief = new PromptAuthorBrief("safety", PromptAuthorApproach.CORRECT_INSTRUCTION,
			ContractFixtures.contract(), List.of(failure), Map.of("section", "PRIVATE_EVIDENCE_SECTION"), Map.of(),
			List.of(), Map.of(), "", List.of(),
			List.of(new PromptAuthorBrief.EarlierManifest(diagnosis, PromptAuthorBrief.PromotionResult.PROMOTED)),
			List.of());
		SessionRequest request = prompt.request(brief, evidence());
		String turn = request.turns().getFirst();
		requireThat(request.role(), "role").isEqualTo(SessionRole.APPROACH_RANKER);
		requireThat(request.profile(), "profile").isEqualTo(brief.contract().judge().profile());
		requireThat(request.plugins(), "plugins").isEmpty();
		requireThat(request.readOnly(), "readOnly").isEmpty();
		requireThat(turn, "brief").contains(failure.rationale()).contains("| 1 | safety | promoted |").
			doesNotContain("evidence_section").doesNotContain("PRIVATE_EVIDENCE_SECTION");
		for (PromptAuthorApproach approach : PromptAuthorApproach.values())
			requireThat(turn, "approach").contains(approach.text()).contains(EnumNames.lowerCase(approach));
	}

	/**
	 * Schema violations cannot become an approach order.
	 *
	 * @param defect the mutation applied to an otherwise valid ranking
	 */
	// Requirement: R-D3
	@Test(dataProvider = "invalidRankings")
	public void rejectsInvalidRanking(String defect)
	{
		ObjectNode output = output(false);
		ArrayNode approaches = (ArrayNode) output.get("approaches");
		ObjectNode first = (ObjectNode) approaches.get(0);
		switch (defect)
		{
			case "duplicate" -> ((ObjectNode) approaches.get(1)).put("name", first.get("name").asString());
			case "missing" -> approaches.remove(0);
			case "extra" -> output.put("extra", true);
			case "unknown" -> first.put("name", "invented_approach");
			case "blank_reason" -> first.put("reason", " ");
			case "missing_reason" -> first.remove("reason");
			case "extra_entry_field" -> first.put("extra", true);
			case "fractional_fit" -> first.put("fit", 1.5);
			case "reversed_fit" -> first.put("fit", 3);
			case "zero_fit" -> first.put("fit", 0);
			default -> throw new IllegalArgumentException(defect);
		}
		requireThat(prompt.order(completed(output), "safety", evidence()), "invalidRanking").isEmpty();
	}

	/**
	 * Supplies independent schema violations.
	 *
	 * @return invalid ranking cases
	 */
	@DataProvider
	public Object[][] invalidRankings()
	{
		return new Object[][] {{"duplicate"}, {"missing"}, {"extra"}, {"unknown"}, {"blank_reason"},
			{"missing_reason"}, {"extra_entry_field"}, {"fractional_fit"}, {"reversed_fit"}, {"zero_fit"}};
	}

	/**
	 * Creates a complete ranking using the role's documented output contract.
	 *
	 * @param tied indicates whether all approaches share the best fit group
	 * @return the fake session's output
	 */
	private ObjectNode output(boolean tied)
	{
		ObjectNode output = json.createObjectNode();
		ArrayNode approaches = output.putArray("approaches");
		int fit = 1;
		for (PromptAuthorApproach approach : PromptAuthorApproach.values())
		{
			approaches.addObject().put("name", EnumNames.lowerCase(approach)).put("fit", fit).
				put("reason", "It addresses the supplied failures.");
			if (!tied)
				++fit;
		}
		return output;
	}

	/**
	 * Creates a fake completed model response for the production parser.
	 *
	 * @param output the structured response
	 * @return the session result
	 */
	private SessionResult.Completed completed(ObjectNode output)
	{
		return new SessionResult.Completed(Path.of("transcript"), List.of(List.of(json.writeValueAsString(output))),
			Path.of("effects"), Path.of("ranking"), "role-model", null, null, List.of(), "test prompt", List.of());
	}

	/**
	 * Creates records with one full-share, one half-share, one zero-share and eight untried approaches.
	 *
	 * @return the campaign's retained attempt evidence
	 */
	private static OptimizationEvidence evidence()
	{
		List<AttemptResult> attempts = new ArrayList<>();
		for (int number = 1; number <= 4; ++number)
		{
			boolean promoted = number == 1 || number == 4;
			attempts.add(new AttemptResult(1, Builder.snapshot("working"), number, "safety",
				Builder.snapshot("candidate-" + number), List.of(), 1, promoted, promoted));
		}
		return new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of(), attempts, Map.of(), Map.of(
			1, new ApproachAssignment(PromptAuthorApproach.CORRECT_INSTRUCTION, ApproachAssignment.Source.RANKING),
			2, new ApproachAssignment(PromptAuthorApproach.REWRITE_INSTRUCTIONS, ApproachAssignment.Source.RANKING),
			3, new ApproachAssignment(PromptAuthorApproach.CORRECT_INSTRUCTION, ApproachAssignment.Source.RANKING),
			4, new ApproachAssignment(PromptAuthorApproach.COMBINE_INSTRUCTIONS, ApproachAssignment.Source.RANKING)));
	}
}
