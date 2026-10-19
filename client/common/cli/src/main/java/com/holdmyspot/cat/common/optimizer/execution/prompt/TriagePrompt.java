package com.holdmyspot.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Impact;
import com.holdmyspot.cat.common.optimizer.campaign.Triage;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.execution.TriageProtocol;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.proposal.JsonResponse;
import com.holdmyspot.cat.common.optimizer.proposal.PromptTemplate;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds requests that decide the impact of one failed development trial, and reads the impact from the triage
 * session's final response.
 * <p>
 * <b>Thread Safety:</b> Callers may build requests and read impacts concurrently.
 */
public final class TriagePrompt implements TriageProtocol
{
	private static final Duration TIME_LIMIT = Duration.ofMinutes(10);
	private static final String PROMPT_RESOURCE = "/optimizer/triage/triage.md";
	private static final String SCHEMA_RESOURCE = "/optimizer/triage/impact.schema.json";
	private final JsonMapper json;
	private final Path triageDirectory;
	private final JsonResponse responses;
	private final PromptTemplate prompt;
	private final String resultSchema;

	/**
	 * Creates the triage prompt.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param triageDirectory the existing directory that triage sessions work in; it must contain no prompt files, so
	 *   those sessions do not load the candidate's instructions
	 * @throws NullPointerException if any of the arguments are null
	 * @throws UncheckedIOException if the packaged triage prompt or result schema cannot be read
	 */
	public TriagePrompt(JsonMapper json, Path triageDirectory)
	{
		requireThat(json, "json").isNotNull();
		requireThat(triageDirectory, "triageDirectory").isNotNull();
		this.json = json;
		this.triageDirectory = triageDirectory;
		this.responses = new JsonResponse(json);
		this.prompt = PromptTemplate.load(PROMPT_RESOURCE);
		this.resultSchema = pretty(json.readTree(PromptTemplate.load(SCHEMA_RESOURCE).text()));
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The prompt defines both impacts and gives the result schema, then the evidence as data: the failure record, the
	 * test case, every message of the reply and the built-in tool results as JSON, and the rendered trial activity and
	 * file changes. The effects directory and the directory that holds the trial's transcript are readable.
	 */
	@Override
	public SessionRequest request(TrialProfile profile, DrawnTestCase testCase, SessionResult.Completed evidence,
		List<BuiltInToolResult> results, String evidenceSection, String failure)
	{
		requireThat(profile, "profile").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		requireThat(evidence, "evidence").isNotNull();
		requireThat(results, "results").isNotNull();
		requireThat(evidenceSection, "evidenceSection").isNotNull();
		requireThat(failure, "failure").isNotNull();
		ArrayNode responseArray = json.createArrayNode();
		for (List<String> turn : evidence.responses())
		{
			ArrayNode messages = responseArray.addArray();
			turn.forEach(messages::add);
		}
		Map<String, String> textByName = PromptTest.textByName(testCase.testCase());
		ArrayNode resultArray = json.createArrayNode();
		for (BuiltInToolResult result : results)
		{
			resultArray.addObject().
				put("assertion_id", result.assertion().id()).
				put("statement", result.assertion().statement(textByName)).
				put("tool", result.assertion().tool()).
				put("on_tool_failure", EnumNames.lowerCase(result.assertion().onToolFailure())).
				put("passed", result.passed()).
				put("observed", result.observed());
		}
		String turn = prompt.fill(Map.of(
			"failure", failure,
			"test_case", pretty(TestCaseJson.encode(json, testCase)),
			"responses", pretty(responseArray),
			"evidence", evidenceSection,
			"effects", evidence.effects().toString(),
			"transcript", evidence.transcript().toString(),
			"tool_results", pretty(resultArray),
			"result_schema", resultSchema));
		List<Path> readOnly = new ArrayList<>();
		readOnly.add(evidence.effects());
		Path transcriptDirectory = evidence.transcript().getParent();
		if (transcriptDirectory != null && !readOnly.contains(transcriptDirectory))
			readOnly.add(transcriptDirectory);
		return new SessionRequest(SessionRole.TRIAGE, Part.DEVELOPMENT, triageDirectory, List.of(turn), readOnly,
			TIME_LIMIT, profile);
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
	 * fence, that holds exactly an {@code impact} of {@code blocking} or {@code self_recovering} and a non-blank
	 * {@code rationale}, as the result schema requires. The triage keeps the rationale as the session wrote it.
	 */
	@Override
	public Optional<Triage> triage(SessionResult.Completed result)
	{
		requireThat(result, "result").isNotNull();
		Optional<ObjectNode> output = responses.parseFinalResponse(result);
		if (output.isEmpty() || output.get().size() != 2)
			return Optional.empty();
		JsonNode impact = output.get().get("impact");
		JsonNode rationale = output.get().get("rationale");
		if (impact == null || !impact.isString() || rationale == null || !rationale.isString() ||
			rationale.asString().isBlank())
		{
			return Optional.empty();
		}
		for (Impact candidate : Impact.values())
		{
			if (candidate.label().equals(impact.asString()))
				return Optional.of(new Triage(candidate, rationale.asString()));
		}
		return Optional.empty();
	}
}
