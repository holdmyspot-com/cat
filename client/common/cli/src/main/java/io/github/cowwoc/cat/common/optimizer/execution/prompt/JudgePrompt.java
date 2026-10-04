package io.github.cowwoc.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.CheckResult;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.execution.JudgeProtocol;
import io.github.cowwoc.cat.common.optimizer.execution.Judgment;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.proposal.JsonResponse;
import io.github.cowwoc.cat.common.optimizer.proposal.PromptTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds requests that evaluate one trial against the confirmed rubric and decision rule, and reads the verdict
 * from the judge's final response.
 * <p>
 * <b>Thread Safety:</b> Callers may build requests and read verdicts concurrently.
 */
public final class JudgePrompt implements JudgeProtocol
{
	private static final Duration TIME_LIMIT = Duration.ofMinutes(10);
	private final JsonMapper json;
	private final Path judgeDirectory;
	private final JsonResponse responses;

	/**
	 * Creates the judge prompt.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param judgeDirectory the existing directory that judge sessions work in; it must contain no prompt files, so
	 *   those sessions do not load the candidate's instructions
	 * @throws NullPointerException if any of the arguments are null
	 */
	public JudgePrompt(JsonMapper json, Path judgeDirectory)
	{
		requireThat(json, "json").isNotNull();
		requireThat(judgeDirectory, "judgeDirectory").isNotNull();
		this.json = json;
		this.judgeDirectory = judgeDirectory;
		this.responses = new JsonResponse(json);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The profile's prompt receives the rubric, the decision rule, the semantic requirements to decide and the result
	 * schema, then the evidence as data: the instance, the responses and the deterministic results as JSON, and the
	 * rendered trial activity and file changes. The effects directory is readable. A template that names the effects
	 * or transcript placeholders receives those paths, and naming the transcript grants read-only access to its
	 * directory.
	 *
	 * @throws IllegalArgumentException if {@code requirements} is empty
	 */
	@Override
	public SessionRequest request(JudgeProfile profile, TrialInstance instance, SessionResult.Completed evidence,
		List<Requirement> requirements, List<CheckResult> results, String evidenceSection)
	{
		requireThat(profile, "profile").isNotNull();
		requireThat(instance, "instance").isNotNull();
		requireThat(evidence, "evidence").isNotNull();
		requireThat(requirements, "requirements").isNotEmpty();
		requireThat(results, "results").isNotNull();
		requireThat(evidenceSection, "evidenceSection").isNotNull();
		ArrayNode responseArray = json.createArrayNode();
		for (List<String> turn : evidence.responses())
		{
			ArrayNode messages = responseArray.addArray();
			turn.forEach(messages::add);
		}
		ArrayNode requirementArray = json.createArrayNode();
		for (Requirement requirement : requirements)
		{
			requirementArray.addObject().
				put("id", requirement.id()).
				put("description", requirement.description()).
				put("source", requirement.source());
		}
		ArrayNode resultArray = json.createArrayNode();
		for (CheckResult result : results)
		{
			resultArray.addObject().
				put("requirement_id", result.check().requirementId()).
				put("kind", result.check().criterion().kind()).
				put("check", result.check().criterion().describe()).
				put("passed", result.passed()).
				put("observed", result.observed());
		}
		Map<String, String> values = new HashMap<>(Map.of(
			"rubric", profile.rubric(),
			"decision_rule", profile.decisionRule(),
			"requirements", pretty(requirementArray),
			"instance", pretty(InstanceJson.encode(json, instance)),
			"responses", pretty(responseArray),
			"effects", evidence.effects().toString(),
			"transcript", evidence.transcript().toString(),
			"evidence", evidenceSection,
			"deterministic_results", pretty(resultArray),
			"result_schema", pretty(json.readTree(profile.resultSchema()))));
		values.keySet().removeIf(name -> !profile.prompt().contains("{{" + name + "}}"));
		String turn = new PromptTemplate(profile.prompt()).fill(values);

		List<Path> readOnly = new ArrayList<>();
		readOnly.add(evidence.effects());
		Path transcriptDirectory = evidence.transcript().getParent();
		if (profile.prompt().contains("{{transcript}}") && transcriptDirectory != null &&
			!readOnly.contains(transcriptDirectory))
			readOnly.add(transcriptDirectory);
		return new SessionRequest(SessionRole.JUDGE, Part.HELD_OUT, judgeDirectory, List.of(turn), readOnly, TIME_LIMIT,
			profile.profile());
	}

	/**
	 * Returns JSON as indented text.
	 *
	 * @param node the JSON
	 * @return the text
	 */
	private String pretty(JsonNode node)
	{
		return json.writerWithDefaultPrettyPrinter().writeValueAsString(node);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The output is the final response when it is exactly one JSON object, bare or alone inside one Markdown code
	 * fence, that holds exactly a {@code verdict} of {@code pass} or {@code fail} and a non-blank {@code rationale}, as
	 * CAT's result schema requires. The judgment keeps the rationale as the judge wrote it.
	 */
	@Override
	public Optional<Judgment> judgment(SessionResult.Completed result)
	{
		requireThat(result, "result").isNotNull();
		Optional<ObjectNode> output = responses.parseFinalResponse(result);
		if (output.isEmpty() || output.get().size() != 2)
			return Optional.empty();
		JsonNode verdict = output.get().get("verdict");
		JsonNode rationale = output.get().get("rationale");
		if (verdict == null || !verdict.isString() || rationale == null || !rationale.isString() ||
			rationale.asString().isBlank())
		{
			return Optional.empty();
		}
		return switch (verdict.asString())
		{
			case "pass" -> Optional.of(new Judgment(Verdict.PASS, rationale.asString()));
			case "fail" -> Optional.of(new Judgment(Verdict.FAIL, rationale.asString()));
			default -> Optional.empty();
		};
	}
}
