package com.holdmyspot.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.ApproachOrder;
import com.holdmyspot.cat.common.optimizer.campaign.AttemptResult;
import com.holdmyspot.cat.common.optimizer.campaign.OptimizationEvidence;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorApproach;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorDiagnosis;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.execution.ApproachRankingProtocol;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorBrief;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.proposal.JsonResponse;
import com.holdmyspot.cat.common.optimizer.proposal.PromptTemplate;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders the compact approach-ranking brief and accepts only a complete, ordered ranking.
 * <p>
 * Equal-fit neighbors are ordered by their exact promotion shares in the addressed cell.
 */
public final class ApproachRankingPrompt implements ApproachRankingProtocol
{
	private final JsonMapper json;
	private final Path directory;
	private final JsonResponse responses;
	private final PromptTemplate prompt = PromptTemplate.load("/optimizer/approach-ranking/rank.md");
	private final String schema = PromptTemplate.load("/optimizer/approach-ranking/ranking.schema.json").text();

	/**
	 * Creates the ranking protocol.
	 *
	 * @param json      the operation-owned mapper
	 * @param directory the existing role directory containing no candidate prompt files
	 * @throws NullPointerException if any argument is null
	 */
	public ApproachRankingPrompt(JsonMapper json, Path directory)
	{
		requireThat(json, "json").isNotNull();
		requireThat(directory, "directory").isNotNull();
		this.json = json;
		this.directory = directory;
		responses = new JsonResponse(json);
	}

	@Override
	public SessionRequest request(PromptAuthorBrief brief, OptimizationEvidence evidence)
	{
		ObjectNode failures = PromptAuthorPrompt.failureEvidence(json, brief);
		for (JsonNode failure : failures.get("failures"))
			((ObjectNode) failure).remove("evidence_section");
		ArrayNode approaches = json.createArrayNode();
		Map<PromptAuthorApproach, PromotionRecord> records = records(brief.cell(), evidence);
		for (PromptAuthorApproach approach : PromptAuthorApproach.values())
		{
			PromotionRecord record = records.get(approach);
			approaches.addObject().put("name", EnumNames.lowerCase(approach)).put("instruction", approach.text()).
				put("attempts", record.attempts()).put("promoted", record.promoted());
		}
		String turn = prompt.fill(Map.of("cell", brief.cell(), "failures", json.writeValueAsString(failures),
			"earlier_attempts", PromptAuthorPrompt.earlierAttemptsTable(brief.earlierManifests()),
			"approaches", json.writeValueAsString(approaches), "result_schema", schema));
		return new SessionRequest(SessionRole.APPROACH_RANKER, Part.DEVELOPMENT, directory, List.of(turn), List.of(),
			Duration.ofMinutes(10), brief.contract().judge().profile());
	}

	@Override
	public Optional<ApproachOrder> order(SessionResult.Completed result, String cell, OptimizationEvidence evidence)
	{
		Optional<ObjectNode> output = responses.parseFinalResponse(result);
		if (output.isEmpty() || output.get().size() != 1)
			return Optional.empty();
		JsonNode array = output.get().get("approaches");
		if (array == null || !array.isArray() || array.size() != PromptAuthorApproach.values().length)
			return Optional.empty();
		List<Rank> ranks = new ArrayList<>();
		Set<PromptAuthorApproach> seen = EnumSet.noneOf(PromptAuthorApproach.class);
		int previousFit = 1;
		for (JsonNode node : array)
		{
			if (!node.isObject() || node.size() != 3)
				return Optional.empty();
			JsonNode name = node.get("name");
			JsonNode fit = node.get("fit");
			JsonNode reason = node.get("reason");
			boolean validFields = name != null && name.isString() && fit != null && fit.isIntegralNumber() &&
				fit.canConvertToInt() && reason != null && reason.isString() && !reason.asString().isBlank();
			if (!validFields)
				return Optional.empty();
			int value = fit.asInt();
			if (value < previousFit || value > PromptAuthorApproach.values().length)
				return Optional.empty();
			PromptAuthorApproach approach;
			try
			{
				approach = EnumNames.parse(name.asString(), PromptAuthorApproach.class);
			}
			catch (IllegalArgumentException _)
			{
				return Optional.empty();
			}
			if (!seen.add(approach))
				return Optional.empty();
			ranks.add(new Rank(approach, value, reason.asString()));
			previousFit = value;
		}
		Map<PromptAuthorApproach, PromotionRecord> records = records(cell, evidence);
		ranks.sort(Comparator.comparingInt(Rank::fit).thenComparing((left, right) ->
			compare(records.get(left.approach()), records.get(right.approach()))));
		return Optional.of(new ApproachOrder(ranks.stream().map(Rank::approach).toList(),
			ranks.stream().map(Rank::reason).toList(), false));
	}

	/**
	 * Counts each retained attempt once, including rejected manifests and attempts without a candidate.
	 *
	 * @param cell     the addressed cell
	 * @param evidence the campaign's earlier attempts
	 * @return promotion counts for every approach
	 */
	private static Map<PromptAuthorApproach, PromotionRecord> records(String cell, OptimizationEvidence evidence)
	{
		Map<Integer, Boolean> attempts = new TreeMap<>();
		for (PromptAuthorDiagnosis diagnosis : evidence.promptAuthors())
		{
			if (diagnosis.cell().equals(cell))
				attempts.put(diagnosis.number(), false);
		}
		for (AttemptResult result : evidence.attempts())
		{
			if (result.cell().equals(cell))
				attempts.put(result.number(), result.winner());
		}
		Map<PromptAuthorApproach, PromotionRecord> records = new EnumMap<>(PromptAuthorApproach.class);
		for (PromptAuthorApproach approach : PromptAuthorApproach.values())
			records.put(approach, new PromotionRecord(0, 0));
		for (Map.Entry<Integer, Boolean> attempt : attempts.entrySet())
		{
			PromptAuthorApproach approach = evidence.approach(attempt.getKey(), cell).approach();
			PromotionRecord record = records.get(approach);
			int promoted = record.promoted();
			if (attempt.getValue())
				++promoted;
			records.put(approach, new PromotionRecord(record.attempts() + 1, promoted));
		}
		return records;
	}

	/**
	 * Compares exact shares, putting an untried approach after positive shares and before a zero share.
	 *
	 * @param left  one approach's record
	 * @param right the other record
	 * @return a negative value when the left record sorts first
	 */
	private static int compare(PromotionRecord left, PromotionRecord right)
	{
		if (left.attempts() == 0 || right.attempts() == 0)
		{
			if (left.attempts() == right.attempts())
				return 0;
			if (left.attempts() == 0)
			{
				if (right.promoted() == 0)
					return -1;
				return 1;
			}
			if (left.promoted() == 0)
				return 1;
			return -1;
		}
		return Long.compare((long) right.promoted() * left.attempts(), (long) left.promoted() * right.attempts());
	}

	/**
	 * An approach's completed attempt and promotion counts in one cell.
	 *
	 * @param attempts retained attempts
	 * @param promoted attempts whose result was promoted
	 */
	private record PromotionRecord(int attempts, int promoted)
	{
	}

	/**
	 * One validated model decision.
	 *
	 * @param approach the approach
	 * @param fit      the ascending fit group
	 * @param reason   the model's reason
	 */
	private record Rank(PromptAuthorApproach approach, int fit, String reason)
	{
	}
}
