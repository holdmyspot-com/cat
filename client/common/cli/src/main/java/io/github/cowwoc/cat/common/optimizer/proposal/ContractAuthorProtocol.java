package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.contract.CapabilityRanking;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.BuiltInTool;
import io.github.cowwoc.cat.common.optimizer.contract.MappedAssertion;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfiles;
import io.github.cowwoc.cat.common.optimizer.contract.ValueGenerators;
import io.github.cowwoc.cat.common.optimizer.contract.WorkspaceFixtures;
import io.github.cowwoc.cat.common.optimizer.execution.PilotResult;
import io.github.cowwoc.cat.common.optimizer.port.FollowUp;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.StringJoiner;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates requests for the session that reads a prompt system and proposes its requirements and tests (the
 * contract author), and validates that session's output.
 * <p>
 * The request names the campaign's target and the user's goal, and supplies only the recorded regression scenarios of
 * the target's guidance; the proposal must cite every one of them.
 * <p>
 * The contract author's output follows the proposal schema without the judge fields, the trial profiles and the
 * committed test cases that CAT owns, plus the contract author's additional findings: the prompt files whose loading no
 * harness event reports, the user's profile restriction, the conditions that make certification unavailable,
 * and the groups of requirements that no reply can satisfy together.
 * CAT adds its judge fields and computes the trial profiles before validating the proposal. It rejects output that
 * supplies a field that CAT owns, declares an assertion whose built-in tool needs exit statuses that the harness does
 * not report, or omits a citation for a recorded regression scenario of the target. It reports every problem it finds
 * together, including missing citations when the output also violates the schema.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class ContractAuthorProtocol
{
	private static final Duration TIME_LIMIT = Duration.ofMinutes(30);
	private static final String PROMPT_RESOURCE = "/optimizer/proposal/contract-author.md";
	/**
	 * The final turn, which asks the contract author to write the proposal to its output file.
	 */
	private static final String OUTPUT_RESOURCE = "/optimizer/proposal/contract-author-output.md";
	/**
	 * The turn that returns the problems of a refused proposal to the contract author, so that it can correct the parts
	 * of its output file that they concern with its inspection still in context.
	 */
	private static final String CORRECTION_RESOURCE = "/optimizer/proposal/contract-author-correction.md";
	private static final String FINDINGS_RESOURCE = "/optimizer/proposal/contract-author-findings.schema.json";
	/**
	 * The rules for writing a prompt test, which every session that writes one receives.
	 */
	private static final String PROMPT_TESTS_RESOURCE = "/optimizer/proposal/prompt-tests.md";
	private static final String PROMPT_TESTS = "{{prompt_tests}}\n";
	/**
	 * The value of the revision placeholders when there is nothing to revise.
	 */
	private static final String NONE = "none";
	/**
	 * Describes a stored proposal in a problem.
	 */
	private static final String STORED = "the stored proposal";
	private final JsonMapper json;
	private final ProposalSchema schema;
	private final JudgeTemplate judge;
	private final OutputFile outputFile;
	private final PromptTemplate prompt;
	private final String outputSchema;
	private final PromptTemplate outputTurn;
	private final PromptTemplate correction;
	private final RegressionScenarios scenarios;
	private final Path target;
	private final String goal;
	private final boolean reportsExitStatus;
	/**
	 * The files that load in production, each with the rule that loads it, as a Markdown list.
	 */
	private final String loadedFiles;
	/**
	 * The trial plugins that the project builds, with the project paths that trials write over each, as a Markdown
	 * list.
	 */
	private final String trialPlugins;

	/**
	 * Creates the contract author request builder and response validator.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema the proposal schema
	 * @param judge the judge fields that CAT adds to every proposal
	 * @param scenarios the available recorded scenarios, of which CAT supplies and checks those of the target
	 * @param target the absolute path of the campaign's target, a prompt file or a directory of prompt files
	 * @param goal what the user wants the target to achieve, in the user's own words, or {@code null} if the user
	 *   stated no goal
	 * @param reportsExitStatus {@code true} if the harness reports the exit status of commands, so that a built-in
	 *   check that needs it can be decided
	 * @param loadedFiles the files that load in production, each with the rule that loads it
	 * @param trialPlugins the trial plugins that every trial of the campaign installs, in configuration order
	 * @throws NullPointerException if any of the arguments other than {@code goal} are null
	 * @throws IllegalArgumentException if {@code target} is relative or {@code goal} is blank
	 * @throws UncheckedIOException if a packaged prompt or schema cannot be read
	 */
	public ContractAuthorProtocol(JsonMapper json, ProposalSchema schema, JudgeTemplate judge,
		RegressionScenarios scenarios, Path target, String goal, boolean reportsExitStatus,
		List<DerivedSource> loadedFiles, List<TrialPlugin> trialPlugins)
	{
		requireThat(json, "json").isNotNull();
		requireThat(schema, "schema").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(scenarios, "scenarios").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(target.isAbsolute(), "target.isAbsolute()").isTrue();
		if (goal != null)
			requireThat(goal, "goal").isNotBlank();
		requireThat(loadedFiles, "loadedFiles").isNotNull();
		requireThat(trialPlugins, "trialPlugins").isNotNull();
		this.json = json;
		this.schema = schema;
		this.judge = judge;
		this.scenarios = scenarios.of(target);
		this.target = target.normalize();
		this.goal = goal;
		this.reportsExitStatus = reportsExitStatus;
		this.loadedFiles = loadedFiles(loadedFiles);
		this.trialPlugins = trialPlugins(trialPlugins);
		this.outputFile = new OutputFile(json);
		this.prompt = withPromptTests(PROMPT_RESOURCE);
		this.outputSchema = outputSchema(json, schema);
		this.outputTurn = PromptTemplate.load(OUTPUT_RESOURCE);
		this.correction = PromptTemplate.load(CORRECTION_RESOURCE);
	}

	/**
	 * Loads a packaged prompt that receives the rules for writing a prompt test.
	 *
	 * @param resource the prompt, whose line {@code {{prompt_tests}}} the rules replace
	 * @return the prompt with the rules in place
	 * @throws UncheckedIOException if the prompt or the rules cannot be read
	 */
	static PromptTemplate withPromptTests(String resource)
	{
		String text = PromptTemplate.readResource(resource);
		requireThat(text, "text").contains(PROMPT_TESTS);
		return new PromptTemplate(text.replace(PROMPT_TESTS, PromptTemplate.readResource(PROMPT_TESTS_RESOURCE)));
	}

	/**
	 * Returns the schema of the contract author's output: the proposal schema without the judge fields, the trial
	 * profiles and the committed test cases that CAT owns, plus the contract author's findings.
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
	 * @param salt the salt that CAT drew for the proposal, which the session validates the proposal with
	 * @return a request whose role is {@link SessionRole#CONTRACT_AUTHOR}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public SessionRequest request(Path promptSystem, ProfileEvidence profiles, long salt)
	{
		requireThat(promptSystem, "promptSystem").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		return request(promptSystem, profiles, NONE, NONE, salt);
	}

	/**
	 * Returns the request for regenerating the complete proposal from the user's feedback.
	 *
	 * @param promptSystem the directory that holds the prompt system
	 * @param profiles the harness's profile inventory, which a profile restriction selects from, and its capability
	 *   ranking
	 * @param previous the proposal that the user gave feedback on
	 * @param feedback the user's feedback
	 * @param pilots the latest guard pilot of each prompt test of {@code previous} that has one, by prompt test; the
	 *   request shows their results after the previous proposal
	 * @return a request whose proposal keeps the salt of {@code previous}, so the test cases that the user reviewed keep
	 *   their parts, and whose role is whose role is {@link SessionRole#CONTRACT_AUTHOR}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code feedback} is blank
	 */
	public SessionRequest request(Path promptSystem, ProfileEvidence profiles, DerivedProposal previous,
		String feedback, Map<String, PilotResult> pilots)
	{
		requireThat(promptSystem, "promptSystem").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		requireThat(previous, "previous").isNotNull();
		requireThat(feedback, "feedback").isNotBlank();
		requireThat(pilots, "pilots").isNotNull();
		return request(promptSystem, profiles, previous.derivation() + "\n\nGuard pilot results of the previous " +
			"proposal:\n\n" + PilotReport.render(previous.contract(), pilots), feedback, previous.contract().salt());
	}

	/**
	 * Returns a contract author session request.
	 *
	 * @param promptSystem the directory that holds the prompt system
	 * @param profiles the harness's profile inventory and its capability ranking
	 * @param previous the previous proposal, or {@code none}
	 * @param feedback the user's feedback, or {@code none}
	 * @param salt the salt that CAT drew for the proposal
	 * @return the request, which returns the problems of a refused proposal to the session once
	 */
	private SessionRequest request(Path promptSystem, ProfileEvidence profiles, String previous, String feedback,
		long salt)
	{
		ProfileCatalog inventory = profiles.inventory();
		StringJoiner lines = new StringJoiner("\n");
		lines.add("Source: " + inventory.source());
		for (TrialProfile profile : inventory.profiles())
		{
			if (profile.effort().isEmpty())
				lines.add("- model `" + profile.model() + "`, no effort dimension");
			else
				lines.add("- model `" + profile.model() + "`, effort `" + profile.effort() + "`");
		}
		String turn = prompt.fill(Map.ofEntries(Map.entry("previous_proposal", previous),
			Map.entry("feedback", feedback), Map.entry("profile_inventory", lines.toString()),
			Map.entry("capability_ranking", profiles.ranking().describe()), Map.entry("output_schema", outputSchema),
			Map.entry("regression_scenarios", scenarios.prompt()), Map.entry("generators", ValueGenerators.describeAll()),
			Map.entry("target_prompt_file", targetPromptFile()), Map.entry("goal", goal()),
			Map.entry("loaded_files", loadedFiles), Map.entry("trial_plugins", trialPlugins)));
		String file = OutputFile.name(promptSystem);
		FollowUp correct = result -> correction(result, profiles, salt, file);
		return new SessionRequest(SessionRole.CONTRACT_AUTHOR, Part.DEVELOPMENT, promptSystem, List.of(turn,
			outputTurn.fill(Map.of("output_file", file))), List.of(), TIME_LIMIT, role(), WorkspaceFixtures.NONE,
			List.of(), Optional.of(correct));
	}

	/**
	 * Returns the files that load in production as a Markdown list.
	 *
	 * @param files the files, each with the rule that loads it
	 * @return a list item for each file, or {@code none} if there are none
	 */
	private static String loadedFiles(List<DerivedSource> files)
	{
		if (files.isEmpty())
			return "none";
		StringJoiner list = new StringJoiner("\n");
		for (DerivedSource file : files)
			list.add("- `" + file.path() + "`: " + file.rule());
		return list.toString();
	}

	/**
	 * Returns the trial plugins that the project builds as a Markdown list.
	 *
	 * @param plugins the trial plugins
	 * @return a list item for each project-built plugin with the project paths that trials write over it, or
	 *   {@code none} if there are none
	 */
	private static String trialPlugins(List<TrialPlugin> plugins)
	{
		StringJoiner list = new StringJoiner("\n");
		for (TrialPlugin plugin : plugins)
		{
			if (!plugin.isProjectBuilt())
				continue;
			StringJoiner mappings = new StringJoiner(", ");
			for (TrialPlugin.Mapping mapping : plugin.mappings())
			{
				mappings.add("`" + PortablePaths.text(mapping.source()) + "` → `" +
					PortablePaths.text(mapping.target()) + "`");
			}
			if (mappings.length() == 0)
				mappings.add("none");
			list.add("- `" + plugin.name() + "`, with these project paths written over it: " + mappings);
		}
		if (list.length() == 0)
			return "none";
		return list.toString();
	}

	/**
	 * Returns the campaign's target prompt file as a contract author session names it.
	 *
	 * @return the target prompt file relative to the project, in backticks
	 */
	String targetPromptFile()
	{
		return "`" + PortablePaths.text(scenarios.project().relativize(target)) + "`";
	}

	/**
	 * Returns the user's goal for the target as a contract author session receives it.
	 *
	 * @return the goal in the user's own words, or {@code none} if the user stated no goal
	 */
	String goal()
	{
		if (goal == null)
			return NONE;
		return goal;
	}

	/**
	 * Returns the profile that a contract author session runs with.
	 *
	 * @return the harness's role profile
	 */
	TrialProfile role()
	{
		return judge.role();
	}

	/**
	 * Returns the turn that asks the contract author to correct its proposal.
	 *
	 * @param result the session's result after it sent the proposal
	 * @param profiles the profile inventory and its capability ranking
	 * @param salt the salt that CAT drew for the proposal
	 * @param file the name of the session's output file
	 * @return the turn that lists the proposal's problems, or empty if the proposal is valid
	 */
	private Optional<String> correction(SessionResult.Completed result, ProfileEvidence profiles, long salt,
		String file)
	{
		try
		{
			proposal(result, profiles, salt);
			return Optional.empty();
		}
		catch (InvalidProposalException e)
		{
			StringJoiner problems = new StringJoiner("\n");
			for (String problem : e.problems())
				problems.add("- " + problem);
			return Optional.of(correction.fill(Map.of("problems", problems.toString(), "output_file", file)));
		}
	}

	/**
	 * Validates a contract author session's output and computes its trial profiles. A capability ranking in the output,
	 * which the user's feedback supplies, replaces the shipped one; the profiles of a described certification scope in
	 * the output become the contract's described profiles. References to the session's project directory in the
	 * output's text name the user's project in both the proposal and its saved derivation.
	 *
	 * @param result the contract author session's result
	 * @param profiles the profile inventory, its shipped capability ranking and the bindings that CAT read when the
	 *   contract author ran
	 * @param salt the salt that CAT drew for the proposal, which splits each test's outcome-changing combinations into
	 *   the development part and the held-out part, and from which the seeds of the generator calls derive, so that
	 *   reading the same output again gives the same contract
	 * @return the proposal, without derived sources, which the caller lists
	 * @throws NullPointerException if any of the arguments are null
	 * @throws InvalidProposalException if the session wrote no output file or the file is not exactly one JSON object,
	 *   or the object violates the contract author's output schema, supplies a judge field, the trial profiles or the
	 *   derived sources that CAT owns, declares an assertion whose built-in tool needs exit statuses that the harness
	 *   does not report, restricts the profiles to a set that the inventory cannot satisfy, describes a certification
	 *   profile that matches no inventory profile, ranks a family twice, omits a citation for a recorded scenario of
	 *   the target, or breaks a rule of the contract model. The exception lists every problem found.
	 */
	public DerivedProposal proposal(SessionResult.Completed result, ProfileEvidence profiles, long salt)
		throws InvalidProposalException
	{
		requireThat(result, "result").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		ObjectNode output = outputFile.read(result);
		rewriteProjectPaths(output, result.effects());
		return proposal(output, result.promptSources(), profiles, salt);
	}

	/**
	 * Validates a stored contract author output whose text already names the user's project, and computes its trial
	 * profiles.
	 *
	 * @param output the stored output
	 * @param observedSources the prompt sources that the harness reported loading in the contract author session
	 * @param profiles the profile inventory, its shipped capability ranking and the bindings that CAT read when the
	 *   contract author ran
	 * @param salt the salt that CAT drew for the proposal
	 * @return the proposal, without derived sources, which the caller restores from the stored proposal
	 * @throws NullPointerException if any of the arguments are null
	 * @throws InvalidProposalException if {@code output} is not exactly one JSON object or breaks a rule that
	 *   {@link #proposal(SessionResult.Completed, ProfileEvidence, long)} lists
	 */
	public DerivedProposal proposal(String output, String observedSources, ProfileEvidence profiles, long salt)
		throws InvalidProposalException
	{
		requireThat(output, "output").isNotNull();
		requireThat(observedSources, "observedSources").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		return proposal(outputFile.parse(output, STORED), observedSources, profiles, salt);
	}

	/**
	 * Validates a contract author's output whose text already names the user's project, and computes its trial
	 * profiles.
	 *
	 * @param output the output, which this method changes
	 * @param observedSources the prompt sources that the harness reported loading in the contract author session
	 * @param profiles the profile inventory, its shipped capability ranking and the bindings that CAT read when the
	 *   contract author ran
	 * @param salt the salt that CAT drew for the proposal
	 * @return the proposal, without derived sources
	 * @throws InvalidProposalException if the output breaks a rule that
	 *   {@link #proposal(SessionResult.Completed, ProfileEvidence, long)} lists
	 */
	DerivedProposal proposal(ObjectNode output, String observedSources, ProfileEvidence profiles, long salt)
		throws InvalidProposalException
	{
		String derivation = json.writerWithDefaultPrettyPrinter().writeValueAsString(output);

		List<String> problems = new ArrayList<>();
		Findings findings = new Findings(problems);
		JsonNode scopeNode = output.remove(Findings.SCOPE_CONDITIONS);
		JsonNode conflictsNode = output.remove(Findings.CONFLICTS);
		List<Conflict> conflicts = findings.conflicts(conflictsNode);
		List<ScopeCondition> scopeConditions = findings.scopeConditions(scopeNode);
		List<TrialProfile> restriction = findings.profileRestriction(
			output.remove(Findings.PROFILE_RESTRICTION));
		List<TrialProfile> described = findings.certificationProfiles(
			output.remove(Findings.CERTIFICATION_PROFILES));
		CapabilityRanking ranking = findings.capabilityRanking(output.remove(Findings.CAPABILITY_RANKING)).
			orElse(profiles.ranking());
		if (output.remove(ProposalSchema.TRIAL_PROFILES) != null)
		{
			problems.add("$." + ProposalSchema.TRIAL_PROFILES +
				": CAT computes the trial profiles, so the contract author " + "must leave them out");
		}
		if (output.remove(Findings.DERIVED_SOURCES) != null)
		{
			problems.add("$." + Findings.DERIVED_SOURCES +
				": CAT computes the derived sources, so the contract author must leave them out");
		}
		if (output.get("judge") instanceof ObjectNode judgeNode)
		{
			for (String field : JudgeTemplate.OWNED_FIELDS)
			{
				if (judgeNode.has(field))
					problems.add("$.judge." + field +
					": CAT supplies this field, so the contract author must leave it out");
			}
			judgeNode.remove(JudgeTemplate.OWNED_FIELDS);
			judge.complete(judgeNode);
		}
		Contract contract = null;
		try
		{
			contract = schema.parse(json.writeValueAsString(output), (requirements, tests) ->
				TrialProfiles.compute(profiles.inventory(), ranking, profiles.bindingsOf(requirements),
					restriction, described, requirements, tests), salt, new SplittableRandom(salt),
				byCitingTest(output, scenarios::committedTestCases),
				byCitingTest(output, scenarios::recordedFixtures));
			problems.addAll(unreportedExitStatuses(contract));
			findings.checkRequirements(scopeNode, Findings.SCOPE_CONDITIONS, contract);
			findings.checkRequirements(conflictsNode, Findings.CONFLICTS, contract);
		}
		catch (InvalidProposalException e)
		{
			problems.addAll(e.problems());
		}
		// Read from the raw output so that missing citations are reported even when the schema rejects the output
		problems.addAll(scenarios.missingCitations(citations(output)));
		if (!problems.isEmpty())
			throw new InvalidProposalException(problems);
		return new DerivedProposal(contract, List.of(), scopeConditions, conflicts, observedSources, derivation,
			citedScenarios(contract));
	}

	/**
	 * Returns the regression scenarios that the prompt tests of the contract author's output cite, ignoring malformed
	 * entries that the schema validation rejects.
	 *
	 * @param output the contract author's output
	 * @return the citations
	 */
	private static Set<String> citations(ObjectNode output)
	{
		Set<String> cited = new HashSet<>();
		JsonNode tests = output.get("tests");
		if (tests == null || !tests.isArray())
			return cited;
		for (JsonNode test : tests)
		{
			JsonNode citations = test.get("regression_scenarios");
			if (citations == null || !citations.isArray())
				continue;
			for (JsonNode citation : citations)
			{
				if (citation.isString())
					cited.add(citation.asString());
			}
		}
		return cited;
	}

	/**
	 * Returns what CAT adds to each prompt test of the contract author's output from the recorded scenarios that the
	 * test cites. The schema validation that follows rejects a malformed test.
	 *
	 * @param <T> the type of item that CAT adds
	 * @param output the contract author's output
	 * @param ofScenarios returns the items of the scenarios that one test cites, from their citations
	 * @return the items, by prompt test identifier; a test that receives none is left out
	 */
	private static <T> Map<String, List<T>> byCitingTest(ObjectNode output,
		Function<List<String>, List<T>> ofScenarios)
	{
		Map<String, List<T>> byTest = new HashMap<>();
		JsonNode tests = output.get("tests");
		if (tests == null || !tests.isArray())
			return byTest;
		for (JsonNode test : tests)
		{
			JsonNode id = test.get("id");
			JsonNode citations = test.get("regression_scenarios");
			if (id == null || !id.isString() || citations == null || !citations.isArray())
				continue;
			List<String> cited = new ArrayList<>();
			for (JsonNode citation : citations)
			{
				if (citation.isString())
					cited.add(citation.asString());
			}
			List<T> items = ofScenarios.apply(cited);
			if (!items.isEmpty())
				byTest.put(id.asString(), items);
		}
		return byTest;
	}

	/**
	 * Returns the recorded regression scenarios that a contract's prompt tests cite.
	 *
	 * @param contract the contract
	 * @return the content of each cited scenario, by citation, in contract order
	 */
	private Map<String, String> citedScenarios(Contract contract)
	{
		Map<String, String> byCitation = new HashMap<>();
		for (RegressionScenarios.Scenario scenario : scenarios.scenarios())
			byCitation.put(scenario.citation(), scenario.content());
		Map<String, String> cited = new LinkedHashMap<>();
		for (PromptTest test : contract.tests())
		{
			for (String citation : test.regressionScenarios())
			{
				String content = byCitation.get(citation);
				if (content != null)
					cited.put(citation, content);
			}
		}
		return cited;
	}

	/**
	 * Replaces staged project references in every string value of a contract author session's output with the user's
	 * project. Paths to the session's artifacts outside its project directory remain unchanged.
	 *
	 * @param output the output value whose text is rewritten in place
	 * @param effects the session's project copy
	 */
	void rewriteProjectPaths(JsonNode output, Path effects)
	{
		rewriteProjectPaths(output, effects.toAbsolutePath().normalize().toString(), scenarios.project().toString());
	}

	/**
	 * Replaces staged project references in every string value of the contract author's output. Paths to the session's
	 * artifacts outside its project directory remain unchanged.
	 *
	 * @param output the output value whose text is rewritten in place
	 * @param effects the absolute root of the session's project copy
	 * @param project the absolute root of the user's project
	 */
	private void rewriteProjectPaths(JsonNode output, String effects, String project)
	{
		if (output instanceof ObjectNode object)
		{
			for (String name : object.propertyNames())
			{
				JsonNode value = object.get(name);
				if (value.isString())
					object.put(name, value.asString().replace(effects, project));
				else
					rewriteProjectPaths(value, effects, project);
			}
		}
		else if (output instanceof ArrayNode array)
		{
			for (int i = 0; i < array.size(); ++i)
			{
				JsonNode value = array.get(i);
				if (value.isString())
					array.set(i, json.getNodeFactory().stringNode(value.asString().replace(effects, project)));
				else
					rewriteProjectPaths(value, effects, project);
			}
		}
	}

	/**
	 * Returns a problem for each assertion whose built-in tool needs exit statuses when the harness does not report
	 * them.
	 *
	 * @param contract the proposed contract
	 * @return the problems, empty if the harness reports exit statuses or no check needs them
	 */
	private List<String> unreportedExitStatuses(Contract contract)
	{
		List<String> problems = new ArrayList<>();
		if (reportsExitStatus)
			return problems;
		for (PromptTest test : contract.tests())
		{
			for (MappedAssertion mapped : test.assertions())
			{
				Assertion assertion = mapped.assertion();
				if (assertion.builtInTool().map(BuiltInTool::needsExitStatus).orElse(false))
				{
					problems.add("test '" + test.id() + "', assertion '" + assertion.id() + "': this harness does " +
						"not report the exit status of commands, so `tool: " + assertion.tool() + "` cannot be decided. Use " +
						"another built-in tool or let the judge decide the assertion.");
				}
			}
		}
		return problems;
	}
}
