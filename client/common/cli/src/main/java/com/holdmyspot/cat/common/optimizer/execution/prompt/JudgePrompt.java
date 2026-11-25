package com.holdmyspot.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.evidence.EvidencePrompt;
import com.holdmyspot.cat.common.optimizer.execution.AssertionVerdict;
import com.holdmyspot.cat.common.optimizer.execution.JudgeProtocol;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.proposal.JsonResponse;
import com.holdmyspot.cat.common.optimizer.proposal.PromptTemplate;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds requests that evaluate one trial against the confirmed rubric and decision rule, and reads the verdict
 * from the judge's final response.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
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
	 * The profile's prompt receives the rubric, the decision rule, the assertions to decide and the result schema, then
	 * the evidence as data: the test case inputs, the responses and the built-in tool results as JSON, and the rendered
	 * trial activity and file changes. Each assertion's statement uses the test case values for its placeholders,
	 * including the expected outcome when named. A template that names the effects or transcript placeholders receives
	 * those paths. The effects directory is readable, and naming the transcript grants read-only access to its directory.
	 * <p>
	 * Assertions of the {@link EvidenceScope#RESPONSE} scope are instead decided from the profile's response prompt,
	 * which receives the rubric, the decision rule, the assertions, the test case, the replies and the result schema,
	 * and no evidence section, built-in tool result or path; the session may read no file.
	 *
	 * @throws IllegalArgumentException if {@code assertions} is empty or holds assertions of both scopes, or the
	 *                                  assertions are of the response scope and the profile has no response prompt
	 */
	@Override
	public SessionRequest request(JudgeProfile profile, DrawnTestCase testCase, SessionResult.Completed evidence,
		List<JudgedAssertion> assertions, List<BuiltInToolResult> results, String evidenceSection)
	{
		requireThat(profile, "profile").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		requireThat(evidence, "evidence").isNotNull();
		requireThat(assertions, "assertions").isNotEmpty();
		requireThat(results, "results").isNotNull();
		requireThat(evidenceSection, "evidenceSection").isNotNull();
		EvidenceScope scope = assertions.getFirst().scope();
		for (JudgedAssertion judged : assertions)
		{
			if (judged.scope() != scope)
			{
				throw new IllegalArgumentException("One judge session decides the assertions of one evidence scope, " +
					"but '" + assertions.getFirst().assertion().id() + "' is " + EnumNames.lowerCase(scope) + " and '" +
					judged.assertion().id() + "' is " + EnumNames.lowerCase(judged.scope()) + ".");
			}
		}
		ArrayNode responseArray = json.createArrayNode();
		for (List<String> turn : evidence.responses())
		{
			ArrayNode messages = responseArray.addArray();
			turn.forEach(messages::add);
		}
		Map<String, String> textByName = PromptTest.textByName(testCase.testCase());
		ArrayNode assertionArray = json.createArrayNode();
		for (JudgedAssertion judged : assertions)
		{
			ObjectNode node = assertionArray.addObject().
				put("id", judged.assertion().id()).
				put("statement", judged.assertion().statement(textByName));
			Requirement requirement = judged.requirement();
			node.putObject("requirement").
				put("id", requirement.id()).
				put("description", requirement.description()).
				put("source", requirement.source());
			node.set("behaviors", json.valueToTree(judged.behaviors()));
		}
		ArrayNode resultArray = json.createArrayNode();
		for (BuiltInToolResult result : results)
		{
			resultArray.addObject().
				put("assertion_id", result.assertion().id()).
				put("statement", result.assertion().statement(textByName)).
				put("tool", result.assertion().tool()).
				put("on_tool_failure", EnumNames.lowerCase(result.assertion().onToolFailure())).
				put("passed", result.passed()).
				put("observed", result.judgeObserved());
		}
		ObjectNode caseEvidence = TestCaseJson.encode(json, testCase);
		caseEvidence.remove(TestCase.EXPECTED_OUTCOME);
		String caseJson = pretty(caseEvidence);
		if (scope == EvidenceScope.RESPONSE)
		{
			String template = profile.prompt(EvidenceScope.RESPONSE);
			Map<String, String> values = new HashMap<>(Map.of(
				"rubric", profile.rubric(),
				"decision_rule", profile.decisionRule(),
				"assertions", pretty(assertionArray),
				"test_case", caseJson,
				"responses", pretty(responseArray),
				"result_schema", pretty(json.readTree(profile.resultSchema()))));
			values.keySet().removeIf(name -> !template.contains("{{" + name + "}}"));
			return new SessionRequest(SessionRole.JUDGE, Part.HELD_OUT, judgeDirectory,
				List.of(new PromptTemplate(template).fill(values)), List.of(), TIME_LIMIT, profile.profile());
		}
		Path originalTranscript = evidence.transcript();
		Path originalEffects = evidence.effects();
		Map<String, String> values = new HashMap<>(Map.of(
			"rubric", profile.rubric(),
			"decision_rule", profile.decisionRule(),
			"assertions", pretty(assertionArray),
			"test_case", caseJson,
			"responses", pretty(responseArray),
			"effects", originalEffects.toString(),
			"transcript", originalTranscript.toString(),
			"evidence", evidenceSection,
			"tool_results", pretty(resultArray),
			"result_schema", pretty(json.readTree(profile.resultSchema()))));
		values.keySet().removeIf(name -> !profile.prompt().contains("{{" + name + "}}"));
		EvidencePrompt prompt = new EvidencePrompt(profile.prompt(), values, originalTranscript, originalEffects);

		List<Path> readOnly = new ArrayList<>();
		readOnly.add(originalEffects);
		Path transcriptDirectory = originalTranscript.getParent();
		if (profile.prompt().contains("{{transcript}}") && transcriptDirectory != null &&
			!readOnly.contains(transcriptDirectory))
			readOnly.add(transcriptDirectory);
		return new SessionRequest(SessionRole.JUDGE, Part.HELD_OUT, judgeDirectory,
			List.of(prompt.render(UnaryOperator.identity())), readOnly, TIME_LIMIT, profile.profile()).
			withEvidencePrompt(prompt);
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
	 * fence, that holds exactly {@code assertions}, as CAT's result schema requires. It holds one item per decided
	 * assertion, each with exactly its {@code id}, a {@code verdict} of {@code pass} or {@code fail}, a non-blank
	 * {@code rationale} and a {@code behavior}: {@code null} for a pass, and for a failure one of the behaviors that the
	 * assertion admits. Each decision keeps its rationale as the judge wrote it.
	 */
	@Override
	public Optional<List<AssertionVerdict>> verdicts(SessionResult.Completed result, List<JudgedAssertion> decided)
	{
		requireThat(result, "result").isNotNull();
		requireThat(decided, "decided").isNotNull();
		Optional<ObjectNode> output = responses.parseFinalResponse(result);
		if (output.isEmpty() || output.get().size() != 1)
			return Optional.empty();
		JsonNode items = output.get().get("assertions");
		if (items == null || !items.isArray() || items.size() != decided.size())
			return Optional.empty();
		Map<String, JudgedAssertion> assertionById = new HashMap<>();
		for (JudgedAssertion judged : decided)
			assertionById.put(judged.assertion().id(), judged);
		Set<String> named = new HashSet<>();
		List<AssertionVerdict> verdicts = new ArrayList<>();
		for (JsonNode item : items)
		{
			Optional<Decided> decision = decision(item, assertionById);
			if (decision.isEmpty() || !named.add(decision.get().id()))
				return Optional.empty();
			Decided assertion = decision.get();
			verdicts.add(new AssertionVerdict(assertion.id(), assertion.behavior(), assertion.rationale()));
		}
		return Optional.of(List.copyOf(verdicts));
	}

	/**
	 * The judge's decision of one assertion.
	 *
	 * @param id the assertion's identifier
	 * @param rationale the evidence that decided it
	 * @param behavior the behavior through which the trial failed it, or {@code null} if the trial passed it
	 */
	private record Decided(String id, String rationale, String behavior)
	{
	}

	/**
	 * Reads the judge's decision of one assertion.
	 *
	 * @param item one item of the judge's {@code assertions}
	 * @param assertionById the decided assertions, by identifier
	 * @return the decision, or empty if the item does not match the result schema, names an assertion outside
	 *   {@code assertionById}, or names a behavior that its assertion does not admit
	 */
	private static Optional<Decided> decision(JsonNode item, Map<String, JudgedAssertion> assertionById)
	{
		if (!item.isObject() || item.size() != 4)
			return Optional.empty();
		JsonNode id = item.get("id");
		JsonNode verdict = item.get("verdict");
		JsonNode rationale = item.get("rationale");
		JsonNode behavior = item.get("behavior");
		if (id == null || !id.isString() || verdict == null || !verdict.isString() || rationale == null ||
			!rationale.isString() || rationale.asString().isBlank() || behavior == null)
		{
			return Optional.empty();
		}
		JudgedAssertion assertion = assertionById.get(id.asString());
		if (assertion == null)
			return Optional.empty();
		return switch (verdict.asString())
		{
			case "pass" ->
			{
				if (!behavior.isNull())
					yield Optional.empty();
				yield Optional.of(new Decided(id.asString(), rationale.asString(), null));
			}
			case "fail" ->
			{
				if (!behavior.isString() || !assertion.admits(behavior.asString()))
					yield Optional.empty();
				yield Optional.of(new Decided(id.asString(), rationale.asString(), behavior.asString()));
			}
			default -> Optional.empty();
		};
	}
}
