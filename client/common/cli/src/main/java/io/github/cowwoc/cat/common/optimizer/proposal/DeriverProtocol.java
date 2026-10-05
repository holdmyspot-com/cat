package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.CapabilityRanking;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfiles;
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
import java.util.Set;
import java.util.StringJoiner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates requests for the session that reads a prompt system and proposes its requirements and tests (the
 * deriver), and validates that session's output.
 * <p>
 * The deriver's output follows the proposal schema without the judge fields, the trial profiles and the discarded
 * checks that CAT owns, plus the deriver's additional findings: the prompt files whose loading no
 * harness event reports, the user's profile restriction, the conditions that make certification unavailable,
 * and the groups of requirements that no reply can satisfy together.
 * CAT adds its judge fields and the core-task requirement, which every test checks, computes the trial profiles, and
 * runs each deterministic check against its controls before validating the proposal. It rejects
 * output that supplies a field that CAT owns or a requirement with the core-task requirement's identifier, or omits
 * a citation for a recorded regression scenario of a prompt source.
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
	private final RegressionScenarios scenarios;

	/**
	 * Creates the deriver request builder and response validator.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema the proposal schema
	 * @param judge the judge fields that CAT adds to every proposal
	 * @param scenarios the available recorded scenarios that CAT supplies and checks
	 * @throws NullPointerException if any of the arguments are null
	 * @throws UncheckedIOException if a packaged prompt or schema cannot be read
	 */
	public DeriverProtocol(JsonMapper json, ProposalSchema schema, JudgeTemplate judge, RegressionScenarios scenarios)
	{
		requireThat(json, "json").isNotNull();
		requireThat(schema, "schema").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(scenarios, "scenarios").isNotNull();
		this.json = json;
		this.schema = schema;
		this.judge = judge;
		this.scenarios = scenarios;
		this.responses = new JsonResponse(json);
		this.prompt = PromptTemplate.load(PROMPT_RESOURCE);
		this.outputSchema = outputSchema(json, schema);
		this.outputTurn = PromptTemplate.readResource(OUTPUT_RESOURCE);
	}

	/**
	 * Returns the schema of the deriver's output: the proposal schema without the judge fields, the trial profiles and
	 * the discarded checks that CAT owns, plus the deriver's findings.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema the proposal schema
	 * @return the schema text
	 */
	private static String outputSchema(JsonMapper json, ProposalSchema schema)
	{
		ObjectNode root = schema.derivationSchema();
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
			"role profile, prompt and result schema.");

		JsonNode findings = json.readTree(PromptTemplate.readResource(FINDINGS_RESOURCE));
		((ObjectNode) root.get("properties")).setAll((ObjectNode) findings.get("properties"));
		((ArrayNode) root.get("required")).addAll((ArrayNode) findings.get("required"));
		return json.writerWithDefaultPrettyPrinter().writeValueAsString(root);
	}

	/**
	 * Returns the request for deriving the first proposal.
	 *
	 * @param promptSystem the directory that holds the prompt system, which the session works in so that the harness
	 *   loads it as it would in production
	 * @param profiles the harness's profile inventory, which a profile restriction selects from, and its capability
	 *   ranking
	 * @return a request whose role is {@link SessionRole#DERIVER}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public SessionRequest request(Path promptSystem, ProfileEvidence profiles)
	{
		requireThat(promptSystem, "promptSystem").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		return request(promptSystem, profiles, NONE, NONE);
	}

	/**
	 * Returns the request for regenerating the complete proposal from the user's feedback.
	 *
	 * @param promptSystem the directory that holds the prompt system
	 * @param profiles the harness's profile inventory, which a profile restriction selects from, and its capability
	 *   ranking
	 * @param previous the proposal that the user gave feedback on
	 * @param feedback the user's feedback
	 * @return a request whose role is {@link SessionRole#DERIVER}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code feedback} is blank
	 */
	public SessionRequest request(Path promptSystem, ProfileEvidence profiles, DerivedProposal previous,
		String feedback)
	{
		requireThat(promptSystem, "promptSystem").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		requireThat(previous, "previous").isNotNull();
		requireThat(feedback, "feedback").isNotBlank();
		return request(promptSystem, profiles, previous.derivation(), feedback);
	}

	/**
	 * Returns a deriver session request.
	 *
	 * @param promptSystem the directory that holds the prompt system
	 * @param evidence the harness's profile inventory and its capability ranking
	 * @param previous the previous proposal, or {@code none}
	 * @param feedback the user's feedback, or {@code none}
	 * @return the request
	 */
	private SessionRequest request(Path promptSystem, ProfileEvidence evidence, String previous, String feedback)
	{
		ProfileCatalog inventory = evidence.inventory();
		StringJoiner profiles = new StringJoiner("\n");
		profiles.add("Source: " + inventory.source());
		for (TrialProfile profile : inventory.profiles())
		{
			if (profile.effort().isEmpty())
				profiles.add("- model `" + profile.model() + "`, no effort dimension");
			else
				profiles.add("- model `" + profile.model() + "`, effort `" + profile.effort() + "`");
		}
		String turn = prompt.fill(Map.of("previous_proposal", previous, "feedback", feedback, "profile_inventory",
			profiles.toString(), "capability_ranking", evidence.ranking().describe(), "output_schema", outputSchema,
			"regression_scenarios", scenarios.prompt()));
		return new SessionRequest(SessionRole.DERIVER, Part.DEVELOPMENT, promptSystem, List.of(turn, outputTurn),
			List.of(), TIME_LIMIT, judge.role());
	}

	/**
	 * Validates a deriver session's output and computes its trial profiles. A capability ranking in the output, which
	 * the user's feedback supplies, replaces the shipped one; the profiles of a described certification scope in the
	 * output become the contract's described profiles. References to the session's project directory in the output's
	 * text name the user's project in both the proposal and its saved derivation.
	 *
	 * @param result the deriver session's result
	 * @param profiles the profile inventory, its shipped capability ranking and the bindings that CAT read when the
	 *   deriver ran
	 * @param salt the salt that CAT drew for the proposal, which splits each test's key-value combinations into the
	 *   development part and the held-out part
	 * @return the proposal
	 * @throws NullPointerException if any of the arguments are null
	 * @throws InvalidProposalException if the final response is not exactly one JSON object, bare or alone inside one
	 *   Markdown code fence, or the object violates the deriver's output schema, supplies a judge field or the trial
	 *   profiles that CAT owns, lists a requirement with the core-task requirement's identifier, restricts the
	 *   profiles to a set that the inventory cannot satisfy, describes a certification profile that matches no
	 *   inventory profile, ranks a family twice, omits a citation for a recorded scenario of a prompt source, or breaks
	 *   a rule of the contract model
	 */
	public DerivedProposal proposal(SessionResult.Completed result, ProfileEvidence profiles, long salt)
		throws InvalidProposalException
	{
		requireThat(result, "result").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		Optional<ObjectNode> extracted = responses.parseFinalResponse(result);
		if (extracted.isEmpty())
			throw new InvalidProposalException(List.of(NOT_ONE_OBJECT));
		ObjectNode output = extracted.get();
		rewriteProjectPaths(output, result.effects().toAbsolutePath().normalize().toString(),
			scenarios.project().toString());
		String derivation = json.writerWithDefaultPrettyPrinter().writeValueAsString(output);

		List<String> problems = new ArrayList<>();
		Findings findings = new Findings(problems);
		JsonNode scopeNode = output.remove(Findings.SCOPE_CONDITIONS);
		JsonNode conflictsNode = output.remove(Findings.CONFLICTS);
		List<Conflict> conflicts = findings.conflicts(conflictsNode);
		List<DerivedSource> derivedSources = findings.derivedSources(output.remove(Findings.DERIVED_SOURCES));
		List<ScopeCondition> scopeConditions = findings.scopeConditions(scopeNode);
		List<TrialProfile> restriction = findings.profileRestriction(
			output.remove(Findings.PROFILE_RESTRICTION));
		List<TrialProfile> described = findings.certificationProfiles(
			output.remove(Findings.CERTIFICATION_PROFILES));
		CapabilityRanking ranking = findings.capabilityRanking(output.remove(Findings.CAPABILITY_RANKING)).
			orElse(profiles.ranking());
		if (output.remove(ProposalSchema.TRIAL_PROFILES) != null)
		{
			problems.add("$." + ProposalSchema.TRIAL_PROFILES + ": CAT computes the trial profiles, so the deriver " +
				"must leave them out");
		}
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
		addCoreTask(output, problems);
		Contract contract = null;
		try
		{
			contract = schema.parse(json.writeValueAsString(output), (requirements, categories) ->
				TrialProfiles.compute(profiles.inventory(), ranking, profiles.bindingsOf(requirements),
					restriction, described, requirements, categories, Set.of(Requirement.CORE_TASK.id())), salt);
			findings.checkRequirements(scopeNode, Findings.SCOPE_CONDITIONS, contract);
			findings.checkRequirements(conflictsNode, Findings.CONFLICTS, contract);
			problems.addAll(scenarios.missingCitations(contract, derivedSources));
		}
		catch (InvalidProposalException e)
		{
			problems.addAll(e.problems());
		}
		if (!problems.isEmpty())
			throw new InvalidProposalException(problems);
		return new DerivedProposal(contract, derivedSources, scopeConditions, conflicts, result.promptSources(),
			derivation);
	}

	/**
	 * Replaces staged project references in every string value of the deriver's output. Paths to the session's
	 * artifacts outside its project directory remain unchanged.
	 *
	 * @param node the output value whose text is rewritten in place
	 * @param staged the absolute root of the session's project copy
	 * @param project the absolute root of the user's project
	 */
	private void rewriteProjectPaths(JsonNode node, String staged, String project)
	{
		if (node instanceof ObjectNode object)
		{
			for (String name : object.propertyNames())
			{
				JsonNode value = object.get(name);
				if (value.isString())
					object.put(name, value.asString().replace(staged, project));
				else
					rewriteProjectPaths(value, staged, project);
			}
		}
		else if (node instanceof ArrayNode array)
		{
			for (int i = 0; i < array.size(); ++i)
			{
				JsonNode value = array.get(i);
				if (value.isString())
					array.set(i, json.getNodeFactory().stringNode(value.asString().replace(staged, project)));
				else
					rewriteProjectPaths(value, staged, project);
			}
		}
	}

	/**
	 * Adds the core-task requirement to the deriver's requirements and to every test that does not list it already.
	 * <p>
	 * A test may already list it, because a deterministic check of a mechanical test may decide it. A shape that the
	 * schema rejects is left alone so that the schema reports it.
	 *
	 * @param output the deriver's output
	 * @param problems receives a problem if the deriver lists a requirement with the core-task requirement's identifier
	 */
	private static void addCoreTask(ObjectNode output, List<String> problems)
	{
		Requirement coreTask = Requirement.CORE_TASK;
		if (output.get("requirements") instanceof ArrayNode requirements)
		{
			for (int i = requirements.size() - 1; i >= 0; --i)
			{
				JsonNode id = requirements.get(i).get("id");
				if (id != null && id.asString().equals(coreTask.id()))
				{
					problems.add("$.requirements[" + i + "]: CAT adds the requirement '" + coreTask.id() + "', so the " +
						"deriver must leave it out");
					// Validate the rest of the output against CAT's requirement, so that each problem is reported once
					requirements.remove(i);
				}
			}
			ObjectNode node = requirements.addObject();
			node.put("id", coreTask.id());
			node.put("description", coreTask.description());
			node.put("source", coreTask.source());
			node.put("trigger", coreTask.trigger());
			ArrayNode behaviors = node.putArray("behaviors");
			for (String behavior : coreTask.behaviors())
				behaviors.add(behavior);
		}
		if (!(output.get("categories") instanceof ArrayNode categories))
			return;
		for (JsonNode category : categories)
		{
			if (!(category.get("tests") instanceof ArrayNode tests))
				continue;
			for (JsonNode test : tests)
			{
				if (!(test.get("requirement_ids") instanceof ArrayNode requirementIds))
					continue;
				boolean listed = false;
				for (JsonNode id : requirementIds)
				{
					if (id.asString().equals(coreTask.id()))
						listed = true;
				}
				if (!listed)
					requirementIds.add(coreTask.id());
			}
		}
	}
}
