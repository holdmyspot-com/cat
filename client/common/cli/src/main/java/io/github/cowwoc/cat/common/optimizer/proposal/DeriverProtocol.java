package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

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
 * Turns a prompt system into a deriver session, and the deriver's output into a validated proposal (design
 * &sect;6.1).
 * <p>
 * The deriver's output follows the proposal schema without the judge fields that CAT owns, plus the deriver's
 * findings that the contract does not hold: the prompt files whose loading no native event reports, and the
 * conditions that make certification unavailable. CAT adds its judge fields before validating the proposal, and
 * rejects output that supplies them.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class DeriverProtocol
{
	private static final Duration TIME_LIMIT = Duration.ofMinutes(30);
	private static final String PROMPT_RESOURCE = "/optimizer/proposal/deriver.md";
	/**
	 * The final turn, which asks for the proposal alone so that no narration of the inspection shares the final
	 * response.
	 */
	private static final String OUTPUT_RESOURCE = "/optimizer/proposal/deriver-output.md";
	private static final String FINDINGS_RESOURCE = "/optimizer/proposal/deriver-findings.schema.json";
	/**
	 * The value of the revision placeholders when there is nothing to revise.
	 */
	private static final String NONE = "none";
	private static final String NOT_ONE_OBJECT = "$: the final response is not exactly one JSON object, either bare " +
		"or alone inside one Markdown code fence";
	private final JsonMapper json;
	private final ProposalSchema schema;
	private final JudgeTemplate judge;
	private final JsonResponse responses;
	private final PromptTemplate prompt;
	private final String outputSchema;
	private final String outputTurn;

	/**
	 * Creates a protocol.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema the proposal schema
	 * @param judge the judge fields that CAT adds to every proposal
	 * @throws NullPointerException if any of the arguments are null
	 * @throws UncheckedIOException if a packaged prompt or schema cannot be read
	 */
	public DeriverProtocol(JsonMapper json, ProposalSchema schema, JudgeTemplate judge)
	{
		requireThat(json, "json").isNotNull();
		requireThat(schema, "schema").isNotNull();
		requireThat(judge, "judge").isNotNull();
		this.json = json;
		this.schema = schema;
		this.judge = judge;
		this.responses = new JsonResponse(json);
		this.prompt = PromptTemplate.load(PROMPT_RESOURCE);
		this.outputSchema = outputSchema(json, schema);
		this.outputTurn = PromptTemplate.readResource(OUTPUT_RESOURCE);
	}

	/**
	 * Returns the schema of the deriver's output: the proposal schema without the judge fields that CAT owns, plus
	 * the deriver's findings.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema the proposal schema
	 * @return the schema text
	 */
	private static String outputSchema(JsonMapper json, ProposalSchema schema)
	{
		ObjectNode root = (ObjectNode) json.readTree(schema.schemaText());
		ObjectNode judgeSchema = (ObjectNode) root.get("properties").get("judge");
		((ObjectNode) judgeSchema.get("properties")).remove(JudgeTemplate.OWNED_FIELDS);
		ArrayNode judgeRequired = json.createArrayNode();
		for (JsonNode name : judgeSchema.get("required"))
		{
			if (!JudgeTemplate.OWNED_FIELDS.contains(name.asString()))
				judgeRequired.add(name);
		}
		judgeSchema.set("required", judgeRequired);
		judgeSchema.put("description", "The rubric and decision rule of the judge profile. CAT supplies the " +
			"judge model, prompt and result schema.");

		JsonNode findings = json.readTree(PromptTemplate.readResource(FINDINGS_RESOURCE));
		((ObjectNode) root.get("properties")).setAll((ObjectNode) findings.get("properties"));
		((ArrayNode) root.get("required")).addAll((ArrayNode) findings.get("required"));
		return json.writerWithDefaultPrettyPrinter().writeValueAsString(root);
	}

	/**
	 * Returns the session that derives the first proposal.
	 *
	 * @param promptSystem the directory that holds the prompt system, which the session works in so that the harness
	 *   loads it as it would in production
	 * @return a request whose role is {@link SessionRole#DERIVER}
	 * @throws NullPointerException if {@code promptSystem} is null
	 */
	public SessionRequest request(Path promptSystem)
	{
		requireThat(promptSystem, "promptSystem").isNotNull();
		return request(promptSystem, NONE, NONE);
	}

	/**
	 * Returns the session that regenerates the complete proposal from the user's feedback.
	 *
	 * @param promptSystem the directory that holds the prompt system
	 * @param previous the proposal that the user gave feedback on
	 * @param feedback the user's feedback
	 * @return a request whose role is {@link SessionRole#DERIVER}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code feedback} is blank
	 */
	public SessionRequest request(Path promptSystem, DerivedProposal previous, String feedback)
	{
		requireThat(promptSystem, "promptSystem").isNotNull();
		requireThat(previous, "previous").isNotNull();
		requireThat(feedback, "feedback").isNotBlank();
		return request(promptSystem, previous.derivation(), feedback);
	}

	/**
	 * Returns a deriver session.
	 *
	 * @param promptSystem the directory that holds the prompt system
	 * @param previous the previous proposal, or {@code none}
	 * @param feedback the user's feedback, or {@code none}
	 * @return the request
	 */
	private SessionRequest request(Path promptSystem, String previous, String feedback)
	{
		String turn = prompt.fill(Map.of("previous_proposal", previous, "feedback", feedback, "output_schema",
			outputSchema));
		return new SessionRequest(SessionRole.DERIVER, promptSystem, List.of(turn, outputTurn), List.of(), TIME_LIMIT);
	}

	/**
	 * Validates a deriver session's output.
	 *
	 * @param result the deriver session's result
	 * @return the proposal
	 * @throws NullPointerException if {@code result} is null
	 * @throws InvalidProposalException if the final response is not exactly one JSON object, bare or alone inside one
	 *   Markdown code fence, or the object violates the deriver's output schema, supplies a judge field that CAT
	 *   owns, or breaks a rule of the contract model
	 */
	public DerivedProposal proposal(SessionResult.Completed result) throws InvalidProposalException
	{
		requireThat(result, "result").isNotNull();
		Optional<ObjectNode> extracted = responses.parseFinalResponse(result);
		if (extracted.isEmpty())
			throw new InvalidProposalException(List.of(NOT_ONE_OBJECT));
		ObjectNode output = extracted.get();
		String derivation = json.writerWithDefaultPrettyPrinter().writeValueAsString(output);

		List<String> problems = new ArrayList<>();
		DeriverFindings findings = new DeriverFindings(problems);
		JsonNode scopeNode = output.remove(DeriverFindings.SCOPE_CONDITIONS);
		List<DerivedSource> derivedSources = findings.derivedSources(output.remove(DeriverFindings.DERIVED_SOURCES));
		List<ScopeCondition> scopeConditions = findings.scopeConditions(scopeNode);
		if (output.get("judge") instanceof ObjectNode judgeNode)
		{
			for (String field : JudgeTemplate.OWNED_FIELDS)
			{
				if (judgeNode.has(field))
					problems.add("$.judge." + field + ": CAT supplies this field, so the deriver must leave it out");
			}
			judgeNode.remove(JudgeTemplate.OWNED_FIELDS);
			judge.complete(judgeNode);
		}
		Contract contract = null;
		try
		{
			contract = schema.parse(json.writeValueAsString(output));
			findings.checkRequirements(scopeNode, contract);
		}
		catch (InvalidProposalException e)
		{
			problems.addAll(e.problems());
		}
		if (!problems.isEmpty())
			throw new InvalidProposalException(problems);
		return new DerivedProposal(contract, derivedSources, scopeConditions, result.promptSources(), derivation);
	}
}
