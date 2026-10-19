package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.ProfileCatalog;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestLocations;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.ValueGenerators;
import com.holdmyspot.cat.common.optimizer.execution.PilotResult;
import com.holdmyspot.cat.common.optimizer.port.FollowUp;
import com.holdmyspot.cat.common.optimizer.port.HookRegistration;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.proposal.ContractAuthorFiles.DraftSource;
import com.holdmyspot.cat.common.optimizer.proposal.ProposalCheck.RecordedTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.StringJoiner;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates requests for the session that reads a prompt system and proposes its requirements and tests (the
 * contract author), and validates what that session leaves.
 * <p>
 * The session's project receives a draft directory, which holds a copy of every recorded prompt test of the target, and
 * a context file for the contract tools. The request names the campaign's target, the user's goal, the files that load
 * in production, the trial plugins, the hook registrations, the draft directory, the proposal file and the launcher
 * that provides the contract tools. The contract author writes each prompt test as files in the draft directory and
 * the rest of the proposal to the proposal file, which follows the proposal file schema without the judge fields that
 * CAT owns, plus the contract author's additional findings.
 * <p>
 * When the session ends, CAT validates the proposal file with the draft directory through {@link ProposalCheck},
 * returns every problem to the session once, and then gives each generated value of a new test case its seed.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class ContractAuthorProtocol
{
	private static final Duration TIME_LIMIT = Duration.ofMinutes(30);
	private static final String PROMPT_RESOURCE = "/optimizer/proposal/contract-author.md";
	/**
	 * The turn that returns the problems of a refused proposal to the contract author, so that it can correct the files
	 * that they concern with its inspection still in context.
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
	private final JsonMapper json;
	private final JudgeTemplate judge;
	private final OutputFile outputFile;
	private final PromptTemplate prompt;
	private final String outputSchema;
	private final PromptTemplate correction;
	private final RegressionScenarios scenarios;
	private final Path target;
	private final String goal;
	private final boolean reportsExitStatus;
	private final List<RecordedTest> recorded;
	private final ProposalCheck check;
	/**
	 * The absolute path of the launcher that provides the contract tools.
	 */
	private final Path launcher;
	/**
	 * Returns the number of bytes of each tool output that a trial of a profile receives whole, or empty if the
	 * harness's limit is not known.
	 */
	private final Function<TrialProfile, OptionalLong> toolOutputBytes;
	/**
	 * The trial plugins, with the project paths that trials write over each one that the project builds, as a
	 * Markdown list.
	 */
	private final String trialPlugins;

	/**
	 * Creates the contract author request builder and response validator.
	 *
	 * @param json              the operation-owned JSON mapper
	 * @param schema            the proposal schema
	 * @param judge             the judge fields that CAT adds to every proposal
	 * @param scenarios         the available recorded scenarios, of which CAT supplies and checks those of the target
	 * @param target            the absolute path of the campaign's target, a prompt file or a directory of prompt
	 *                          files
	 * @param goal              what the user wants the target to achieve, in the user's own words, or {@code null} if
	 *                          the user stated no goal
	 * @param reportsExitStatus {@code true} if the harness reports the exit status of commands, so that a built-in
	 *                          check that needs it can be decided
	 * @param trialPlugins      the trial plugins that every trial of the campaign installs, in configuration order
	 * @param launcher          the absolute path of the launcher that provides the contract tools
	 * @param toolOutputBytes   returns the number of bytes of each tool output that a trial of a profile receives
	 *                          whole, or empty if the harness's limit is not known
	 * @throws NullPointerException     if any of the arguments other than {@code goal} are null
	 * @throws IllegalArgumentException if {@code goal} is blank, {@code target} lies outside the project, or
	 *                                  {@code target} or {@code launcher} are relative
	 * @throws UncheckedIOException     if a packaged prompt or schema cannot be read
	 */
	public ContractAuthorProtocol(JsonMapper json, ProposalSchema schema, JudgeTemplate judge,
		RegressionScenarios scenarios, Path target, String goal, boolean reportsExitStatus,
		List<TrialPlugin> trialPlugins, Path launcher,
		Function<TrialProfile, OptionalLong> toolOutputBytes)
	{
		requireThat(json, "json").isNotNull();
		requireThat(schema, "schema").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(scenarios, "scenarios").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(target.isAbsolute(), "target.isAbsolute()").isTrue();
		if (goal != null)
			requireThat(goal, "goal").isNotBlank();
		requireThat(trialPlugins, "trialPlugins").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
		requireThat(launcher.isAbsolute(), "launcher.isAbsolute()").isTrue();
		requireThat(toolOutputBytes, "toolOutputBytes").isNotNull();
		this.json = json;
		this.judge = judge;
		this.scenarios = scenarios.of(target);
		this.target = target.normalize();
		this.goal = goal;
		this.reportsExitStatus = reportsExitStatus;
		this.recorded = ProposalCheck.recorded(this.scenarios);
		this.check = new ProposalCheck(json, schema, judge, relativeTarget(), reportsExitStatus, recorded);
		this.launcher = launcher;
		this.toolOutputBytes = toolOutputBytes;
		this.trialPlugins = trialPlugins(trialPlugins);
		this.outputFile = new OutputFile(json);
		this.prompt = withPromptTests(PROMPT_RESOURCE);
		this.outputSchema = outputSchema(json, schema);
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
	 * Returns the schema of the proposal file that a contract author writes: the proposal file schema without the
	 * judge fields that CAT owns, plus the contract author's findings.
	 *
	 * @param json   the operation-owned JSON mapper
	 * @param schema the proposal schema
	 * @return the schema text
	 */
	private static String outputSchema(JsonMapper json, ProposalSchema schema)
	{
		ObjectNode root = schema.proposalFileSchema();
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
	 * Returns the request for deriving the first proposal. The session's draft directory starts with a copy of every
	 * recorded prompt test of the target.
	 *
	 * @param promptSystem the directory that holds the prompt system, which the session works in so that the harness
	 *                     loads it as it would in production
	 * @param profiles     the harness's profile inventory, which a profile restriction selects from, and its
	 *                     capability ranking
	 * @param hooks        every hook registration of the prompt system and of the trial plugins
	 * @param loadedFiles  the files that load in trials, each with the rule that loads it
	 * @param salt         the salt that CAT drew for the proposal, which the session validates the proposal with
	 * @return a request whose role is {@link SessionRole#CONTRACT_AUTHOR}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public SessionRequest request(Path promptSystem, ProfileEvidence profiles, List<HookRegistration> hooks,
		List<DerivedSource> loadedFiles, long salt)
	{
		requireThat(promptSystem, "promptSystem").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		requireThat(hooks, "hooks").isNotNull();
		requireThat(loadedFiles, "loadedFiles").isNotNull();
		return request(promptSystem, profiles, hooks, loadedFiles, NONE, NONE,
			draft -> DraftDirectory.seed(scenarios, draft), salt);
	}

	/**
	 * Returns the request for regenerating the complete proposal from the user's feedback. The session's draft
	 * directory starts as a copy of the previous proposal's.
	 *
	 * @param promptSystem the directory that holds the prompt system
	 * @param profiles     the harness's profile inventory, which a profile restriction selects from, and its
	 *                     capability ranking
	 * @param hooks        every hook registration of the prompt system and of the trial plugins
	 * @param loadedFiles  the files that load in trials, each with the rule that loads it
	 * @param previous     the proposal that the user gave feedback on
	 * @param feedback     the user's feedback
	 * @param pilots       the latest guard pilot of each prompt test of {@code previous} that has one, by prompt test;
	 *                     the request shows their results after the previous proposal
	 * @return a request whose proposal keeps the salt of {@code previous}, so the test cases that the user reviewed
	 *   keep their parts, and whose role is {@link SessionRole#CONTRACT_AUTHOR}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code feedback} is blank or {@code previous} has no draft directory
	 */
	public SessionRequest request(Path promptSystem, ProfileEvidence profiles, List<HookRegistration> hooks,
		List<DerivedSource> loadedFiles, DerivedProposal previous, String feedback, Map<String, PilotResult> pilots)
	{
		requireThat(promptSystem, "promptSystem").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		requireThat(hooks, "hooks").isNotNull();
		requireThat(loadedFiles, "loadedFiles").isNotNull();
		requireThat(previous, "previous").isNotNull();
		requireThat(feedback, "feedback").isNotBlank();
		requireThat(pilots, "pilots").isNotNull();
		Path previousDraft = previous.draft();
		requireThat(previousDraft, "previous.draft()").isNotNull();
		return request(promptSystem, profiles, hooks, loadedFiles, previous.derivation() + "\n\nGuard pilot " +
				"results of the previous proposal:\n\n" + PilotReport.render(previous.contract(), pilots), feedback,
			draft -> DraftDirectory.copy(previousDraft, draft), previous.contract().salt());
	}

	/**
	 * Returns a contract author session request.
	 *
	 * @param promptSystem the directory that holds the prompt system
	 * @param profiles     the harness's profile inventory and its capability ranking
	 * @param hooks        every hook registration of the prompt system and of the trial plugins
	 * @param loadedFiles  the files that load in trials, each with the rule that loads it
	 * @param previous     the previous proposal, or {@code none}
	 * @param feedback     the user's feedback, or {@code none}
	 * @param source       fills the session's draft directory
	 * @param salt         the salt that CAT drew for the proposal
	 * @return the request, which returns the problems of a refused proposal to the session once
	 */
	private SessionRequest request(Path promptSystem, ProfileEvidence profiles, List<HookRegistration> hooks,
		List<DerivedSource> loadedFiles, String previous, String feedback, DraftSource source, long salt)
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
		ContractAuthorFiles files = files(promptSystem, profiles, source, Optional.empty());
		String turn = prompt.fill(Map.ofEntries(Map.entry("previous_proposal", previous),
			Map.entry("feedback", feedback), Map.entry("profile_inventory", lines.toString()),
			Map.entry("capability_ranking", profiles.ranking().describe()), Map.entry("output_schema", outputSchema),
			Map.entry("generators", ValueGenerators.describeAll()),
			Map.entry("target_prompt_file", targetPromptFile()), Map.entry("goal", goal()),
			Map.entry("loaded_files", loadedFiles(loadedFiles)), Map.entry("trial_plugins", trialPlugins),
			Map.entry("hook_registrations", hookRegistrations(hooks)),
			Map.entry("recorded_tests", recordedTests()),
			Map.entry("test_directory", PortablePaths.text(PromptTestLocations.scenarioDirectory(relativeTarget()))),
			Map.entry("proposal_file", files.context().proposalFile()),
			Map.entry("draft_directory", files.draft()), Map.entry("launcher", launcher()),
			Map.entry("context_file", files.contextFile())));
		FollowUp correct = result -> correction(result, promptSystem, profiles, salt);
		return new SessionRequest(SessionRole.CONTRACT_AUTHOR, Part.DEVELOPMENT, promptSystem, List.of(turn),
			List.of(), TIME_LIMIT, role(), files, List.of(), Optional.of(correct));
	}

	/**
	 * Returns the files that a contract author session's project receives.
	 *
	 * @param promptSystem the directory that holds the prompt system
	 * @param profiles     the harness's profile inventory, its capability ranking and the bindings that CAT read
	 * @param source       fills the session's draft directory
	 * @param revision     the confirmed contract that a test revision session examines a prompt test of, or empty
	 *                     for a session that writes a proposal
	 * @return the draft directory and the context file of the contract tools
	 */
	ContractAuthorFiles files(Path promptSystem, ProfileEvidence profiles, DraftSource source,
		Optional<ContractContext.Revision> revision)
	{
		String draft = DraftDirectory.name(promptSystem);
		Map<TrialProfile, Long> limits = new HashMap<>();
		for (TrialProfile profile : profiles.inventory().profiles())
		{
			OptionalLong bytes = toolOutputBytes.apply(profile);
			if (bytes.isPresent())
				limits.put(profile, bytes.getAsLong());
		}
		ContractContext context = new ContractContext(OutputFile.name(promptSystem), draft, relativeTarget(),
			reportsExitStatus, role(), profiles, limits, recorded, revision);
		return new ContractAuthorFiles(json, draft, source, ContractContext.name(promptSystem), context);
	}

	/**
	 * Returns the launcher that provides the contract tools, as a session's request names it.
	 *
	 * @return the launcher's absolute path
	 */
	String launcher()
	{
		return launcher.toString();
	}

	/**
	 * Returns the files that load in trials as a Markdown list.
	 *
	 * @param files the files, each with the rule that loads it
	 * @return a list item for each file, or {@code none} if there are none
	 */
	private static String loadedFiles(List<DerivedSource> files)
	{
		if (files.isEmpty())
			return NONE;
		StringJoiner list = new StringJoiner("\n");
		for (DerivedSource file : files)
			list.add("- `" + file.path() + "`: " + file.rule());
		return list.toString();
	}

	/**
	 * Returns the trial plugins as a Markdown list.
	 *
	 * @param plugins the trial plugins
	 * @return a list item for each plugin, with the project paths that trials write over each one that the project
	 *   builds, or {@code none} if there are none
	 */
	private static String trialPlugins(List<TrialPlugin> plugins)
	{
		StringJoiner list = new StringJoiner("\n");
		for (TrialPlugin plugin : plugins)
		{
			if (!plugin.isProjectBuilt())
			{
				list.add("- `" + plugin.name() + "`, installed by the user");
				continue;
			}
			StringJoiner mappings = new StringJoiner(", ");
			for (TrialPlugin.Mapping mapping : plugin.mappings())
			{
				String target = PortablePaths.text(mapping.target());
				if (target.isEmpty())
					target = ".";
				mappings.add("`" + PortablePaths.text(mapping.source()) + "` → `" + target + "`");
			}
			if (mappings.length() == 0)
				mappings.add(NONE);
			list.add("- `" + plugin.name() + "`, built by the project, with these project paths written over it: " +
				mappings);
		}
		if (list.length() == 0)
			return NONE;
		return list.toString();
	}

	/**
	 * Returns the hook registrations as a Markdown list.
	 *
	 * @param hooks the hook registrations
	 * @return a list item for each registration, or {@code none} if there are none
	 */
	private static String hookRegistrations(List<HookRegistration> hooks)
	{
		if (hooks.isEmpty())
			return NONE;
		StringJoiner list = new StringJoiner("\n");
		for (HookRegistration hook : hooks)
		{
			StringBuilder item = new StringBuilder(128).append("- event `").append(hook.event()).append('`');
			if (!hook.matcher().isEmpty())
				item.append(", matcher `").append(hook.matcher()).append('`');
			item.append(", command `").append(hook.command()).append("`, registered by `").append(hook.file()).
				append('`');
			if (hook.plugin().isEmpty())
				item.append(" of the project");
			else
				item.append(" of the trial plugin `").append(hook.plugin()).append('`');
			list.add(item);
		}
		return list.toString();
	}

	/**
	 * Returns the recorded prompt tests of the target as a Markdown list.
	 *
	 * @return a list item for each recorded prompt test with its directory in the draft directory and its citation,
	 *   or {@code none} if there are none
	 */
	private String recordedTests()
	{
		if (recorded.isEmpty())
			return NONE;
		Map<String, RegressionScenarios.Scenario> byCitation = new HashMap<>();
		for (RegressionScenarios.Scenario scenario : scenarios.scenarios())
			byCitation.put(scenario.citation(), scenario);
		StringJoiner list = new StringJoiner("\n");
		for (RecordedTest test : recorded)
		{
			RegressionScenarios.Scenario scenario = byCitation.get(test.citation());
			StringBuilder item = new StringBuilder(128).append("- `").
				append(PortablePaths.text(test.directory())).append("`, recorded as `").append(test.citation()).
				append("` for `").append(PortablePaths.text(scenarios.project().relativize(scenario.source()))).
				append('`');
			if (scenario.format() instanceof RegressionScenarios.Earlier earlier)
			{
				item.append(". Its `scenario.md` holds the recorded file in an earlier format, which you convert: ").
					append(earlier.problem());
			}
			list.add(item);
		}
		return list.toString();
	}

	/**
	 * Returns the campaign's target relative to the project.
	 *
	 * @return the target
	 */
	private Path relativeTarget()
	{
		return scenarios.project().relativize(target);
	}

	/**
	 * Returns the campaign's target prompt file as a contract author session names it.
	 *
	 * @return the target prompt file relative to the project, in backticks
	 */
	String targetPromptFile()
	{
		return "`" + PortablePaths.text(relativeTarget()) + "`";
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
	 * Returns the validation of proposal files and draft directories against the target's recorded prompt tests.
	 *
	 * @return the validation
	 */
	ProposalCheck check()
	{
		return check;
	}

	/**
	 * Returns the recorded scenarios of the target.
	 *
	 * @return the scenarios
	 */
	RegressionScenarios scenarios()
	{
		return scenarios;
	}

	/**
	 * Returns the turn that asks the contract author to correct its proposal.
	 *
	 * @param result       the session's result after its turn
	 * @param promptSystem the directory that holds the prompt system
	 * @param profiles     the profile inventory and its capability ranking
	 * @param salt         the salt that CAT drew for the proposal
	 * @return the turn that lists the proposal's problems, or empty if the proposal is valid
	 */
	private Optional<String> correction(SessionResult.Completed result, Path promptSystem, ProfileEvidence profiles,
		long salt)
	{
		try
		{
			validate(result, promptSystem, profiles, salt);
			return Optional.empty();
		}
		catch (InvalidProposalException e)
		{
			StringJoiner problems = new StringJoiner("\n");
			for (String problem : e.problems())
				problems.add("- " + problem);
			return Optional.of(correction.fill(Map.of("problems", problems.toString(),
				"proposal_file", OutputFile.name(promptSystem), "draft_directory", DraftDirectory.name(promptSystem),
				"launcher", launcher(), "context_file", ContractContext.name(promptSystem))));
		}
	}

	/**
	 * Validates what a contract author session left and computes its trial profiles: the proposal file and the
	 * draft directory in the session's project. A capability ranking in the proposal file, which the user's feedback
	 * supplies, replaces the shipped one; the profiles of a described certification scope become the contract's
	 * described profiles. References to the session's project directory in the proposal file's text name the user's
	 * project in both the proposal and its saved derivation. Each generated value of a new test case receives its
	 * seed and version in the draft directory's values file.
	 *
	 * @param result       the contract author session's result
	 * @param promptSystem the directory that holds the prompt system, which the request named
	 * @param profiles     the profile inventory, its shipped capability ranking and the bindings that CAT read when
	 *                     the contract author ran
	 * @param salt         the salt that CAT drew for the proposal, which splits each test's outcome-changing
	 *                     combinations into the development part and the held-out part, and from which the seeds of
	 *                     the generator calls derive, so that reading the same files again gives the same contract
	 * @return the proposal with its draft directory in the session's project and each prompt test's change from the
	 *   recorded one, without derived sources, which the caller lists
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws InvalidProposalException if the session wrote no proposal file, the file is not exactly one JSON object,
	 *                                  or the proposal breaks a rule that
	 *                                  {@link ProposalCheck#check(ObjectNode, Path, Path, String, ProfileEvidence,
	 *                                  long)} lists. The exception lists every problem found.
	 * @throws UncheckedIOException     if a file cannot be read or written
	 */
	public DerivedProposal proposal(SessionResult.Completed result, Path promptSystem, ProfileEvidence profiles,
		long salt) throws InvalidProposalException
	{
		DerivedProposal derived = validate(result, promptSystem, profiles, salt);
		try
		{
			check.writeSeeds(derived.contract(), derived.draft());
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return derived.withDraft(derived.draft(), check.changes(derived.contract(), derived.draft(),
			scenarios.project()));
	}

	/**
	 * Validates what a contract author session left, without changing its files.
	 *
	 * @param result       the contract author session's result
	 * @param promptSystem the directory that holds the prompt system, which the request named
	 * @param profiles     the profile inventory, its shipped capability ranking and the bindings
	 * @param salt         the salt that CAT drew for the proposal
	 * @return the proposal, without derived sources and without its prompt tests' changes
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws InvalidProposalException if the proposal is invalid
	 */
	private DerivedProposal validate(SessionResult.Completed result, Path promptSystem, ProfileEvidence profiles,
		long salt) throws InvalidProposalException
	{
		requireThat(result, "result").isNotNull();
		requireThat(promptSystem, "promptSystem").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		ObjectNode proposalFile = outputFile.read(result);
		rewriteProjectPaths(proposalFile, result.effects());
		Path effects = result.effects();
		return check.check(proposalFile, effects, effects.resolve(DraftDirectory.name(promptSystem)),
			result.promptSources(), profiles, salt);
	}

	/**
	 * Validates a stored proposal file, whose text already names the user's project, with its stored draft directory,
	 * and computes its trial profiles.
	 *
	 * @param derivation      the stored proposal file
	 * @param draft           the stored draft directory
	 * @param observedSources the prompt sources that the harness reported loading in the contract author session
	 * @param profiles        the profile inventory, its shipped capability ranking and the bindings that CAT read
	 *                        when the contract author ran
	 * @param salt            the salt that CAT drew for the proposal
	 * @return the proposal with its draft directory and each prompt test's change from the recorded one, without
	 *   derived sources, which the caller restores from the stored proposal
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws InvalidProposalException if {@code derivation} is not exactly one JSON object or the proposal breaks a
	 *                                  rule that {@link ProposalCheck#restore(ObjectNode, Path, Path,
	 *                                  String, ProfileEvidence, long)} lists
	 * @throws UncheckedIOException     if a file cannot be read
	 */
	public DerivedProposal proposal(String derivation, Path draft, String observedSources, ProfileEvidence profiles,
		long salt) throws InvalidProposalException
	{
		requireThat(derivation, "derivation").isNotNull();
		requireThat(draft, "draft").isNotNull();
		requireThat(observedSources, "observedSources").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		DerivedProposal derived = check.restore(outputFile.parse(derivation, "the stored proposal"),
			scenarios.project(), draft, observedSources, profiles, salt);
		return derived.withDraft(draft, check.changes(derived.contract(), draft, scenarios.project()));
	}

	/**
	 * Replaces staged project references in every string value of a contract author session's proposal file with the
	 * user's project. Paths to the session's artifacts outside its project directory remain unchanged.
	 *
	 * @param output  the value whose text is rewritten in place
	 * @param effects the session's project copy
	 */
	void rewriteProjectPaths(JsonNode output, Path effects)
	{
		rewriteProjectPaths(output, effects.toAbsolutePath().normalize().toString(), scenarios.project().toString());
	}

	/**
	 * Replaces staged project references in every string value of a JSON value. Paths to the session's artifacts
	 * outside its project directory remain unchanged.
	 *
	 * @param output  the value whose text is rewritten in place
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
}
