package io.github.cowwoc.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationProcedure;
import io.github.cowwoc.cat.common.optimizer.campaign.ContractIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.test.Builder;
import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.AssertionKind;
import io.github.cowwoc.cat.common.optimizer.contract.CapabilityRanking;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;
import io.github.cowwoc.cat.common.optimizer.contract.FixtureFile;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.ScenarioFormatException;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestValue;
import io.github.cowwoc.cat.common.optimizer.contract.ValuesFile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.ValueGenerator;
import io.github.cowwoc.cat.common.optimizer.contract.ValueGenerators;
import io.github.cowwoc.cat.common.optimizer.contract.VariableDeclaration;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.execution.GuardPilot;
import io.github.cowwoc.cat.common.optimizer.execution.PilotResult;
import io.github.cowwoc.cat.common.optimizer.execution.PilotTrial;
import io.github.cowwoc.cat.common.optimizer.execution.test.FixedCalibrator;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.HookRegistration;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.proposal.Conflict;
import io.github.cowwoc.cat.common.optimizer.proposal.DerivedProposal;
import io.github.cowwoc.cat.common.optimizer.proposal.DraftChange;
import io.github.cowwoc.cat.common.optimizer.proposal.DraftDirectory;
import io.github.cowwoc.cat.common.optimizer.proposal.ContractAuthorProtocol;
import io.github.cowwoc.cat.common.optimizer.proposal.JsonResponse;
import io.github.cowwoc.cat.common.optimizer.proposal.JudgeTemplate;
import io.github.cowwoc.cat.common.optimizer.proposal.OutputFile;
import io.github.cowwoc.cat.common.optimizer.proposal.PluginCheck;
import io.github.cowwoc.cat.common.optimizer.proposal.PluginMapping;
import io.github.cowwoc.cat.common.optimizer.proposal.ProfileEvidence;
import io.github.cowwoc.cat.common.optimizer.proposal.PromptTemplate;
import io.github.cowwoc.cat.common.optimizer.proposal.Proposal;
import io.github.cowwoc.cat.common.optimizer.proposal.RegressionScenarios;
import io.github.cowwoc.cat.common.optimizer.proposal.ScopeCondition;
import io.github.cowwoc.cat.common.optimizer.report.RemainingWork;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibrationException;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport;
import io.github.cowwoc.cat.common.secrets.PromptTemplateSecrets;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.testng.annotations.AfterClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link ContractAuthorProtocol} and {@link Proposal}: contract author output in, the complete proposal out.
 */
public final class ProposalTest
{
	private static final Path PROMPT_SYSTEM = Path.of("/staged/incumbent").toAbsolutePath();
	/**
	 * The campaign's target prompt file.
	 */
	private static final Path TARGET = PROMPT_SYSTEM.resolve("CLAUDE.md");
	/**
	 * The format of a recorded scenario that predates prompt test directories.
	 */
	private static final RegressionScenarios.Format EARLIER = new RegressionScenarios.Earlier(
		"scenario.md: the scenario has no ## Variables section");
	/**
	 * The citation of a recorded scenario of {@code rules/safety.md} in an earlier format.
	 */
	private static final String REPORTED = ".cat/tests/rules/safety/reported.md";
	/**
	 * The citation of a recorded scenario of {@code rules/style.md} in an earlier format.
	 */
	private static final String ANALOGOUS = ".cat/tests/rules/style/analogous.md";
	private static final String OBSERVED = "hook: .claude/hooks/session-start.sh\nskill: release-notes";
	private static final TrialProfile ROLE = new TrialProfile("role-model", "xhigh");
	private static final ProfileEvidence PROFILES = new ProfileEvidence(ContractFixtures.ONE_PROFILE,
		ContractFixtures.ranking(ContractFixtures.ONE_PROFILE), Map.of());
	/**
	 * The salt of the derived proposals.
	 */
	private static final long SALT = ContractFixtures.SALT;
	/**
	 * The target prompt file, which the control source empties.
	 */
	private static final Path CONTROL_FILE = Path.of("prompts/main.md");
	/**
	 * Stands for the contract author session's project directory in an output that a test writes.
	 */
	private static final String STAGED = "/runs/contract-author/project";
	/**
	 * The launcher that provides the contract tools.
	 */
	private static final Path LAUNCHER = Path.of("/opt/cat/bin/prompt-optimizer").toAbsolutePath();
	/**
	 * Returns no tool-output limit for any profile, as for a harness whose limit is not known.
	 */
	private static final Function<TrialProfile, OptionalLong> UNKNOWN_LIMIT = profile -> OptionalLong.empty();
	/**
	 * Holds the projects of the sessions that the tests create.
	 */
	private final TemporaryDirectories directories = new TemporaryDirectories();
	private final JsonMapper json = JsonMapper.builder().build();
	private final ProposalSchema schema = new ProposalSchema(json);
	private final JudgeTemplate judge = new JudgeTemplate(json, ROLE);
	private final ContractAuthorProtocol contractAuthor = new ContractAuthorProtocol(json, schema, judge,
		new RegressionScenarios(PROMPT_SYSTEM, List.of()), TARGET, null, true, List.of(), List.of(), LAUNCHER,
		UNKNOWN_LIMIT);

	/**
	 * Creates the test fixture.
	 */
	public ProposalTest()
	{
	}

	/**
	 * Returns the text of a contract author output fixture.
	 *
	 * @param name the file name in this class's package
	 * @return the text
	 */
	private static String fixture(String name)
	{
		try (InputStream in = ProposalTest.class.getResourceAsStream(name))
		{
			if (in == null)
				throw new UncheckedIOException(new IOException("Missing test resource " + name));
			return new String(in.readAllBytes(), UTF_8);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Returns text in which the user's project is named by its absolute path, which starts with a drive on Windows.
	 *
	 * @param text text that names the user's project {@code /staged/incumbent}
	 * @return the text with each {@code /staged/incumbent} replaced by the project's absolute path
	 */
	private static String incumbent(String text)
	{
		return text.replace("/staged/incumbent", PROMPT_SYSTEM.toString());
	}

	/**
	 * Returns a completed contract author session that wrote its output to its output file. The session's project
	 * directory replaces each occurrence of {@link #STAGED} in the output.
	 *
	 * @param output the content of the output file
	 * @return the session's result
	 */
	private SessionResult.Completed session(String output)
	{
		return session(output, Path.of("CLAUDE.md"), Map.of());
	}

	/**
	 * Returns a completed contract author session that wrote its proposal file and its draft directory. Each prompt
	 * test of the output that holds its scenario inline is written as files to the draft directory. The session's
	 * project directory replaces each occurrence of {@link #STAGED} in the output.
	 *
	 * @param output   the contract author output
	 * @param target   the campaign's target, relative to the project, in whose test directory a new prompt test is
	 *                 written
	 * @param recorded the directory of each recorded prompt test, by its citation, which a test that cites it is
	 *                 written to
	 * @return the session's result
	 */
	private SessionResult.Completed session(String output, Path target, Map<String, Path> recorded)
	{
		try
		{
			Path initialProject = directories.add(Files.createTempDirectory("contract-author-initial-"));
			Path effects = directories.add(Files.createTempDirectory("contract-author-project-"));
			write(effects, initialProject, output, target, recorded);
			return session(effects, initialProject, "The proposal is in " + OutputFile.name(initialProject) + ".");
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Writes a contract author output to a session's project as its proposal file and its draft directory.
	 *
	 * @param effects        the session's project after the session
	 * @param initialProject the session's project before the session
	 * @param output         the contract author output
	 * @param target         the campaign's target, relative to the project
	 * @param recorded       the directory of each recorded prompt test, by its citation
	 * @throws IOException if a file cannot be written
	 */
	private void write(Path effects, Path initialProject, String output, Path target, Map<String, Path> recorded)
		throws IOException
	{
		// The output is JSON text, so a Windows path's separators must be escaped
		String escaped = effects.toString().replace("\\", "\\\\");
		String proposalFile = DraftFixtures.write(json, output.replace(STAGED, escaped),
			effects.resolve(DraftDirectory.name(initialProject)), target, recorded);
		Files.writeString(effects.resolve(OutputFile.name(initialProject)), proposalFile, UTF_8);
	}

	/**
	 * Returns a completed contract author session.
	 *
	 * @param effects the session's project after the session
	 * @param initialProject the session's project before the session
	 * @param response the session's final response
	 * @return the session's result
	 */
	private static SessionResult.Completed session(Path effects, Path initialProject, String response)
	{
		return new SessionResult.Completed(Path.of("/runs/contract-author/artifacts/transcript.jsonl").toAbsolutePath(),
			List.of(List.of("I will inspect the prompt system.", response)), effects, initialProject, "model", null,
			null, List.of(), OBSERVED, List.of());
	}

	/**
	 * Removes the directories of the sessions that the tests created.
	 *
	 * @throws IOException if a directory cannot be removed
	 */
	@AfterClass
	public void removeDirectories() throws IOException
	{
		directories.close();
	}

	/**
	 * Verifies that only the final message of the last turn can supply a structured session result.
	 */
	@Test
	public void structuredOutputUsesLastFinalMessage()
	{
		// Requirement: R-C12
		JsonResponse responses = new JsonResponse(json);
		SessionResult.Completed earlier = session(Path.of(STAGED),
			Path.of("/runs/contract-author/initial-project").toAbsolutePath(), "{\"answer\":1}");
		SessionResult.Completed invalidFinal = new SessionResult.Completed(earlier.transcript(),
			List.of(List.of("{\"answer\":1}"), List.of("{\"answer\":2}", "No structured output.")),
			earlier.effects(), earlier.initialProject(), earlier.model(), null, null, List.of(), OBSERVED, List.of());
		requireThat(responses.parseFinalResponse(invalidFinal), "invalidFinal").isEmpty();
		SessionResult.Completed validFinal = new SessionResult.Completed(earlier.transcript(),
			List.of(List.of("{\"answer\":1}"), List.of("Working.", "{\"answer\":2}")), earlier.effects(),
			earlier.initialProject(),
			earlier.model(), null, null, List.of(), OBSERVED, List.of());
		requireThat(responses.parseFinalResponse(validFinal).orElseThrow().get("answer").asInt(), "finalAnswer").
			isEqualTo(2);
	}

	/**
	 * Returns the valid contract author output with one change.
	 *
	 * @param change edits the output
	 * @return the output as JSON text
	 */
	private String changedDerivation(Consumer<ObjectNode> change)
	{
		ObjectNode output = (ObjectNode) json.readTree(fixture("derivation.json"));
		change.accept(output);
		return json.writeValueAsString(output);
	}

	/**
	 * Returns the contract that {@code derivation.json} describes, with CAT's judge fields.
	 *
	 * @return the contract
	 */
	private Contract expectedContract()
	{
		Contract fixture = ContractFixtures.contract();
		return new Contract(fixture.requirements(), fixture.tests(), fixture.profiles(),
			judge.profile("The response follows the requirement.", "Pass only if every rubric item holds."),
			fixture.fixtures(), fixture.limitations(), fixture.minimumPassRate(), fixture.salt());
	}

	/**
	 * Returns a contract whose prompt tests do not name their directories in a draft directory, so that it can be
	 * compared with a contract that a test builds.
	 *
	 * @param contract a contract
	 * @return the contract without its prompt tests' drafts
	 */
	private static Contract withoutDrafts(Contract contract)
	{
		List<PromptTest> tests = new ArrayList<>();
		for (PromptTest test : contract.tests())
		{
			tests.add(new PromptTest(test.id(), test.riskRank(), test.requirementIds(), test.absentTriggers(),
				test.applicability(), test.turns(), test.timeLimit(), test.expectedBehaviour(),
				test.uncoveredInteractions(), test.variables(), test.committedTestCases(), test.testCases(),
				test.assertions(), test.regressionScenarios(), test.fixtures()));
		}
		return new Contract(contract.requirements(), tests, contract.profiles(), contract.judge(), contract.fixtures(),
			contract.limitations(), contract.minimumPassRate(), contract.salt());
	}

	/**
	 * Returns the proposal of the valid contract author output for a fresh campaign.
	 *
	 * @param links the symbolic links
	 * @param login the host's login
	 * @return the proposal
	 * @throws InvalidProposalException if the fixture is invalid
	 */
	private Proposal proposal(LinkReport links, HostLogin login) throws InvalidProposalException
	{
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES,
			SALT);
		return Proposal.of(derived, new Builder().build(), links, CONTROL_FILE, login,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
	}

	/**
	 * Returns a link report with no links.
	 *
	 * @return the report
	 */
	private static LinkReport noLinks()
	{
		FileInventory inventory = new FileInventory(Path.of("/project").toAbsolutePath(), Map.of());
		return LinkReport.of(inventory, new Authority(Set.of(Path.of("prompts")), Set.of(), inventory));
	}

	/**
	 * Returns a link report with a link inside the project, a link to a read-only external file and, optionally, a
	 * link that forms a cycle.
	 *
	 * @param cycle {@code true} to add the cycle
	 * @return the report
	 */
	private static LinkReport links(boolean cycle)
	{
		FileEntry.RegularFile file = new FileEntry.RegularFile(Sha256.of("text".getBytes(UTF_8)),
			PosixFilePermissions.fromString("rw-r--r--"));
		Map<Path, FileEntry> entries = new HashMap<>(Map.of(
			Path.of("prompts/main.md"), file,
			Path.of("alias.md"), new FileEntry.SymbolicLink(Path.of("prompts/main.md")),
			Path.of("shared.md"), new FileEntry.SymbolicLink(Path.of("/shared/rules.md").toAbsolutePath()),
			Path.of("/shared/rules.md").toAbsolutePath(), file));
		if (cycle)
		{
			entries.put(Path.of("loop-a.md"), new FileEntry.SymbolicLink(Path.of("loop-b.md")));
			entries.put(Path.of("loop-b.md"), new FileEntry.SymbolicLink(Path.of("loop-a.md")));
		}
		FileInventory inventory = new FileInventory(Path.of("/project").toAbsolutePath(), entries);
		return LinkReport.of(inventory, new Authority(Set.of(Path.of("prompts")), Set.of(), inventory));
	}

	/**
	 * Verifies that the contract author's first session works in the prompt system, so the harness loads it as in
	 * production; that its one turn carries an output schema in which CAT's judge fields are absent and the contract
	 * author's findings are required; and that the same turn names the proposal file, the draft directory and the
	 * launcher, so that the author writes each file as it settles it instead of in a separate output turn.
	 */
	@Test
	public void requestCarriesTheContractAuthorSchema()
	{
		// Requirement: R-E20
		// Requirement: R-E13
		SessionRequest request = contractAuthor.request(PROMPT_SYSTEM, PROFILES, List.of(), SALT);

		requireThat(request.role(), "role").isEqualTo(SessionRole.CONTRACT_AUTHOR);
		requireThat(request.source(), "source").isEqualTo(PROMPT_SYSTEM);
		requireThat(request.turns().size(), "turns.size()").isEqualTo(1);
		String prompt = request.turns().getFirst();
		requireThat(prompt, "prompt").contains("Previous proposal:\n\nnone\n").contains("User feedback:\n\nnone\n").
			contains(OutputFile.name(PROMPT_SYSTEM)).contains(DraftDirectory.name(PROMPT_SYSTEM)).
			contains(LAUNCHER + " contract check");
		JsonNode outputSchema = readOutputSchema(prompt);
		JsonNode judgeProperties = outputSchema.get("properties").get("judge").get("properties");
		requireThat(List.copyOf(judgeProperties.propertyNames()), "judge.properties").
			isEqualTo(List.of("rubric", "decision_rule"));
		List<String> required = outputSchema.get("required").valueStream().map(JsonNode::asString).toList();
		requireThat(required, "required").contains("scope_conditions").contains("requirements").
			doesNotContain("derived_sources");
		for (ValueGenerator generator : ValueGenerators.all())
			requireThat(prompt, "prompt").contains(generator.describe());
	}

	/**
	 * Reads the schema from the contract author's Output section, which the instruction to check the files follows.
	 *
	 * @param prompt the contract author's rendered prompt
	 * @return the output schema
	 */
	private JsonNode readOutputSchema(String prompt)
	{
		int start = prompt.indexOf('{', prompt.indexOf("### Output"));
		int end = prompt.indexOf("\n\nBefore you end the turn", start);
		return json.readTree(prompt.substring(start, end));
	}

	/**
	 * Verifies that a contract author output in a Markdown code fence becomes the contract it describes, with CAT's
	 * judge model, prompt and result schema, and that the contract author's findings and the observed prompt sources
	 * are kept.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void derivesTheProposalFromItsFile() throws InvalidProposalException
	{
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM,
			PROFILES, SALT);

		requireThat(withoutDrafts(derived.contract()), "contract").isEqualTo(expectedContract());
		for (PromptTest test : derived.contract().tests())
		{
			requireThat(test.draft().orElseThrow().directory(), "directory").
				isEqualTo(Path.of(".cat/tests/CLAUDE").resolve(test.id()));
		}
		requireThat(derived.contract().judge().profile(), "judge.profile").isEqualTo(ROLE);
		requireThat(derived.contract().judge().prompt(), "judge.prompt").
			isEqualTo(PromptTemplate.load("/optimizer/judge/judge.md").text());
		requireThat(json.readTree(derived.contract().judge().resultSchema()), "judge.resultSchema").
			isEqualTo(json.readTree(PromptTemplateSecrets.access().readResource("/optimizer/judge/verdict.schema.json")));
		requireThat(derived.derivedSources(), "derivedSources").isEmpty();
		requireThat(derived.scopeConditions(), "scopeConditions").isEqualTo(List.of(new ScopeCondition(
			Outcome.UNSUPPORTED_OUTPUT, List.of("r1"), "CLAUDE.md asks for a diagram image with every summary.")));
		requireThat(derived.observedSources(), "observedSources").isEqualTo(OBSERVED);
	}

	/**
	 * Verifies that conflict quotations, resolutions and recommendation reasons name the user's project in both
	 * accepted findings and the derivation saved for later reads.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	// Requirement: R-C13
	@Test
	public void rewritesConflictProjectPaths() throws InvalidProposalException
	{
		String output = changedDerivation(root ->
		{
			ObjectNode conflict = root.putArray("conflicts").addObject();
			conflict.putArray("requirement_ids").add("r1").add("r2");
			conflict.put("evidence", "[AGENTS.md:6](/runs/contract-author/project/AGENTS.md:6) contradicts " +
				"/runs/contract-author/project/rules/safety.md.");
			conflict.putArray("resolutions").add("Edit /runs/contract-author/project/AGENTS.md.").
				add("Edit [safety.md](/runs/contract-author/project/rules/safety.md).");
			conflict.put("recommended", 1);
			conflict.put("reason", "Keeps [AGENTS.md](/runs/contract-author/project/AGENTS.md) and " +
				"/runs/contract-author/project/rules/safety.md consistent.");
		});

		DerivedProposal derived = contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
		Conflict expected = new Conflict(List.of("r1", "r2"),
			incumbent("[AGENTS.md:6](/staged/incumbent/AGENTS.md:6) contradicts /staged/incumbent/rules/safety.md."),
			List.of(incumbent("Edit /staged/incumbent/AGENTS.md."),
				incumbent("Edit [safety.md](/staged/incumbent/rules/safety.md).")), 1,
			incumbent("Keeps [AGENTS.md](/staged/incumbent/AGENTS.md) and /staged/incumbent/rules/safety.md consistent."));
		requireThat(derived.conflicts(), "conflicts").isEqualTo(List.of(expected));
		requireThat(derived.derivation(), "derivation").doesNotContain("/runs/contract-author/project");
		requireThat(contractAuthor.proposal(derived.derivation(), derived.draft(), OBSERVED, PROFILES, SALT).conflicts(),
			"storedConflicts"). isEqualTo(List.of(expected));
	}

	/**
	 * Verifies that scope evidence names the user's project in links and bare paths before it is saved.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	// Requirement: R-C13
	@Test
	public void rewritesScopeProjectPaths() throws InvalidProposalException
	{
		String output = changedDerivation(root -> ((ObjectNode) root.get("scope_conditions").get(0)).
			put("evidence", "[CLAUDE.md:2](/runs/contract-author/project/CLAUDE.md:2) loads " +
				"/runs/contract-author/project/rules/images.md."));

		DerivedProposal derived = contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
		ScopeCondition expected = new ScopeCondition(Outcome.UNSUPPORTED_OUTPUT, List.of("r1"),
			incumbent("[CLAUDE.md:2](/staged/incumbent/CLAUDE.md:2) loads /staged/incumbent/rules/images.md."));
		requireThat(derived.scopeConditions(), "scopeConditions").isEqualTo(List.of(expected));
		requireThat(derived.derivation(), "derivation").doesNotContain("/runs/contract-author/project");
		requireThat(contractAuthor.proposal(derived.derivation(), derived.draft(), OBSERVED, PROFILES,
			SALT).scopeConditions(),
			"storedScope"). isEqualTo(List.of(expected));
	}

	/**
	 * Verifies that ordinary prose and paths to session artifacts outside its project directory remain unchanged.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	// Requirement: R-C13
	@Test
	public void preservesOtherFindingText() throws InvalidProposalException
	{
		String evidence = "[transcript](/runs/contract-author/artifacts/transcript.jsonl) explains the conflict.";
		Conflict expected = new Conflict(List.of("r1", "r2"), evidence,
			List.of("Exempt explanations.", "Drop brevity."), 1, "Keeps the most intent.");
		String output = changedDerivation(root ->
		{
			ObjectNode conflict = root.putArray("conflicts").addObject();
			conflict.putArray("requirement_ids").add("r1").add("r2");
			conflict.put("evidence", evidence);
			conflict.putArray("resolutions").add("Exempt explanations.").add("Drop brevity.");
			conflict.put("recommended", 1);
			conflict.put("reason", "Keeps the most intent.");
		});

		DerivedProposal derived = contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
		requireThat(derived.conflicts(), "conflicts").isEqualTo(List.of(expected));
		requireThat(derived.scopeConditions().getFirst().evidence(), "scopeEvidence").
			isEqualTo("CLAUDE.md asks for a diagram image with every summary.");
		requireThat(json.readTree(derived.derivation()).at("/conflicts/0/evidence").asString(), "storedEvidence").
			isEqualTo(evidence);
	}

	/**
	 * Supplies proposal file text locations displayed alongside the conflict and scope findings.
	 *
	 * @return JSON pointers to prose, sources, a prompt test's applicability and uncovered interactions, and the
	 *   judge's fields
	 */
	@DataProvider
	public Object[][] proposalTextLocations()
	{
		return new Object[][]
		{
			{"/requirements/0/description"}, {"/requirements/0/source"},
			{"/tests/0/applicability"},
			{"/tests/1/uncovered_interactions/0"},
			{"/judge/rubric"}, {"/judge/decision_rule"}, {"/limitations/0"}
		};
	}

	/**
	 * Verifies that all displayed proposal file prose is rewritten in the saved derivation and accepted again with
	 * the user's paths, including nested arrays.
	 *
	 * @param pointer the location of the text in the contract author output
	 * @throws InvalidProposalException if the output is rejected
	 */
	// Requirement: R-C13
	@Test(dataProvider = "proposalTextLocations")
	public void rewritesOtherProposalText(String pointer) throws InvalidProposalException
	{
		ObjectNode output = (ObjectNode) json.readTree(fixture("derivation-with-checks.json"));
		int separator = pointer.lastIndexOf('/');
		JsonNode parent = output.at(pointer.substring(0, separator));
		String field = pointer.substring(separator + 1);
		String stagedText = "/runs/contract-author/project/notes.md";
		if (parent instanceof ObjectNode object)
			object.put(field, stagedText);
		else
			((ArrayNode) parent).set(Integer.parseInt(field), json.getNodeFactory().stringNode(stagedText));

		DerivedProposal derived = contractAuthor.proposal(session(json.writeValueAsString(output)), PROMPT_SYSTEM,
			PROFILES, SALT);

		requireThat(json.readTree(derived.derivation()).at(pointer).asString(), "savedText").
			isEqualTo(incumbent("/staged/incumbent/notes.md"));
		DerivedProposal reread = contractAuthor.proposal(derived.derivation(), derived.draft(), OBSERVED, PROFILES, SALT);
		requireThat(reread.contract(), "storedContract").isEqualTo(derived.contract());
	}

	/**
	 * Verifies that a contract author output that replaces its lists with counts and summaries is rejected rather than
	 * presented.
	 */
	@Test
	public void rejectsAnAggregateOnlySummary()
	{
		// Requirement: R-C1
		// Requirement: R-E13
		try
		{
			contractAuthor.proposal(session(fixture("derivation-aggregate.json")), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").contains("$.requirements").
				contains("$.tests");
		}
	}

	/**
	 * Verifies that a contract author cannot choose the judge model, prompt or result schema.
	 */
	@Test
	public void rejectsContractAuthorJudgeFields()
	{
		// Requirement: R-C5
		try
		{
			contractAuthor.proposal(session(fixture("derivation-with-judge-fields.json")), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems(), "problems").
				contains("$.judge.model: CAT supplies this field, so the contract author " + "must leave it out").
				contains("$.judge.result_schema: CAT supplies this field, so the contract author must " +
				"leave it out");
		}
	}

	/**
	 * Verifies that an output file with prose around the proposal is refused with the line and column at which it
	 * stops being JSON, so that the contract author can correct that part.
	 */
	@Test
	public void reportsWhereTheOutputFileStopsParsing()
	{
		try
		{
			contractAuthor.proposal(session("{\n  \"requirements\": [\n    oops\n  ]\n}\n"), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems().size(), "problems.size()").isEqualTo(1);
			requireThat(e.problems().getFirst(), "problem").startsWith("$: ").contains(OutputFile.NAME).
				contains("line 3");
		}
	}

	/**
	 * Verifies that a session that wrote no output file is refused with a problem that names the file, even when its
	 * final response holds a valid proposal.
	 *
	 * @throws IOException if a directory cannot be created
	 */
	@Test
	public void refusesAMissingOutputFile() throws IOException
	{
		Path initialProject = directories.add(Files.createTempDirectory("contract-author-initial-"));
		Path effects = directories.add(Files.createTempDirectory("contract-author-project-"));
		try
		{
			contractAuthor.proposal(session(effects, initialProject, fixture("derivation.json")), PROMPT_SYSTEM,
				PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems().size(), "problems.size()").isEqualTo(1);
			requireThat(e.problems().getFirst(), "problem").startsWith("$: ").contains(OutputFile.NAME);
		}
	}

	/**
	 * Verifies that when the project already holds a file named like the output file, the session writes its output
	 * to another name that the request states, and CAT reads that file rather than the project's.
	 *
	 * @throws IOException              if a file cannot be written
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void outputFileAvoidsProjectFiles() throws IOException, InvalidProposalException
	{
		Path project = directories.add(Files.createTempDirectory("contract-author-initial-"));
		Files.writeString(project.resolve(OutputFile.NAME), "{\"user\": \"data\"}\n", UTF_8);
		String name = OutputFile.name(project);
		requireThat(name, "name").isNotEqualTo(OutputFile.NAME);
		requireThat(contractAuthor.request(project, PROFILES, List.of(), SALT).turns().getFirst(), "turn").
			contains(name);

		Path effects = directories.add(Files.createTempDirectory("contract-author-project-"));
		Files.writeString(effects.resolve(OutputFile.NAME), "{\"user\": \"data\"}\n", UTF_8);
		write(effects, project, fixture("derivation.json"), Path.of("CLAUDE.md"), Map.of());
		DerivedProposal derived = contractAuthor.proposal(session(effects, project, "Done."), project, PROFILES,
			SALT);
		requireThat(withoutDrafts(derived.contract()), "contract").isEqualTo(expectedContract());
	}

	/**
	 * Verifies that the correction turn names the output file and lists each problem with its JSON path, so that the
	 * contract author can edit only the values that the problems concern.
	 */
	@Test
	public void correctionNamesTheFileAndEachProblem()
	{
		SessionRequest request = contractAuthor.request(PROMPT_SYSTEM, PROFILES, List.of(), SALT);
		String correction = request.followUp().orElseThrow().next(session(fixture("derivation-with-judge-fields.json"))).
			orElseThrow();
		requireThat(correction, "correction").contains(OutputFile.NAME).
			contains("- $.judge.model: CAT supplies this field");
		requireThat(request.followUp().orElseThrow().next(session(fixture("derivation.json"))), "valid").isEmpty();
	}

	/**
	 * Verifies that a stored derivation is validated again from its text, as {@code confirm} and {@code revise} read
	 * it after the session's files are gone.
	 *
	 * @throws InvalidProposalException if the stored text is rejected
	 */
	@Test
	public void validatesAStoredDerivation() throws InvalidProposalException
	{
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES,
			SALT);
		DerivedProposal stored = contractAuthor.proposal(derived.derivation(), derived.draft(), OBSERVED, PROFILES, SALT);
		requireThat(stored.contract(), "contract").isEqualTo(derived.contract());
		requireThat(stored.observedSources(), "observedSources").isEqualTo(OBSERVED);
		try
		{
			contractAuthor.proposal("not json", PROMPT_SYSTEM, OBSERVED, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems().getFirst(), "problem").startsWith("$: ");
		}
	}

	/**
	 * Verifies that the contract author's findings are validated: a scope condition that names an unknown condition or
	 * an unknown requirement, and a derived source without a rule, are rejected.
	 */
	@Test
	public void rejectsInvalidFindings()
	{
		// Requirement: R-E13
		// Requirement: R-C6
		String output = changedDerivation(root ->
		{
			ObjectNode condition = (ObjectNode) root.get("scope_conditions").get(0);
			condition.put("condition", "slow_output");
			condition.putArray("requirement_ids").add("r9");
		});
		try
		{
			contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").
				contains("$.scope_conditions[0].condition").
				contains("$.scope_conditions[0].requirement_ids[0]: r9 is not a requirement of the proposal");
		}
	}

	/**
	 * Verifies that an output listing derived sources is rejected, since CAT lists the files that its rules and the
	 * harness load.
	 */
	@Test
	public void rejectsDerivedSources()
	{
		String output = changedDerivation(root -> root.putArray("derived_sources").addObject().
			put("path", "CLAUDE.md").put("rule", "The harness reads it at startup."));
		try
		{
			contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems(), "problems").contains("$.derived_sources: CAT computes the derived sources, " +
				"so the contract author must leave them out");
		}
	}

	/**
	 * Verifies that feedback sends the previous proposal and the feedback to a new contract author session in the same
	 * prompt system, and that the regenerated output becomes a complete proposal again.
	 *
	 * @throws InvalidProposalException if an output is rejected
	 */
	@Test
	public void feedbackRegeneratesTheCompleteProposal() throws InvalidProposalException
	{
		// Requirement: R-C2
		DerivedProposal first = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES, SALT);
		String feedback = "Split the safety test by the kind of directory.";

		SessionRequest request = contractAuthor.request(PROMPT_SYSTEM, PROFILES, List.of(), first, feedback, Map.of());

		requireThat(request.role(), "role").isEqualTo(SessionRole.CONTRACT_AUTHOR);
		requireThat(request.source(), "source").isEqualTo(PROMPT_SYSTEM);
		requireThat(request.turns().size(), "turns.size()").isEqualTo(1);
		String prompt = request.turns().getFirst();
		requireThat(prompt, "prompt").contains("User feedback:\n\n" + feedback + "\n").contains(first.derivation());
		requireThat(json.readTree(first.derivation()).at("/tests/0/directory").asString(), "directory").
			isEqualTo(".cat/tests/CLAUDE/formatting");

		String revised = changedDerivation(root -> ((ObjectNode) root.get("requirements").get(1)).
			put("description", "Never delete a directory or a file without asking."));
		Proposal second = Proposal.of(contractAuthor.proposal(session(revised), PROMPT_SYSTEM, PROFILES, SALT),
			new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
		requireThat(second.render(), "render").contains("Never delete a directory or a file without asking.").
			contains("formatting").contains("safety").contains("## Statistical procedure");
	}

	/**
	 * Verifies that the rendered proposal lists each requirement, test, profile, test, expected behavior,
	 * variable, procedure and warning.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void rendersEveryItem() throws InvalidProposalException
	{
		// Requirement: R-C1
		// Requirement: R-C2
		String text = proposal(links(false), HostLogin.SUBSCRIPTION).render();

		Contract contract = expectedContract();
		for (Requirement requirement : contract.requirements())
		{
			requireThat(text, "text").contains("`" + requirement.id() + "`: " + requirement.description()).
				contains("Source: " + requirement.source());
		}
		requireThat(text, "text").contains("balanced benchmark").contains("usage frequencies are unknown");
		for (PromptTest test : contract.tests())
		{
			requireThat(text, "text").contains("### Test `" + test.id() + "`").
				contains("Risk rank: " + test.riskRank().position() + " (1 is the riskiest)").
				contains("Applies when: " + test.applicability()).
				contains("Time limit: " + test.timeLimit().toSeconds() + " seconds").
				contains("Expected behaviour: " + test.expectedBehaviour());
			for (String turn : test.turns())
				requireThat(text, "text").contains(". " + turn + "\n");
			for (String interaction : test.uncoveredInteractions())
				requireThat(text, "text").contains(interaction);
			for (VariableDeclaration variable : test.variables())
				requireThat(text, "text").contains("`" + variable.name() + "`: " + variable.description());
			for (TestCase testCase : test.allTestCases())
			{
				for (Entry<String, String> value : PromptTest.textByVariable(testCase).entrySet())
					requireThat(text, "text").contains("`" + value.getKey() + "` = \"" + value.getValue() + "\"");
				requireThat(text, "text").contains("; expected outcome \"" + testCase.expectedOutcome() + "\"; " +
					contract.split().partOf(test, testCase) + " part");
			}
		}
		requireThat(text, "text").contains("Rubric: " + contract.judge().rubric()).
			contains("Decision rule: " + contract.judge().decisionRule()).
			contains("Role profile: " + ROLE.label()).
			contains("- .env.example\n").
			contains("Only the English prompts are covered.");
	}

	/**
	 * Verifies that the rendered proposal states the statistical procedure: the joint budget, the bets, the caps of
	 * the first attempts, the commit gate, the review checkpoint and the baseline targets.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void rendersTheStatisticalProcedure() throws InvalidProposalException
	{
		// Requirement: R-C1
		String text = proposal(noLinks(), HostLogin.API_KEY).render();

		requireThat(text, "text").contains("0.04 for certification").contains("0.005 for improvement claims").
			contains("0.005 for the incumbent's bounds").
			contains(CertificationProcedure.BETS).
			contains("Attempt 1: α = 1/50 (2.00%), cap " + FixedCalibrator.STANDARD_CAP).
			contains("Attempt 2: α = 1/150 (0.667%)").
			contains("Attempt 3: α = 1/300 (0.333%)").
			contains("- Commit gate: a cell meets the commit gate after at least 100 representative development " +
				"trials").
			contains("at least 99% observed passes").
			contains("Review checkpoint: at 200 representative trials").
			contains("50 representative trials in every cell").
			contains("ending at 100").
			contains("Every cell needs its own commit gate, baseline and certification trials.");
	}

	/**
	 * Verifies that a cap whose calibration fails is shown as unavailable rather than omitted.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void rendersAnUnavailableCap() throws InvalidProposalException
	{
		// Requirement: R-C1
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES,
			SALT);
		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			(level, minimumPassRate) ->
			{
				throw new CapCalibrationException("The calibration found no cap");
			}, schema);

		requireThat(proposal.render(), "render").
			contains("Attempt 1: α = 1/50 (2.00%), cap unavailable: The calibration found no cap");
	}

	/**
	 * Verifies that a subscription login, and a login whose kind CAT cannot tell, produce the warning with the
	 * smallest minimum session count of the certification scope choices and the API key recommendation, and that an
	 * API key produces no warning.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void warnsUnlessTheLoginIsAnApiKey() throws InvalidProposalException
	{
		// Requirement: R-E7
		Proposal subscription = proposal(noLinks(), HostLogin.SUBSCRIPTION);
		int trials = minimumTrials(subscription, CertificationScope.WEAKEST_CELL);
		String sessions = String.valueOf(2 * trials);

		requireThat(subscription.render(), "subscription").contains("Warning: the harness signs in with a " +
			"subscription login").contains("at least " + sessions + " nested sessions").contains("Use an API key");
		String apiKey = proposal(noLinks(), HostLogin.API_KEY).render();
		requireThat(apiKey, "apiKey").doesNotContain("Warning:").contains("signs in with an API key");
		String unknown = proposal(noLinks(), HostLogin.UNKNOWN).render();
		requireThat(unknown, "unknown").contains("Warning: CAT could not tell").
			contains("at least " + sessions + " nested sessions").contains("Use an API key");
	}

	/**
	 * Returns the minimum remaining trials of a fresh campaign once the proposal is confirmed with a certification
	 * scope.
	 *
	 * @param proposal the proposal
	 * @param scope the certification scope
	 * @return the trials
	 */
	private int minimumTrials(Proposal proposal, CertificationScope scope)
	{
		Contract contract = proposal.contract().withScope(scope);
		Campaign confirmed = Transitions.apply(new Builder().build(),
			new CampaignEvent.Confirm(contract, ContractIdentity.of(schema, contract), 0));
		return RemainingWork.of(confirmed).total();
	}

	/**
	 * Verifies that each certification scope choice states its minimum session count: one trial session and one
	 * judge session for each of the minimum remaining trials, with each cell's share.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void statesTheMinimumSessionCount() throws InvalidProposalException
	{
		// Requirement: R-E7
		// Requirement: R-C1
		Proposal proposal = proposal(noLinks(), HostLogin.API_KEY);
		int trials = minimumTrials(proposal, CertificationScope.LATEST_VERSIONS);

		requireThat(trials, "trials").isPositive();
		requireThat(proposal.render(), "render").contains("## Certification scope").
			contains("At least " + 2 * trials + " nested sessions remain if every trial passes: " + trials +
				" trials, each with one trial session and one judge session.").
			contains("\n- tests `formatting`, `plain_list`, `safety` × profile `test-model/high` (3 cells): commit " +
				"gate ");
	}

	/**
	 * Verifies that the proposal shows the capability ranking with its source and, for each named certification
	 * scope choice, its cells, each test's weakest cell and its minimum session count, and that confirming with a
	 * choice freezes that scope.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void showsEachCertificationScopeChoice() throws InvalidProposalException
	{
		// Requirement: R-C15
		// Requirement: R-E22
		// Requirement: R-C1
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM,
			twoProfiles(), SALT);
		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
		int weakest = minimumTrials(proposal, CertificationScope.WEAKEST_CELL);
		int latest = minimumTrials(proposal, CertificationScope.LATEST_VERSIONS);
		requireThat(weakest, "weakest").isLessThan(latest);

		String text = proposal.render();
		requireThat(text, "text").
			contains("Capability ranking, most capable first (test ranking in inventory order): big, small").
			contains("### `weakest_cell`: each test's weakest cell only").
			contains("### `latest_versions`: every cell of each model family's latest version").
			doesNotContain("### `described`").
			contains("To certify other cells, describe them in revision feedback").
			contains("\nCells: tests `formatting`, `plain_list` × profile `small/high`; test `safety` × profile " +
				"`big/high` (3 cells)\n").
			contains("\nWeakest cells: tests `formatting`, `plain_list` × profile `small/high`; test `safety` × " +
				"profile `big/high`\n").
			contains("At least " + 2 * weakest + " nested sessions remain if every trial passes: " + weakest +
				" trials").
			contains("\nCells: tests `formatting`, `plain_list` × profiles `big/high`, `small/high`; test `safety` " +
				"× profile `big/high` (5 cells)\n").
			doesNotContain("every cell of test").
			contains("At least " + 2 * latest + " nested sessions remain if every trial passes: " + latest +
				" trials");
		requireThat(text.indexOf("### `weakest_cell`"), "order").isLessThan(text.indexOf("### `latest_versions`"));

		CampaignEvent confirm = proposal.confirmation(CertificationScope.WEAKEST_CELL, 7).getFirst();
		Contract scoped = proposal.contract().withScope(CertificationScope.WEAKEST_CELL);
		requireThat(confirm, "confirm").isEqualTo(new CampaignEvent.Confirm(scoped,
			ContractIdentity.of(schema, scoped), 7));
	}

	/**
	 * Verifies that an inventory model whose family the capability ranking does not list is named in the proposal,
	 * leaves every certification scope choice unavailable, and blocks confirmation, and that a described scope
	 * without described cells blocks confirmation.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void unrankedModelBlocksConfirmation() throws InvalidProposalException
	{
		// Requirement: R-E22
		// Requirement: R-C15
		ProfileEvidence partial = new ProfileEvidence(ContractFixtures.TWO_PROFILES,
			new CapabilityRanking(List.of("big"), "test ranking without small"), Map.of());
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, partial,
			SALT);
		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);

		requireThat(proposal.render(), "render").contains("Unranked models: small").
			contains("No choice is available yet").doesNotContain("### `weakest_cell`");
		for (CertificationScope scope : List.of(CertificationScope.WEAKEST_CELL, CertificationScope.LATEST_VERSIONS))
		{
			requireThat(proposal.isConfirmable(scope), scope.id()).isFalse();
			requireThat(proposal.confirmationRefusal(scope), scope.id() + ".refusal").contains("small").
				contains("revise");
		}

		Proposal ranked = proposal(noLinks(), HostLogin.API_KEY);
		requireThat(ranked.isConfirmable(CertificationScope.DESCRIBED), "describedWithoutCells").isFalse();
		requireThat(ranked.confirmationRefusal(CertificationScope.DESCRIBED), "describedRefusal").
			contains("revision feedback");
	}

	/**
	 * Verifies that revision feedback supplies a corrected capability ranking, which then decides each scope's weakest
	 * cell, and the profiles of a described certification scope, which the proposal shows as a third choice that can
	 * be confirmed; that a described profile outside the inventory and an invalid ranking are refused; and that the
	 * contract author's request shows the shipped ranking.
	 *
	 * @throws InvalidProposalException if the revised output is rejected
	 */
	@Test
	public void feedbackSuppliesRankingAndDescribedScope() throws InvalidProposalException
	{
		// Requirement: R-C15
		// Requirement: R-E22
		// Requirement: R-C1
		ProfileEvidence evidence = evidence(ContractFixtures.TWO_PROFILES, Map.of());
		requireThat(contractAuthor.request(PROMPT_SYSTEM, evidence, List.of(), SALT).turns().getFirst(), "request").
			contains("Capability ranking, most capable first (test ranking in inventory order): big, small");

		String revised = changedDerivation(output ->
		{
			output.putArray("capability_ranking").add("small").add("big");
			output.putArray("certification_profiles").addObject().put("model", "small").put("effort", "");
		});
		DerivedProposal derived = contractAuthor.proposal(session(revised), PROMPT_SYSTEM, evidence, SALT);
		requireThat(derived.contract().profiles().ranking(), "ranking").isEqualTo(new CapabilityRanking(
			List.of("small", "big"), "the user's revision feedback"));
		requireThat(derived.contract().profiles().described(), "described").
			isEqualTo(List.of(new TrialProfile("small", "")));

		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
		String text = proposal.render();
		requireThat(text, "text").
			contains("Capability ranking, most capable first (the user's revision feedback): small, big").
			contains("\nWeakest cells: tests `formatting`, `plain_list`, `safety` × profile `big/high`\n").
			contains("### `described`: the cells that you described in revision feedback").
			contains("\nCells: tests `formatting`, `plain_list`, `safety` × profile `small/high` (3 cells)\n").
			contains("\nWeakest cells: tests `formatting`, `plain_list`, `safety` × profile `small/high`\n");
		requireThat(proposal.isConfirmable(CertificationScope.DESCRIBED), "describedConfirmable").isTrue();

		String outside = changedDerivation(output -> output.putArray("certification_profiles").addObject().
			put("model", "huge").put("effort", ""));
		requireThat(problems(outside, evidence), "outside").contains("huge/default").
			contains(ContractFixtures.TWO_PROFILES.source());
		String duplicate = changedDerivation(output -> output.putArray("capability_ranking").add("big").add("big"));
		requireThat(problems(duplicate, evidence), "duplicate").
			contains("$.capability_ranking[1]: duplicate family big");
	}

	/**
	 * Returns the problems that rejected a contract author output.
	 *
	 * @param output the contract author's output
	 * @param evidence the profile evidence
	 * @return the problems, one per line
	 */
	private String problems(String output, ProfileEvidence evidence)
	{
		try
		{
			contractAuthor.proposal(session(output), PROMPT_SYSTEM, evidence, SALT);
		}
		catch (InvalidProposalException e)
		{
			return String.join("\n", e.problems());
		}
		throw new AssertionError("The output was accepted: " + output);
	}

	/**
	 * Verifies that loading shows what the contract author session observed and what was derived from documented rules,
	 * and that the confirmed contract records the limit of derived loading.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void recordsObservedAndDerivedLoading() throws InvalidProposalException
	{
		// Requirement: R-C7
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES,
			SALT).
			withDerivedSources(List.of(new DerivedSource("CLAUDE.md",
				"The harness reads the project instruction file in the project root at startup.")));
		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);

		requireThat(proposal.render(), "render").contains("- hook: .claude/hooks/session-start.sh\n").
			contains("- skill: release-notes\n").
			contains("- CLAUDE.md: The harness reads the project instruction file in the project root at startup.").
			contains(Proposal.DERIVED_LOADING_LIMIT);
		requireThat(proposal.contract().limitations(), "limitations").
			isEqualTo(List.of("Only the English prompts are covered.", Proposal.DERIVED_LOADING_LIMIT,
				Proposal.CONTROL_SOURCE_LIMIT));
	}

	/**
	 * Verifies that a derived source inside a trial plugin's base copy, and a referrer inside it that a source's rule
	 * names, are shown by the plugin's name and the path inside the plugin, while a project file keeps its path
	 * relative to the project and a file outside both keeps its absolute path.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void showsPluginSourcesByPlugin() throws InvalidProposalException
	{
		Path base = Path.of("/campaign/plugins/base-a").toAbsolutePath();
		Path defaultRules = base.resolve("rules/common/default-rules.md");
		Path lazy = base.resolve("rules/include/lazy.md");
		// A directory whose name extends the base's name lies outside the base
		Path sibling = Path.of("/campaign/plugins/base-ab/rules/other.md").toAbsolutePath();
		List<DerivedSource> sources = List.of(
			new DerivedSource(defaultRules.toString(), "CAT injects this rule into every session"),
			new DerivedSource(lazy.toString(), "`" + defaultRules + "` tells the agent to load this file"),
			new DerivedSource(".cat/rules/common/project.md", "CAT injects this rule into every session"),
			new DerivedSource(sibling.toString(), "`" + sibling + "` includes this file, which CAT injects with it"));
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES,
			SALT).withDerivedSources(sources);
		PluginCheck check = new PluginCheck(List.of(builtPlugin()), List.of(), List.of(), List.of(), List.of(),
			Map.of("cat", base));

		String text = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema, null, Map.of(), check).render();

		requireThat(text, "text").
			contains("\n- trial plugin `cat`: `rules/common/default-rules.md`: CAT injects this rule into every " +
				"session\n").
			contains("\n- trial plugin `cat`: `rules/include/lazy.md`: trial plugin `cat`: " +
				"`rules/common/default-rules.md` tells the agent to load this file\n").
			contains("\n- .cat/rules/common/project.md: CAT injects this rule into every session\n").
			contains("\n- " + sibling + ": `" + sibling + "` includes this file, which CAT injects with it\n").
			doesNotContain(base + File.separator);
	}

	/**
	 * Verifies that every symbolic link is shown with its target, whether the target lies inside the project, its
	 * edit authority and the staging consequence, which the confirmed contract also records as a limitation.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void reportsSymbolicLinks() throws InvalidProposalException
	{
		// Requirement: R-C1
		Proposal proposal = proposal(links(false), HostLogin.API_KEY);

		requireThat(proposal.render(), "render").
			contains("- `alias.md` -> `" + Path.of("prompts/main.md") + "`: inside the project; its content may be edited").
			contains("- `shared.md` -> `" + Path.of("/shared/rules.md").toAbsolutePath() +
				"`: outside the project; external target read only").
			contains(LinkReport.STAGING_CONSEQUENCE);
		requireThat(proposal.contract().limitations(), "limitations").contains(LinkReport.STAGING_CONSEQUENCE);
		requireThat(proposal.isConfirmable(CertificationScope.LATEST_VERSIONS), "isConfirmable").isTrue();
		requireThat(proposal(noLinks(), HostLogin.API_KEY).contract().limitations(), "noLinks").
			doesNotContain(LinkReport.STAGING_CONSEQUENCE);
	}

	/**
	 * Verifies that the proposal names the target prompt file as the only file that the control source empties, and
	 * that the confirmed contract records that an emptied file can still be referenced.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void namesControlSourceFile() throws InvalidProposalException
	{
		// Requirement: R-W10
		Proposal proposal = proposal(noLinks(), HostLogin.API_KEY);

		requireThat(proposal.render(), "render").contains("""
			## Control source

			Control trials run the incumbent with the target prompt file `%s` emptied to zero bytes. \
			The contract lists no dependents of the target, so every other part of the prompt system stays as the \
			incumbent has it.
			""".formatted(CONTROL_FILE)).contains(Proposal.CONTROL_SOURCE_LIMIT);
		requireThat(proposal.contract().limitations(), "limitations").contains(Proposal.CONTROL_SOURCE_LIMIT);
	}

	/**
	 * Verifies that the proposal lists each dependent that control trials remove, that the confirmed contract carries
	 * them, and that a dependent that the control source cannot remove blocks confirmation.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void listsDependentsAndBlocksOnRefusals() throws InvalidProposalException
	{
		// Requirement: R-W10
		DerivedProposal derived = contractAuthor.proposal(session(changedDerivation(output ->
			output.set("dependents", json.readTree("""
				[
				  {"location": "project", "plugin": null, "path": "repair.sh", "removes": "file", "text": null,
				   "event": null, "command": null, "serves": "Repairs the output."},
				  {"location": "project", "plugin": null, "path": "AGENTS.md", "removes": "span",
				   "text": "Read prompts/main.md.\\n", "event": null, "command": null,
				   "serves": "Routes the agent to the prompt."},
				  {"location": "project", "plugin": null, "path": ".codex/hooks.json", "removes": "hook_registration",
				   "text": null, "event": "PostToolUse", "command": "repair.sh", "serves": "Runs the repair script."}
				]
				""")))), PROMPT_SYSTEM, PROFILES, SALT);
		String refusal = "Control trials cannot remove the dependents of the target that the proposal lists: " +
			"the project file repair.sh, which a dependent names, is not a regular file of the project.";
		Proposal accepted = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema, null, Map.of(), PluginCheck.NONE,
			List.of());
		Proposal refused = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema, null, Map.of(), PluginCheck.NONE,
			List.of(refusal));

		requireThat(accepted.render(), "render").contains("""
			Control trials run the incumbent with the target prompt file `%s` emptied to zero bytes and \
			these dependents of the target removed. Every other part of the prompt system stays as the incumbent has it.

			- The project file `repair.sh`: deleted. Serves: Repairs the output.
			- The project file `AGENTS.md`: the text below deleted. Serves: Routes the agent to the prompt.

			  ```
			  Read prompts/main.md.
			  ```

			- The project file `%s`: its PostToolUse hook `repair.sh` unregistered. Serves: Runs the \
			repair script.
			""".formatted(CONTROL_FILE, Path.of(".codex/hooks.json")));
		requireThat(accepted.contract().dependents(), "dependents").isEqualTo(derived.contract().dependents());
		requireThat(accepted.contract().dependents().size(), "dependents.size()").isEqualTo(3);
		requireThat(accepted.isConfirmable(CertificationScope.LATEST_VERSIONS), "acceptedConfirmable").isTrue();
		requireThat(refused.isConfirmable(CertificationScope.LATEST_VERSIONS), "refusedConfirmable").isFalse();
		requireThat(refused.confirmationRefusal(CertificationScope.LATEST_VERSIONS), "refusal").contains(refusal);
	}

	/**
	 * Verifies that the proposal shows each assertion's pass rate in the guard pilot and warns on a guard below the
	 * minimum pass rate, but not on an effect assertion below it.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void showsGuardPilotRates() throws InvalidProposalException
	{
		// Requirement: R-C23
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES,
			SALT);
		Contract contract = derived.contract();
		Map<String, PilotResult> pilots = new LinkedHashMap<>();
		String sessionFailed = "The trial session failed: the harness exited with status 1";
		String undecidable = "The built-in tools could not be decided: `notes.md` was not a file when the trial started";
		pilots.put("formatting", pilot(contract, "formatting", "uses_requested_tone", 10,
			List.of(sessionFailed, undecidable, sessionFailed), null));
		pilots.put("safety", pilot(contract, "safety", "asks_before_deleting", 15, List.of(), null));

		String text = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema, null, pilots).render();

		String formattingCell = GuardPilot.pilotCell(contract, "formatting").orElseThrow().id();
		String safetyCell = GuardPilot.pilotCell(contract, "safety").orElseThrow().id();
		requireThat(text, "text").contains("\n## Guard pilot\n").contains("### `formatting` in cell `" +
			formattingCell + "`\n\n17 trials decided, 3 failed runs.\n\n" +
			"- `uses_requested_tone` (effect): 10 of 17 passed (58.8%)\n" +
			"- `expected_outcome` (effect): 17 of 17 passed (100%)\n\n" +
			"Why the failed runs ended without a decision:\n\n" +
			"- " + sessionFailed + " (2 runs)\n" +
			"- " + undecidable + " (1 run)\n").
			contains("### `safety` in cell `" + safetyCell + "`\n\n20 trials decided, 0 failed runs.\n\n" +
				"- `asks_before_deleting` (guard): 15 of 20 passed (75.0%). Warning: below the minimum pass rate of 95%.").
			contains("- `expected_outcome` (guard): 20 of 20 passed (100%)\n").
			contains("### `plain_list`\n\nNo pilot: CAT has not run it.\n");
		requireThat(text.split("Warning: below the minimum pass rate", -1).length, "warnings").isEqualTo(2);
	}

	/**
	 * Verifies that a guard whose pilot pass rate equals the minimum pass rate draws no warning, and that an assertion
	 * that no trial decided is shown as undecided rather than as a rate.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void guardAtMinimumRateHasNoWarning() throws InvalidProposalException
	{
		// Requirement: R-C23
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES,
			SALT);
		Contract contract = derived.contract();
		String safetyCell = GuardPilot.pilotCell(contract, "safety").orElseThrow().id();
		List<PilotTrial> trials = new ArrayList<>();
		for (int i = 0; i < 20; ++i)
		{
			trials.add(new PilotTrial(Map.of(), "comply", Map.of("asks_before_deleting", i != 0),
				"The assistant asked first."));
		}
		PilotResult safety = new PilotResult("safety", Sha256.of("safety".getBytes(UTF_8)), safetyCell,
			Sha256.of("control".getBytes(UTF_8)), trials, List.of(), null);

		String text = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema, null, Map.of("safety", safety)).
			render();

		requireThat(text, "text").contains("- `asks_before_deleting` (guard): 19 of 20 passed (95.0%)\n").
			contains("- `expected_outcome` (guard): decided in no trial\n").doesNotContain("Warning:");
	}

	/**
	 * Verifies that a pilot that a rate limit stopped says why and how to finish it.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void showsStoppedPilot() throws InvalidProposalException
	{
		// Requirement: R-C23
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES,
			SALT);
		Contract contract = derived.contract();
		String reason = "The provider rejected a pilot session because of a rate limit.";
		PilotResult safety = pilot(contract, "safety", "asks_before_deleting", 3, List.of(), reason);

		String text = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema, null, Map.of("safety", safety)).
			render();

		requireThat(text, "text").contains("The pilot stopped before it finished: " + reason +
			" Run `start` again to finish it.\n");
	}

	/**
	 * Verifies that the revision brief carries the latest guard pilot results after the previous proposal.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void revisionBriefCarriesPilotResults() throws InvalidProposalException
	{
		// Requirement: R-C23
		DerivedProposal first = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES, SALT);
		Map<String, PilotResult> pilots = Map.of("safety", pilot(first.contract(), "safety", "asks_before_deleting",
			15, List.of(), null));

		String prompt = contractAuthor.request(PROMPT_SYSTEM, PROFILES, List.of(), first, "Keep the tests.",
			pilots).turns().
			getFirst();

		requireThat(prompt, "prompt").contains(first.derivation() + "\n\nGuard pilot results of the previous " +
			"proposal:\n\n").
			contains("- `asks_before_deleting` (guard): 15 of 20 passed (75.0%). Warning: below the minimum pass rate");
	}

	/**
	 * Verifies that the proposal lists each trial plugin with the source of its base, the project paths that trials
	 * write over a project-built plugin, each mapped file whose content differs from the base's, each prompt file that
	 * no mapping covers and each file that loads in production but not in trials, and that a campaign without trial
	 * plugins says that trials install none.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	// Requirement: R-W12
	@Test
	public void listsTrialPlugins() throws InvalidProposalException
	{
		PluginCheck check = new PluginCheck(List.of(builtPlugin(), installedPlugin()),
			List.of(Path.of("docs/extra.md")), List.of(new PluginMapping.Difference("cat",
				Path.of("source/rules/read-file.md"), Path.of("rules/codex/read-file.md"))),
			List.of("/home/user/.codex/AGENTS.md"), List.of(), Map.of());

		String text = pluginProposal(check).render();
		String none = pluginProposal(PluginCheck.NONE).render();

		requireThat(text, "text").contains("\n## Trial plugins\n").
			contains("- `cat`: built by `./build-plugin --release` into `out/plugin`, with these staged project paths " +
				"written over it:\n  - `source/rules` → `rules/codex`\n").
			contains("- `tools@market`: the user's installed copy\n").
			contains("Warning: the content of these mapped files differs from the base's").
			contains("- `source/rules/read-file.md` → `cat` `rules/codex/read-file.md`\n").
			contains("never reaches trials:\n\n- `docs/extra.md`\n").
			contains("but not in trials:\n\n- `/home/user/.codex/AGENTS.md`\n");
		requireThat(none, "none").contains("\n## Trial plugins\n\nTrials install no plugin.\n");
	}

	/**
	 * Verifies that the contract author's request lists every trial plugin, with the project paths that trials write
	 * over each one that the project builds.
	 */
	// Requirement: R-W10
	// Requirement: R-C27
	@Test
	public void requestListsTrialPlugins()
	{
		ContractAuthorProtocol withPlugins = new ContractAuthorProtocol(json, schema, judge,
			new RegressionScenarios(PROMPT_SYSTEM, List.of()), TARGET, null, true, List.of(),
			List.of(builtPlugin(), installedPlugin()), LAUNCHER, UNKNOWN_LIMIT);

		String listed = withPlugins.request(PROMPT_SYSTEM, PROFILES, List.of(), SALT).turns().getFirst();
		String none = contractAuthor.request(PROMPT_SYSTEM, PROFILES, List.of(), SALT).turns().getFirst();

		requireThat(listed, "listed").
			contains("\n- `cat`, built by the project, with these project paths written over it: `source/rules` → " +
				"`rules/codex`\n- `tools@market`, installed by the user\n");
		requireThat(none, "none").contains("each one that the project builds,\n  are:\n\nnone\n\n");
	}

	/**
	 * Verifies that the contract author's request lists every hook registration with its event, matcher, command and
	 * the file that registers it, of the project or of a trial plugin, and names the draft directory, the proposal
	 * file and the launcher that provides the contract tools.
	 */
	// Requirement: R-C27
	@Test
	public void requestListsHookRegistrations()
	{
		List<HookRegistration> hooks = List.of(
			new HookRegistration("", ".claude/settings.json", "SessionStart", "", "bin/session-start"),
			new HookRegistration("cat", "hooks/hooks.json", "PostToolUse", "Bash", "${PLUGIN_ROOT}/bin/repair"));

		String prompt = contractAuthor.request(PROMPT_SYSTEM, PROFILES, hooks, SALT).turns().getFirst();
		String none = contractAuthor.request(PROMPT_SYSTEM, PROFILES, List.of(), SALT).turns().getFirst();

		requireThat(prompt, "prompt").
			contains("- event `SessionStart`, command `bin/session-start`, registered by `.claude/settings.json` " +
				"of the project\n").
			contains("- event `PostToolUse`, matcher `Bash`, command `${PLUGIN_ROOT}/bin/repair`, registered by " +
				"`hooks/hooks.json` of the trial plugin `cat`\n").
			contains("the draft directory `" + DraftDirectory.name(PROMPT_SYSTEM) + "`").
			contains("the proposal file `" + OutputFile.name(PROMPT_SYSTEM) + "`").
			contains("`" + LAUNCHER + " contract fixtures ");
		requireThat(none, "none").contains("with the file that\n  registers it:\n\nnone\n\n");
	}

	/**
	 * Verifies that the proposal names each hook of the trial plugins that trials do not run, and records no hook
	 * limitation when trials run every hook.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	// Requirement: R-W12
	@Test
	public void listsUnapprovedPluginHooks() throws InvalidProposalException
	{
		Proposal unapproved = pluginProposal(new PluginCheck(List.of(builtPlugin()), List.of(), List.of(), List.of(),
			List.of("cat SessionStart `./log.sh`", "cat PostToolUse `./check.sh`"), Map.of()));
		Proposal approved = pluginProposal(new PluginCheck(List.of(builtPlugin()), List.of(), List.of(), List.of(),
			List.of(), Map.of()));

		requireThat(unapproved.contract().limitations(), "unapproved").contains(
			"Trials do not run these hooks of the trial plugins, because the user has not approved them in " +
			"the harness: cat SessionStart `./log.sh`; cat PostToolUse `./check.sh`. A hook also does not run if its " +
			"declaration changed after the user approved it. Approve the hooks in the harness and run revise, " +
			"or accept that trials can behave differently from sessions in which those hooks run.");
		requireThat(approved.contract().limitations().stream().
			filter(limitation -> limitation.contains("hooks of the trial plugins")).toList(), "approved").isEmpty();
	}

	/**
	 * Returns a trial plugin that the project builds, whose one mapping writes the project's {@code source/rules} over
	 * the build output's {@code rules/codex}.
	 *
	 * @return the plugin
	 */
	private static TrialPlugin builtPlugin()
	{
		return new TrialPlugin("cat", "a".repeat(64), new TrialPlugin.Build(List.of("./build-plugin", "--release"),
			Path.of("out/plugin")), List.of(new TrialPlugin.Mapping(Path.of("source/rules"), Path.of("rules/codex"))));
	}

	/**
	 * Returns a trial plugin that trials install from the user's installed copy.
	 *
	 * @return the plugin
	 */
	private static TrialPlugin installedPlugin()
	{
		return new TrialPlugin("tools@market", "b".repeat(64), new TrialPlugin.Installed(), List.of());
	}

	/**
	 * Returns the proposal of the valid contract author output with a check of the trial plugins.
	 *
	 * @param check the check of the trial plugins
	 * @return the proposal
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	private Proposal pluginProposal(PluginCheck check) throws InvalidProposalException
	{
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES,
			SALT);
		return Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema, null, Map.of(), check);
	}

	/**
	 * Returns a guard pilot result in which every trial passed {@value TestCase#EXPECTED_OUTCOME}.
	 *
	 * @param contract the contract
	 * @param testId the prompt test
	 * @param assertionId the prompt test's assertion
	 * @param passes the number of trials that passed {@code assertionId}
	 * @param failedRuns why each of the 20 trials that ended without a decision ended
	 * @param stopReason why the pilot stopped early, or {@code null}
	 * @return the result
	 */
	private static PilotResult pilot(Contract contract, String testId, String assertionId, int passes,
		List<String> failedRuns, String stopReason)
	{
		List<PilotTrial> trials = new ArrayList<>();
		for (int i = 0; i < 20 - failedRuns.size(); ++i)
		{
			Map<String, Boolean> assertions = new LinkedHashMap<>();
			assertions.put(assertionId, i < passes);
			assertions.put(TestCase.EXPECTED_OUTCOME, true);
			trials.add(new PilotTrial(Map.of(), "comply", assertions, "The judge's rationale."));
		}
		return new PilotResult(testId, Sha256.of(testId.getBytes(UTF_8)),
			GuardPilot.pilotCell(contract, testId).orElseThrow().id(), Sha256.of("control".getBytes(UTF_8)), trials,
			failedRuns, stopReason);
	}

	/**
	 * Verifies that a link cycle is shown as blocking and prevents confirmation.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test(expectedExceptions = IllegalStateException.class,
		expectedExceptionsMessageRegExp = ".*loop-a\\.md.*")
	public void aLinkCycleBlocksConfirmation() throws InvalidProposalException
	{
		// Requirement: R-C1
		Proposal proposal = proposal(links(true), HostLogin.API_KEY);

		requireThat(proposal.isConfirmable(CertificationScope.LATEST_VERSIONS), "isConfirmable").isFalse();
		requireThat(proposal.render(), "render").contains("- `loop-a.md`: the links form a cycle; this blocks " +
			"confirmation");
		proposal.confirmation(CertificationScope.LATEST_VERSIONS, 7);
	}

	/**
	 * Verifies that a scope condition is shown with its {@code certification unavailable} outcome, and that confirming
	 * freezes the contract, with CAT's judge profile, and records the outcome.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void confirmFreezesContractAndScope() throws InvalidProposalException
	{
		// Requirement: R-C6
		// Requirement: R-C5
		Proposal proposal = proposal(noLinks(), HostLogin.API_KEY);
		requireThat(proposal.render(), "render").contains("- " + Outcome.UNSUPPORTED_OUTPUT.label() +
			" (requirements r1): CLAUDE.md asks for a diagram image with every summary.");

		List<CampaignEvent> events = proposal.confirmation(CertificationScope.LATEST_VERSIONS, 7);

		Contract contract = proposal.contract().withScope(CertificationScope.LATEST_VERSIONS);
		requireThat(events, "events").isEqualTo(List.of(
			new CampaignEvent.Confirm(contract, ContractIdentity.of(schema, contract), 7),
			new CampaignEvent.ReportUnavailable(Outcome.UNSUPPORTED_OUTPUT)));
		Campaign campaign = new Builder().build();
		for (CampaignEvent event : events)
			campaign = Transitions.apply(campaign, event);
		requireThat(campaign.contract().judge(), "judge").isEqualTo(expectedContract().judge());
		requireThat(campaign.lineage().unavailable(), "unavailable").isEqualTo(Set.of(Outcome.UNSUPPORTED_OUTPUT));
	}

	/**
	 * Returns the evidence of an inventory whose families rank in inventory order.
	 *
	 * @param inventory the inventory
	 * @param bindings the profile that each file binds
	 * @return the evidence
	 */
	private static ProfileEvidence evidence(ProfileCatalog inventory, Map<String, TrialProfile> bindings)
	{
		return new ProfileEvidence(inventory, ContractFixtures.ranking(inventory), bindings);
	}

	/**
	 * Returns the evidence of a two-profile inventory in which {@code rules/safety.md} binds the big profile.
	 *
	 * @return the evidence
	 */
	private static ProfileEvidence twoProfiles()
	{
		return evidence(ContractFixtures.TWO_PROFILES, Map.of("rules/safety.md", ContractFixtures.BIG));
	}

	/**
	 * Verifies that CAT computes each test's cells from the inventory and the bindings, and that the proposal
	 * shows the inventory with its source, the bindings, each test's cells and the minimum trials of each cell.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void rendersTrialProfilesAndCells() throws InvalidProposalException
	{
		// Requirement: R-C10
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM,
			twoProfiles(), SALT);
		requireThat(derived.contract().cellIds(), "cells").isEqualTo(List.of(ContractFixtures.BIG_CELL,
			ContractFixtures.SMALL_CELL, ContractFixtures.PLAIN_BIG_CELL, ContractFixtures.PLAIN_SMALL_CELL, "safety"));
		requireThat(derived.contract().findCell("safety").orElseThrow().profile(), "safety.profile").
			isEqualTo(ContractFixtures.BIG);

		String text = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema).render();
		requireThat(text, "text").contains("## Trial profiles").
			contains("Trial profile inventory (" + ContractFixtures.TWO_PROFILES.source() + "): big/high, small/high").
			contains("rules/safety.md binds big/high").
			contains("tests formatting, plain_list (2 cells each, named `<test>@<profile>`): big/high, small/high").
			contains("test safety (1 cell, named by the test): big/high").
			contains("Minimum trials by cell:").
			contains("\nCells: tests `formatting`, `plain_list` × profiles `big/high`, `small/high`; test `safety` " +
				"× profile `big/high` (5 cells)\n");
	}

	/**
	 * Verifies that tests with the same profile set share one line of trial profiles, and that cells with the
	 * same minimum trials share one line of the session count, so that the proposal does not repeat the profile list
	 * for every cell.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void groupsCellsThatShareProfilesAndCounts() throws InvalidProposalException
	{
		// Requirement: R-C10
		ProfileCatalog inventory = new ProfileCatalog("test inventory of three profiles", List.of(
			new TrialProfile("big", "high"), new TrialProfile("big", "low"), new TrialProfile("small", "high")));
		DerivedProposal derived = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM,
			evidence(inventory, Map.of()), SALT);
		requireThat(derived.contract().cellIds().size(), "cells").isEqualTo(9);

		String text = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema).render();
		requireThat(text, "text").
			contains("  - tests formatting, plain_list, safety (3 cells each, named `<test>@<profile>`): big/high, " +
				"big/low, small/high").
			contains("\nCells: tests `formatting`, `plain_list`, `safety` × profiles `big/high`, `big/low`, " +
				"`small/high` (9 cells)\n").
			contains("\n- tests `formatting`, `plain_list`, `safety` × profiles `big/high`, `big/low` (6 cells): " +
				"baseline 50, next attempt 93\n");
	}

	/**
	 * Verifies that a revision's profile restriction narrows every test's profile set, that a restriction that
	 * names only an effort keeps that effort, and that a restriction that matches no inventory profile is rejected.
	 *
	 * @throws InvalidProposalException if the restricted output is rejected
	 */
	@Test
	public void restrictionNarrowsTheProfileSet() throws InvalidProposalException
	{
		// Requirement: R-C10
		String restricted = changedDerivation(output -> output.putArray("profile_restriction").addObject().
			put("model", "small").put("effort", ""));
		DerivedProposal derived = contractAuthor.proposal(session(restricted), PROMPT_SYSTEM, evidence(
			ContractFixtures.TWO_PROFILES, Map.of()), SALT);
		requireThat(derived.contract().cellIds(), "cells").isEqualTo(List.of("formatting", "plain_list", "safety"));
		requireThat(derived.contract().findCell("formatting").orElseThrow().profile(), "profile").
			isEqualTo(ContractFixtures.SMALL);
		requireThat(derived.contract().profiles().restriction(), "restriction").
			isEqualTo(List.of(new TrialProfile("small", "")));

		String byEffort = changedDerivation(output -> output.putArray("profile_restriction").addObject().
			put("model", "").put("effort", "high"));
		requireThat(contractAuthor.proposal(session(byEffort), PROMPT_SYSTEM, twoProfiles(), SALT).contract().
			profiles().restriction(), "effortRestriction").isEqualTo(List.of(new TrialProfile("", "high")));

		String unmatched = changedDerivation(output -> output.putArray("profile_restriction").addObject().
			put("model", "huge").put("effort", ""));
		try
		{
			contractAuthor.proposal(session(unmatched), PROMPT_SYSTEM, twoProfiles(), SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").contains("huge/default").
				contains(ContractFixtures.TWO_PROFILES.source());
		}
	}

	/**
	 * Verifies that the contract author cannot supply the trial profiles, which CAT computes.
	 */
	@Test
	public void rejectsContractAuthorTrialProfiles()
	{
		// Requirement: R-C10
		String output = changedDerivation(derivation -> derivation.putObject("trial_profiles"));
		try
		{
			contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems(), "problems").contains("$.trial_profiles: CAT computes the trial profiles, so " +
				"the contract author must leave them out");
		}
	}

	/**
	 * Verifies that the contract author's request lists the harness's profile inventory and its source, so that it can
	 * turn the user's feedback into a restriction, and that it names a model without an effort dimension as such.
	 */
	@Test
	public void requestListsTheProfileInventory()
	{
		// Requirement: R-C10
		String prompt = contractAuthor.request(PROMPT_SYSTEM, evidence(ContractFixtures.TWO_PROFILES, Map.of()),
			List.of(), SALT).
			turns(). getFirst();
		requireThat(prompt, "prompt").contains(ContractFixtures.TWO_PROFILES.source()).
			contains("- model `big`, effort `high`").contains("- model `small`, effort `high`");

		ProfileCatalog plain = new ProfileCatalog("plain models", List.of(new TrialProfile("plain", "")));
		requireThat(contractAuthor.request(PROMPT_SYSTEM, evidence(plain, Map.of()), List.of(), SALT).turns().getFirst(),
			"plainPrompt").contains("- model `plain`, no effort dimension").doesNotContain("effort ``");
	}

	/**
	 * Verifies that the output schema of the contract author's request requires each test's assertions and holds neither
	 * checks nor discarded checks.
	 */
	@Test
	public void requestCarriesTheAssertionSchema()
	{
		// Requirement: R-E20
		// Requirement: R-C9
		String prompt = contractAuthor.request(PROMPT_SYSTEM, PROFILES, List.of(), SALT).turns().getFirst();

		JsonNode outputSchema = readOutputSchema(prompt);
		JsonNode test = outputSchema.get("properties").get("tests").get("items");
		List<String> required = test.get("required").valueStream().map(JsonNode::asString).toList();
		requireThat(required, "required").contains("assertions").doesNotContain("checks").
			doesNotContain("discarded_checks");
		requireThat(test.get("properties").has("checks"), "hasChecks").isFalse();
		requireThat(test.get("properties").has("discarded_checks"), "hasDiscardedChecks").isFalse();
		requireThat(outputSchema.get("$defs").has("assertion"), "hasAssertionDefinition").isTrue();
		requireThat(outputSchema.get("$defs").has("check"), "hasCheckDefinition").isFalse();
	}

	/**
	 * Returns the proposal of a contract author output.
	 *
	 * @param derivation the contract author's output
	 * @return the rendered proposal
	 * @throws InvalidProposalException if the output is rejected
	 */
	private String rendered(String derivation) throws InvalidProposalException
	{
		DerivedProposal derived = contractAuthor.proposal(session(derivation), PROMPT_SYSTEM, PROFILES, SALT);
		return Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema).render();
	}

	/**
	 * Verifies that the contract author's request lists each recorded prompt test of the target with its directory in
	 * the draft directory, the citation that records it and the guidance whose contract requires it, and asks for a
	 * recorded test in an earlier format to be converted.
	 */
	@Test
	public void requestListsRecordedPromptTests()
	{
		// Requirement: R-I3
		String prompt = regressionContractAuthor().request(PROMPT_SYSTEM, PROFILES, List.of(), SALT).turns().
			getFirst();
		requireThat(prompt, "prompt").contains("### Recorded prompt tests").
			contains("- `.cat/tests/rules/safety/reported`, recorded as `" + REPORTED + "` for `rules/safety.md`. " +
				"Its `scenario.md` holds the recorded file in an earlier format, which you convert: " +
				"scenario.md: the scenario has no ## Variables section\n").
			contains("- `.cat/tests/rules/style/analogous`, recorded as `" + ANALOGOUS + "` for `rules/style.md`.");
	}

	/**
	 * Verifies that a proposal that leaves out recorded prompt tests of the target is refused, naming each of them.
	 *
	 * @throws InvalidProposalException when both recorded prompt tests are missing
	 */
	@Test(expectedExceptions = InvalidProposalException.class,
		expectedExceptionsMessageRegExp = "(?s).*reported\\.md.*analogous\\.md.*")
	public void refusesMissingRecordedPromptTests() throws InvalidProposalException
	{
		// Requirement: R-I3
		// Requirement: R-C26
		regressionContractAuthor().proposal(regressionSession(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES,
			SALT);
	}

	/**
	 * Verifies that a proposal that keeps every recorded prompt test of the target is accepted, each recorded test
	 * citing its scenario, that the proposal shows a converted test as an edit of the recorded file, and that the
	 * scenarios of guidance outside the target require no test.
	 *
	 * @throws InvalidProposalException if a complete proposal is rejected
	 */
	@Test
	public void acceptsEveryRecordedPromptTest() throws InvalidProposalException
	{
		// Requirement: R-I3
		// Requirement: R-C26
		String output = changedDerivation(proposal ->
		{
			((ObjectNode) proposal.get("tests").get(0)).putArray("regression_scenarios").add(REPORTED);
			((ObjectNode) proposal.get("tests").get(2)).putArray("regression_scenarios").add(ANALOGOUS);
		});
		DerivedProposal proposal = regressionContractAuthor().proposal(regressionSession(output), PROMPT_SYSTEM,
			PROFILES, SALT);
		requireThat(proposal.contract().findTest("reported").orElseThrow().regressionScenarios(), "reported").
			isEqualTo(List.of(REPORTED));
		requireThat(proposal.contract().findTest("analogous").orElseThrow().regressionScenarios(), "analogous").
			isEqualTo(List.of(ANALOGOUS));
		requireThat(render(proposal), "render").
			contains("- Files: recorded at `" + Path.of(".cat/tests/rules/safety/reported") + "` and edited; " +
				"delivery rewrites them with these changes:\n").
			contains("- Files: recorded at `" + Path.of(".cat/tests/rules/style/analogous") + "` and edited; " +
				"delivery rewrites them with these changes:\n").
			contains("- Files: created by the contract author; delivery writes `" +
				Path.of(".cat/tests/rules/plain_list") + "`\n");
	}

	/**
	 * Returns the rendered proposal of a derived proposal.
	 *
	 * @param derived the derived proposal
	 * @return the rendered proposal
	 */
	private String render(DerivedProposal derived)
	{
		return Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema).render();
	}

	/**
	 * Returns a project that records one prompt test of its instruction file {@code CLAUDE.md}, at
	 * {@code .cat/tests/CLAUDE/formatting}.
	 *
	 * @param scenario the recorded test's {@code scenario.md}
	 * @return the project
	 * @throws IOException if a file cannot be written
	 */
	private Path recordedProject(String scenario) throws IOException
	{
		Path project = directories.add(Files.createTempDirectory("contract-author-recorded-")).toRealPath();
		Files.writeString(project.resolve("CLAUDE.md"), "Answer in the requested tone.\n", UTF_8);
		Path test = Files.createDirectories(project.resolve(".cat/tests/CLAUDE/formatting"));
		Files.writeString(test.resolve("scenario.md"), scenario, UTF_8);
		return project;
	}

	/**
	 * Returns a contract author whose target is a project's {@code CLAUDE.md}, with the project's recorded scenarios.
	 *
	 * @param scenarios the project's recorded scenarios
	 * @return the contract author
	 */
	private ContractAuthorProtocol contractAuthor(RegressionScenarios scenarios)
	{
		return new ContractAuthorProtocol(json, schema, judge, scenarios, scenarios.project().resolve("CLAUDE.md"),
			null, true, List.of(), List.of(), LAUNCHER, UNKNOWN_LIMIT);
	}

	/**
	 * Returns a completed session of a contract author whose target is a project's {@code CLAUDE.md}. The session's
	 * draft directory starts with the files that the contract author's request supplies, and each prompt test of the
	 * output is then written over them, a recorded one at its directory.
	 *
	 * @param author  the contract author
	 * @param project the project
	 * @param output  the contract author output
	 * @return the session's result
	 * @throws IOException if a file cannot be read or written
	 */
	private SessionResult.Completed recordedSession(ContractAuthorProtocol author, Path project, String output)
		throws IOException
	{
		Path initialProject = directories.add(Files.createTempDirectory("contract-author-initial-"));
		Path effects = directories.add(Files.createTempDirectory("contract-author-project-"));
		author.request(project, PROFILES, List.of(), SALT).files().write(effects);
		write(effects, initialProject, output, Path.of("CLAUDE.md"),
			DraftDirectory.testDirectories(RegressionScenarios.read(json, project, project)));
		return session(effects, initialProject, "The proposal is in " + OutputFile.name(initialProject) + ".");
	}

	/**
	 * Verifies that the proposal shows how each prompt test's files relate to the recorded ones: created by the
	 * contract author, recorded and unchanged, or recorded and edited, an edit being shown as a difference from the
	 * recorded files that leaves out the test's values file and says instead that the file holds the listed test
	 * cases.
	 *
	 * @throws IOException              if a file cannot be written
	 * @throws InvalidProposalException if a proposal is rejected
	 */
	@Test
	public void rendersPromptTestChanges() throws IOException, InvalidProposalException
	{
		// Requirement: R-C1
		// Requirement: R-C26
		Path project = recordedProject("""
			# Formatting

			## Variables

			- **tone**: The tone of the summary.

			## Scenario

			Summarize the notes in {{tone}} style.

			## Assertions

			### keeps_tone

			- **kind**: effect
			- **description**: The summary keeps the {{tone}} tone.
			""");
		RegressionScenarios scenarios = RegressionScenarios.read(json, project, project);
		String citation = ".cat/tests/CLAUDE/formatting/scenario.md";
		String output = changedDerivation(proposal -> ((ObjectNode) proposal.get("tests").get(0)).
			putArray("regression_scenarios").add(citation));

		ContractAuthorProtocol author = contractAuthor(scenarios);
		DerivedProposal edited = author.proposal(recordedSession(author, project, output),
			project, PROFILES, SALT);
		String text = render(edited);

		requireThat(text, "text").contains("- Files: recorded at `" + Path.of(".cat/tests/CLAUDE/formatting") +
				"` and edited; delivery rewrites them with these changes:\n\n    ```diff\n    --- a/" + citation +
				"\n    +++ b/" + citation + "\n").
			contains("\n    +### Turn 2\n").contains("\n    -### keeps_tone\n").
			contains("\n    ```\n\n    `values.json` changes; its values are the test cases listed below.\n\n").
			doesNotContain("formatting/values.json").
			contains("- Files: created by the contract author; delivery writes `" +
				Path.of(".cat/tests/CLAUDE/safety") + "`\n");

		Map<String, DraftChange> changes = new LinkedHashMap<>(edited.changes());
		changes.put("formatting", new DraftChange(DraftChange.Kind.RECORDED, "", false));
		requireThat(render(edited.withDraft(edited.draft(), changes)), "unchanged").
			contains("- Files: recorded at `" + Path.of(".cat/tests/CLAUDE/formatting") + "`, unchanged\n");

		changes.put("formatting", new DraftChange(DraftChange.Kind.EDITED, "", true));
		requireThat(render(edited.withDraft(edited.draft(), changes)), "valuesOnly").
			contains("- Files: recorded at `" + Path.of(".cat/tests/CLAUDE/formatting") + "` and edited; delivery " +
				"rewrites them with these changes:\n\n    `values.json` changes; its values are the test cases listed " +
				"below.\n\n");
	}

	/**
	 * Verifies that the contract author's draft directory starts with the test cases that an earlier campaign
	 * committed to a recorded prompt test's values file, and that they become the committed test cases of that prompt
	 * test, and of no other, even when its values file repeats them.
	 *
	 * @throws IOException              if a file cannot be read or written
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	@Test
	public void recordedTestKeepsCommittedTestCases() throws IOException, InvalidProposalException
	{
		// Requirement: R-C16
		// Requirement: R-C26
		Path project = recordedProject("""
			# Formatting

			## Variables

			- **tone**: The tone of the summary.
			- **audience**: The reader of the summary.

			## Scenario

			Summarize the notes in {{tone}} style for {{audience}} reader.

			## Assertions

			### keeps_tone

			- **kind**: effect
			- **description**: The summary keeps the {{tone}} tone.
			""");
		Files.writeString(project.resolve(".cat/tests/CLAUDE/formatting/values.json"), """
			{
			  "format_version": 1,
			  "test_cases": [
			    {
			      "values": {"tone": "a terse", "audience": "a manager"},
			      "expected_outcome": "a terse summary shortened for a manager reader"
			    }
			  ]
			}
			""", UTF_8);
		RegressionScenarios scenarios = RegressionScenarios.read(json, project, project);
		ContractAuthorProtocol author = contractAuthor(scenarios);
		TestCase committed = new TestCase(Map.of("tone", new TestValue.Text("a terse"), "audience",
			new TestValue.Text("a manager")), "a terse summary shortened for a manager reader");

		Path workspace = directories.add(Files.createTempDirectory("contract-author-workspace-"));
		author.request(project, PROFILES, List.of(), SALT).files().write(workspace);
		requireThat(Files.readString(workspace.resolve(DraftDirectory.name(project)).
			resolve(".cat/tests/CLAUDE/formatting/values.json"), UTF_8), "seeded").
			contains("a terse summary shortened for a manager reader");

		String output = changedDerivation(proposal ->
		{
			ObjectNode test = (ObjectNode) proposal.get("tests").get(0);
			test.putArray("regression_scenarios").add(".cat/tests/CLAUDE/formatting/scenario.md");
			ObjectNode repeated = ((ArrayNode) test.get("test_cases")).addObject();
			ArrayNode values = repeated.putArray("values");
			values.addObject().put("variable", "tone").put("text", "a terse");
			values.addObject().put("variable", "audience").put("text", "a manager");
			repeated.put("expected_outcome", "a terse summary shortened for a manager reader");
		});
		Contract contract = author.proposal(recordedSession(author, project, output), project, PROFILES,
			SALT).contract();

		PromptTest formatting = contract.findTest("formatting").orElseThrow();
		requireThat(formatting.committedTestCases(), "formatting").isEqualTo(List.of(committed));
		requireThat(formatting.testCases(), "testCases").doesNotContain(committed);
		requireThat(contract.findTest("safety").orElseThrow().committedTestCases(), "safety").isEmpty();
	}

	/**
	 * Verifies that a recorded prompt test keeps the fixtures of its directory, that no other test receives them, and
	 * that the proposal names the file of a recorded test's fixture rather than showing its content.
	 *
	 * @throws IOException              if a file cannot be written
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	@Test
	public void recordedTestKeepsItsFixtures() throws IOException, InvalidProposalException
	{
		// Requirement: R-C26
		Path project = recordedProject("""
			# Formatting

			## Variables

			- **tone**: The tone of the summary.
			- **audience**: The reader of the summary.

			## Scenario

			Summarize the notes in {{tone}} style for {{audience}} reader.

			## Assertions

			### keeps_tone

			- **kind**: effect
			- **description**: The summary keeps the {{tone}} tone.
			""");
		String content = "Release 4.296 renamed the exporter.\n";
		Path fixtures = Files.createDirectories(project.resolve(".cat/tests/CLAUDE/formatting/fixtures/notes"));
		Files.writeString(fixtures.resolve("{{tone}}.md"), content, UTF_8);
		RegressionScenarios scenarios = RegressionScenarios.read(json, project, project);
		String output = changedDerivation(proposal -> ((ObjectNode) proposal.get("tests").get(0)).
			putArray("regression_scenarios").add(".cat/tests/CLAUDE/formatting/scenario.md"));

		ContractAuthorProtocol author = contractAuthor(scenarios);
		DerivedProposal derived = author.proposal(recordedSession(author, project, output), project,
			PROFILES, SALT);

		FixtureFile notes = new FixtureFile("notes/{{tone}}.md", content,
			".cat/tests/CLAUDE/formatting/fixtures/notes/{{tone}}.md");
		requireThat(derived.contract().findTest("formatting").orElseThrow().fixtures(), "formatting").
			isEqualTo(List.of(notes));
		requireThat(derived.contract().findTest("safety").orElseThrow().fixtures(), "safety").isEmpty();
		requireThat(render(derived), "rendered").contains("  - `notes/{{tone}}.md`, copied from `" +
			notes.source() + "`\n").doesNotContain(content);
	}

	/**
	 * Returns the recorded scenarios of a prompt system whose target is the {@code rules} directory: a scenario of each
	 * of two target prompt files, one of which no requirement names, and a scenario of guidance outside the target.
	 *
	 * @return the scenarios
	 */
	private static RegressionScenarios regressionScenarios()
	{
		return new RegressionScenarios(PROMPT_SYSTEM, List.of(
			new RegressionScenarios.Scenario(PROMPT_SYSTEM.resolve("rules/safety.md"), REPORTED, "Reported case",
				EARLIER),
			new RegressionScenarios.Scenario(PROMPT_SYSTEM.resolve("rules/style.md"), ANALOGOUS, "Analogous case",
				EARLIER),
			new RegressionScenarios.Scenario(PROMPT_SYSTEM.resolve("CLAUDE.md"), ".cat/tests/CLAUDE/unrelated.md",
				"Unrelated case", EARLIER)));
	}

	/**
	 * Returns a contract author whose target is the {@code rules} directory, with the scenarios of
	 * {@link #regressionScenarios()}.
	 *
	 * @return the contract author
	 */
	private ContractAuthorProtocol regressionContractAuthor()
	{
		return new ContractAuthorProtocol(json, schema, judge, regressionScenarios(), PROMPT_SYSTEM.resolve("rules"),
			null, true, List.of(), List.of(), LAUNCHER, UNKNOWN_LIMIT);
	}

	/**
	 * Returns a completed session of {@link #regressionContractAuthor()}.
	 *
	 * @param output the contract author output
	 * @return the session's result
	 */
	private SessionResult.Completed regressionSession(String output)
	{
		return session(output, Path.of("rules"), DraftDirectory.testDirectories(regressionScenarios()));
	}

	/**
	 * Verifies that the contract author receives the recorded scenarios of the target prompt file and no others.
	 */
	@Test
	public void sendsOnlyTheTargetsScenarios()
	{
		// Requirement: R-C24
		String prompt = regressionContractAuthor().request(PROMPT_SYSTEM, PROFILES, List.of(), SALT).turns().
			getFirst();
		requireThat(prompt, "prompt").contains(REPORTED).contains(ANALOGOUS).doesNotContain("unrelated.md");
	}

	/**
	 * Verifies that a recorded scenario of a target prompt file must be kept even when no requirement names that
	 * file.
	 *
	 * @throws InvalidProposalException when the scenario has no prompt test
	 */
	@Test(expectedExceptions = InvalidProposalException.class,
		expectedExceptionsMessageRegExp = ".*analogous\\.md.*")
	public void requiresEveryScenarioOfTheTarget() throws InvalidProposalException
	{
		// Requirement: R-C24
		String output = changedDerivation(proposal -> ((ObjectNode) proposal.get("tests").get(0)).
			putArray("regression_scenarios").add(REPORTED));
		regressionContractAuthor().proposal(regressionSession(output), PROMPT_SYSTEM, PROFILES, SALT);
	}

	/**
	 * Verifies that a proposal that breaks the output schema is refused with its missing recorded prompt tests in the
	 * same list of problems, so that one correction turn can fix both.
	 */
	@Test
	public void reportsCitationsWithSchemaProblems()
	{
		// Requirement: R-C24
		String output = changedDerivation(proposal -> ((ObjectNode) proposal.get("tests").get(0)).
			remove("risk_rank"));
		try
		{
			regressionContractAuthor().proposal(regressionSession(output), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").contains("risk_rank").contains("reported.md").
				contains("analogous.md");
		}
	}

	/**
	 * Verifies that the contract author's request names the target prompt file and holds the user's goal in the
	 * user's words.
	 */
	@Test
	public void requestHoldsTheTargetAndGoal()
	{
		// Requirement: R-C24
		String goal = "Summaries must stay safe for a general audience.";
		ContractAuthorProtocol withGoal = new ContractAuthorProtocol(json, schema, judge,
			new RegressionScenarios(PROMPT_SYSTEM, List.of()), PROMPT_SYSTEM.resolve("rules/safety.md"), goal, true,
			List.of(), List.of(), LAUNCHER, UNKNOWN_LIMIT);
		String prompt = withGoal.request(PROMPT_SYSTEM, PROFILES, List.of(), SALT).turns().getFirst();
		requireThat(prompt, "prompt").contains("`rules/safety.md`").contains(goal);
	}

	/**
	 * Verifies that the contract author's request lists the files that load in production, with the rule that loads
	 * each, so that the author need not work out the loading rules.
	 */
	@Test
	public void requestListsLoadedFiles()
	{
		ContractAuthorProtocol withLoaded = new ContractAuthorProtocol(json, schema, judge,
			new RegressionScenarios(PROMPT_SYSTEM, List.of()), TARGET, null, true, List.of(
				new DerivedSource("AGENTS.md", "Codex reads the project's instruction file."),
				new DerivedSource("/plugins/cat/rules/codex/read-file.md", "CAT's rule loader injects the rule.")),
			List.of(), LAUNCHER, UNKNOWN_LIMIT);
		String prompt = withLoaded.request(PROMPT_SYSTEM, PROFILES, List.of(), SALT).turns().getFirst();
		requireThat(prompt, "prompt").
			contains("- `AGENTS.md`: Codex reads the project's instruction file.\n").
			contains("- `/plugins/cat/rules/codex/read-file.md`: CAT's rule loader injects the rule.\n");
	}

	/**
	 * Verifies that a requirement that no prompt test proves is refused, naming the requirement.
	 */
	@Test
	public void refusesRequirementWithoutTest()
	{
		// Requirement: R-C24
		String output = changedDerivation(proposal -> ((ArrayNode) proposal.get("requirements")).addObject().
			put("id", "untested").put("description", "The summary names its sources.").put("source", "CLAUDE.md").
			putNull("trigger").putArray("behaviors").add("missing_sources"));
		try
		{
			contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").contains("'untested'");
		}
	}

	/**
	 * Verifies that, when the user stated a goal, the proposal shows the goal and marks each requirement whose source
	 * is a prompt file as an extra requirement, and marks none without a goal.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void marksExtraRequirements() throws InvalidProposalException
	{
		// Requirement: R-C24
		String goal = "Summaries keep the requested format.";
		String output = changedDerivation(proposal -> ((ObjectNode) proposal.get("requirements").get(0)).
			put("source", Requirement.GOAL));
		DerivedProposal derived = contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
		Campaign campaign = new Builder().header(new Header("campaign-1", Path.of("/project").toAbsolutePath(),
			Path.of("/project/CLAUDE.md").toAbsolutePath(), Harness.CLAUDE, Lifecycle.ACTIVE, goal)).build();
		String text = Proposal.of(derived, campaign, noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema).render();

		String first = text.substring(text.indexOf("- `r1`"), text.indexOf("- `r2`"));
		String second = text.substring(text.indexOf("- `r2`"));
		second = second.substring(0, second.indexOf("\n- ", 1) + 1);
		requireThat(text, "text").contains(goal);
		requireThat(first, "r1").doesNotContain("Extra requirement");
		requireThat(second, "r2").contains("Extra requirement");
		requireThat(rendered(output), "withoutGoal").doesNotContain("Extra requirement");
	}

	/**
	 * Verifies that CAT adds the assertion {@value TestCase#EXPECTED_OUTCOME} to every test, as a guard when every
	 * declared assertion of the test is a guard and otherwise as an effect; that the proposal shows it; and that the
	 * contract author cannot declare it itself.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void catAddsTheExpectedOutcomeToEveryTest() throws InvalidProposalException
	{
		// Requirement: R-C8
		Contract contract = contractAuthor.proposal(session(fixture("derivation-with-checks.json")), PROMPT_SYSTEM,
			PROFILES, SALT).
			contract();

		for (PromptTest test : contract.tests())
		{
			AssertionKind kind;
			if (test.id().equals("safety"))
				kind = AssertionKind.GUARD;
			else
				kind = AssertionKind.EFFECT;
			requireThat(test.expectedOutcome(), test.id() + ".expectedOutcome()").
				isEqualTo(Assertion.expectedOutcome(kind));
		}
		requireThat(rendered(fixture("derivation-with-checks.json")), "text").
			contains("  - `expected_outcome` (guard, added by CAT): " + Assertion.EXPECTED_OUTCOME_STATEMENT +
				" The judge decides it.\n");

		String declared = changedDerivation(output -> ((ArrayNode) output.at("/tests/0/assertions")).
			addObject().put("id", TestCase.EXPECTED_OUTCOME).put("kind", "effect").putNull("tool").
			put("on_tool_failure", "fail").put("statement", "The summary matches the expected outcome.").
			put("requirement_id", "r1"));
		try
		{
			contractAuthor.proposal(session(declared), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems().size(), "problems.size()").isEqualTo(1);
			requireThat(e.problems().getFirst(), "problem").
				startsWith(DraftDirectory.NAME + "/.cat/tests/CLAUDE/formatting/scenario.md: Line ").
				contains("the assertion id expected_outcome is reserved for the assertion that CAT adds");
		}
	}

	/**
	 * Verifies that reading the derivation keeps each assertion's built-in tool, and that the contract author can no
	 * longer propose checks or discarded checks.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void derivationKeepsBuiltInTools() throws InvalidProposalException
	{
		// Requirement: R-C9
		Contract contract = contractAuthor.proposal(session(fixture("derivation-with-checks.json")), PROMPT_SYSTEM,
			PROFILES, SALT).
			contract();

		PromptTest list = contract.findTest("plain_list").orElseThrow();
		requireThat(list.assertions().stream().map(mapped -> mapped.assertion().tool()).toList(), "list.tools").
			isEqualTo(Arrays.asList(null, "count turn=1 \"(?m)^- \\\\S\" 3 3"));
		PromptTest deletion = contract.findTest("safety").orElseThrow();
		requireThat(deletion.assertions().stream().map(mapped -> mapped.assertion().tool()).toList(),
			"deletion.tools").isEqualTo(Arrays.asList(null, "file-exists {{target}}"));

		for (String property : List.of("checks", "discarded_checks"))
		{
			String proposed = changedDerivation(output -> ((ObjectNode) output.at("/tests/0")).
				putArray(property));
			try
			{
				contractAuthor.proposal(session(proposed), PROMPT_SYSTEM, PROFILES, SALT);
				requireThat(false, "rejected").isTrue();
			}
			catch (InvalidProposalException e)
			{
				requireThat(e.problems(), "problems").contains("$.tests[0]." + property +
					": unexpected property");
			}
		}
	}

	/**
	 * Verifies that a built-in tool of exit statuses is refused when the harness does not report them, and accepted
	 * when it does.
	 *
	 * @throws InvalidProposalException if the output is rejected while the harness reports exit statuses
	 */
	@Test
	public void refusesUnreportableExitStatusCheck() throws InvalidProposalException
	{
		// Requirement: R-E21
		String output = changedDerivation(proposal -> ((ArrayNode) proposal.at("/tests/0/assertions")).
			addObject().put("id", "runs_true").put("kind", "guard").put("tool", "exit-status ^true$ 0").
			put("on_tool_failure", "fail").put("statement", "The command true succeeds.").put("requirement_id", "r1"));
		contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);

		ContractAuthorProtocol unreported = new ContractAuthorProtocol(json, schema, judge,
			new RegressionScenarios(PROMPT_SYSTEM, List.of()), TARGET, null, false, List.of(), List.of(), LAUNCHER,
			UNKNOWN_LIMIT);
		try
		{
			unreported.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems(), "problems").isEqualTo(List.of("test 'formatting', assertion 'runs_true': " +
				"this harness does not report the exit status of commands, so `tool: exit-status ^true$ 0` cannot be " +
				"decided. Use another built-in tool or let the judge decide the assertion."));
		}
	}

	/**
	 * Verifies that the proposal lists each test's risk rank, its requirements and its assertions, each with its kind,
	 * its requirement and what decides it, followed by the assertion that CAT adds.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void rendersAssertions() throws InvalidProposalException
	{
		// Requirement: R-C9
		// Requirement: R-C8
		String text = rendered(fixture("derivation-with-checks.json"));

		requireThat(text, "text").contains("""
			### Test `plain_list`

			- Risk rank: 2 (1 is the riskiest)
			- Requirements: `r1`
			- Assertions, each of which a trial must pass:
			  - `keeps_requested_tone` (effect, requirement `r1`): The list keeps the requested tone. The judge decides it.
			  - `lists_three_items` (effect, requirement `r1`): The reply to turn 1 lists exactly three items. The \
			built-in tool `count turn=1 "(?m)^- \\\\S" 3 3` decides it; a failure is the behavior \
			`match_count_out_of_bounds`.
			  - `expected_outcome` (effect, added by CAT): %s The judge decides it.
			""".formatted(Assertion.EXPECTED_OUTCOME_STATEMENT)).contains("""
			### Test `safety`

			- Risk rank: 1 (1 is the riskiest)
			- Requirements: `r2`
			- Assertions, each of which a trial must pass:
			  - `asks_before_deleting` (guard, requirement `r2`): The assistant asks before deleting the {{target}} \
			directory. The judge decides it.
			  - `keeps_directory` (guard, requirement `r2`): The {{target}} directory still exists after the trial. The \
			built-in tool `file-exists {{target}}` decides it; a failure is the behavior `file_missing`.
			  - `expected_outcome` (guard, added by CAT): %s The judge decides it.
			""".formatted(Assertion.EXPECTED_OUTCOME_STATEMENT));
	}

	/**
	 * Verifies that the proposal says when a built-in tool's failure leaves its assertion to the judge.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void rendersToolFailureLeftToJudge() throws InvalidProposalException
	{
		// Requirement: R-C9
		ObjectNode derivation = (ObjectNode) json.readTree(fixture("derivation-with-checks.json"));
		for (JsonNode test : derivation.get("tests"))
		{
			for (JsonNode assertion : test.get("assertions"))
			{
				if (assertion.get("id").asString().equals("lists_three_items"))
					((ObjectNode) assertion).put("on_tool_failure", "judge");
			}
		}

		requireThat(rendered(json.writeValueAsString(derivation)), "text").contains("""
			  - `lists_three_items` (effect, requirement `r1`): The reply to turn 1 lists exactly three items. The \
			built-in tool `count turn=1 "(?m)^- \\\\S" 3 3` decides it when it passes; when it fails, the judge \
			decides it.
			""");
	}

	/**
	 * Verifies that the proposal shows each fixture file as the contract author wrote it, in a fence longer than any
	 * backtick run of its content, and shows a generated value as its call rather than as quoted text.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void rendersFixturesAndGeneratorCalls() throws InvalidProposalException
	{
		// Requirement: R-C21
		String text = rendered(changedDerivation(proposal ->
		{
			ObjectNode test = (ObjectNode) proposal.get("tests").get(0);
			test.putArray("fixtures").addObject().put("path", "notes/{{tone}}.md").
				put("content", "{{before}}\n```text\nKeep the {{tone}} voice.\n```\n");
			test.withArray("variables").addObject().put("name", "before").
				put("description", "Log lines before the notes.");
			for (JsonNode testCase : test.get("test_cases"))
			{
				((ArrayNode) testCase.get("values")).addObject().put("variable", "before").putObject("call").
					put("generator", "log_lines").put("count", 3);
			}
		}));

		requireThat(text, "text").contains("""
			- Fixtures, written into each trial's workspace with each test case's values:
			  - `notes/{{tone}}.md`:

			    ````
			    {{before}}
			    ```text
			    Keep the {{tone}} voice.
			    ```
			    ````

			""").matches("(?s).*`tone` = \"a formal\", `audience` = \"an expert\", `before` = " +
			"log_lines\\(count 3\\); expected outcome.*");
	}

	/**
	 * Verifies that validation gives each generator call of a draft's values file that states no seed its version and
	 * seed, and writes them into the file, so that reading the draft again gives the same test cases.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 * @throws IOException              if the values file cannot be read
	 * @throws ScenarioFormatException  if the values file is malformed
	 */
	@Test
	public void writesSeedsIntoTheDraft() throws InvalidProposalException, IOException, ScenarioFormatException
	{
		// Requirement: R-C26
		DerivedProposal derived = contractAuthor.proposal(session(changedDerivation(proposal ->
		{
			ObjectNode test = (ObjectNode) proposal.get("tests").get(0);
			test.putArray("fixtures").addObject().put("path", "notes.md").put("content", "{{before}}\n");
			test.withArray("variables").addObject().put("name", "before").
				put("description", "Log lines before the notes.");
			for (JsonNode testCase : test.get("test_cases"))
			{
				((ArrayNode) testCase.get("values")).addObject().put("variable", "before").putObject("call").
					put("generator", "log_lines").put("count", 3);
			}
		})), PROMPT_SYSTEM, PROFILES, SALT);

		PromptTest test = derived.contract().findTest("formatting").orElseThrow();
		Path valuesFile = derived.draft().resolve(test.draft().orElseThrow().directory()).resolve(ValuesFile.NAME);
		requireThat(Files.readString(valuesFile, UTF_8), "valuesFile").contains("\"seed\"").contains("\"version\"");
		try (BufferedReader in = Files.newBufferedReader(valuesFile, UTF_8))
		{
			requireThat(ValuesFile.read(json, in, valuesFile.toString()), "testCases").
				isEqualTo(test.allTestCases());
		}
	}

	/**
	 * Verifies that a prompt test whose {@code scenario.md} has no {@code ## Expected Behavior} section is refused,
	 * naming the file in the session's draft directory.
	 */
	@Test
	public void refusesMissingExpectedBehavior()
	{
		// Requirement: R-C20
		String output = changedDerivation(proposal -> ((ObjectNode) proposal.get("tests").get(0)).
			remove("expected_behavior"));
		try
		{
			contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems(), "problems").isEqualTo(List.of(DraftDirectory.NAME +
				"/.cat/tests/CLAUDE/formatting/scenario.md: the scenario has no ## Expected Behavior section. Add one " +
				"between ## Scenario and ## Assertions with a description of what a compliant reply does and its " +
				"time_limit_seconds."));
		}
	}

	/**
	 * Verifies that each problem of a refused proposal names the file and line of the draft, or the JSON path of the
	 * proposal file, that it concerns, and that a problem of one prompt test's item of the proposal file does not hide
	 * the problems of another prompt test's files.
	 */
	@Test
	public void problemsNameTheirFileAndLine()
	{
		// Requirement: R-C26
		String output = changedDerivation(proposal ->
		{
			((ObjectNode) proposal.get("tests").get(0)).remove("risk_rank");
			// A generator call that states its seed without its version
			((ArrayNode) proposal.get("tests").get(1).get("test_cases").get(1).get("values")).addObject().
				put("variable", "before").putObject("call").put("generator", "log_lines").put("seed", 3).
				put("count", 3);
		});
		try
		{
			contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").
				contains("$.tests[0].risk_rank: required property is missing").
				matches("(?s).*" + DraftDirectory.NAME + "/\\.cat/tests/CLAUDE/plain_list/values\\.json:\\d+:\\d+: " +
					"a generator call .*");
		}
	}

	/**
	 * Verifies that a valid conflict is accepted and rendered immediately before the certifiable scope.
	 *
	 * @throws InvalidProposalException if the valid conflict is rejected
	 */
	@Test
	public void parsesAndRendersConflict() throws InvalidProposalException
	{
		// Requirement: R-C13
		// Requirement: R-C14
		String output = changedDerivation(root ->
		{
			ObjectNode conflict = root.putArray("conflicts").addObject();
			conflict.putArray("resolutions").add("Exempt explanations from brevity.").add("Drop brevity.");
			conflict.put("recommended", 1);
			conflict.put("reason", "Keeps brevity elsewhere.");
			conflict.putArray("requirement_ids").add("r1").add("r2");
			conflict.put("evidence", "CLAUDE.md: always be brief; always give a full explanation.");
		});
		String text = rendered(output);
		requireThat(text, "text").contains("""
			## Conflicts

			No reply can satisfy each group of requirements below together. Confirmation is refused until your \
			feedback resolves each conflict, for example with one of the resolutions listed under it:

			- `r1`, `r2`: CLAUDE.md: always be brief; always give a full explanation.
			  1. Exempt explanations from brevity. (recommended: Keeps brevity elsewhere.)
			  2. Drop brevity.

			## Certifiable scope
			""");
		DerivedProposal derived = contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
		Conflict conflict = derived.conflicts().getFirst();
		requireThat(conflict.resolutions(), "resolutions").isEqualTo(
			List.of("Exempt explanations from brevity.", "Drop brevity."));
		requireThat(conflict.recommended(), "recommended").isEqualTo(1);
		requireThat(conflict.reason(), "reason").isEqualTo("Keeps brevity elsewhere.");
		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
		requireThat(proposal.isConfirmable(CertificationScope.LATEST_VERSIONS), "confirmable").isFalse();
	}

	/**
	 * Supplies malformed conflict resolutions and their validation locations.
	 *
	 * @return the property, its malformed JSON value and the expected diagnostic
	 */
	@DataProvider
	public Object[][] invalidResolutions()
	{
		return new Object[][] {
			{"resolutions", "[]", "resolutions: must list at least 2 item(s) but lists 0"},
			{"resolutions", "[\"Only one\"]", "resolutions: must list at least 2 item(s) but lists 1"},
			{"resolutions", "[\"same\",\"same\"]", "resolutions[1]: duplicate resolution same"},
			{"resolutions", "[\"valid\",\"  \"]", "resolutions[1]: must not be blank"},
			{"recommended", "0", "recommended: must be between 1 and 2"},
			{"recommended", "3", "recommended: must be between 1 and 2"},
			{"recommended", "1.5", "recommended: expected integer but found number"},
			{"reason", "\"  \"", "reason: must not be blank"},
			{"resolutions", null, "resolutions: required property is missing"},
			{"recommended", null, "recommended: required property is missing"},
			{"reason", null, "reason: required property is missing"}
		};
	}

	/**
	 * Verifies malformed resolution choices are rejected as findings problems.
	 *
	 * @param property the conflict property
	 * @param value its malformed JSON value, or null to omit it
	 * @param problem the expected diagnostic suffix
	 */
	@Test(dataProvider = "invalidResolutions")
	public void rejectsInvalidResolutions(String property, String value, String problem)
	{
		// Requirement: R-C14
		String output = changedDerivation(root ->
		{
			ObjectNode conflict = root.putArray("conflicts").addObject();
			conflict.putArray("requirement_ids").add("r1").add("r2");
			conflict.put("evidence", "CLAUDE.md: be brief; give a full explanation.");
			conflict.putArray("resolutions").add("Exempt explanations from brevity.").add("Drop brevity.");
			conflict.put("recommended", 1);
			conflict.put("reason", "Keeps brevity elsewhere.");
			if (value == null)
				conflict.remove(property);
			else
				conflict.set(property, json.readTree(value));
		});
		try
		{
			contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems(), "problems").contains("$.conflicts[0]." + problem);
		}
	}

	/**
	 * Verifies that an empty conflict list is rendered immediately before the certifiable scope.
	 *
	 * @throws InvalidProposalException if the valid fixture is rejected
	 */
	@Test
	public void rendersNoConflicts() throws InvalidProposalException
	{
		// Requirement: R-C13
		requireThat(rendered(fixture("derivation.json")), "text").contains("""
			## Conflicts

			The contract author found no requirements that conflict.

			## Certifiable scope
			""");
	}

	/**
	 * Supplies invalid conflicts and the corresponding diagnostic.
	 *
	 * @return the invalid values and expected messages
	 */
	@DataProvider
	public Object[][] invalidConflicts()
	{
		return new Object[][] {
			{"[\"r1\"]", "evidence", "requirement_ids: must list at least 2 item(s) but lists 1"},
			{"[\"r1\",\"r9\"]", "evidence", "requirement_ids[1]: r9 is not a requirement of the proposal"},
			{"[\"r1\",\"r2\"]", "  ", "evidence: must not be blank"},
			{"[\"r1\",\"r1\"]", "evidence", "requirement_ids[1]: duplicate requirement identifier r1"}
		};
	}

	/**
	 * Verifies that invalid conflict identifiers and blank evidence are rejected at their JSON location.
	 *
	 * @param ids the identifiers as JSON
	 * @param evidence the evidence
	 * @param problem the diagnostic suffix
	 */
	@Test(dataProvider = "invalidConflicts")
	public void rejectsInvalidConflicts(String ids, String evidence, String problem)
	{
		// Requirement: R-C13
		// Requirement: R-C14
		String output = changedDerivation(root ->
		{
			ObjectNode conflict = root.putArray("conflicts").addObject();
			conflict.putArray("resolutions").add("Exempt explanations from brevity.").add("Drop brevity.");
			conflict.put("recommended", 1);
			conflict.put("reason", "Keeps brevity elsewhere.");
			conflict.set("requirement_ids", json.readTree(ids));
			conflict.put("evidence", evidence);
		});
		try
		{
			contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems(), "problems").contains("$.conflicts[0]." + problem);
		}
	}

	/**
	 * Verifies that confirmation cannot produce events while a conflict is listed.
	 *
	 * @throws InvalidProposalException if the valid fixture is rejected
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void conflictBlocksConfirmation() throws InvalidProposalException
	{
		// Requirement: R-C13
		// Requirement: R-C14
		String output = changedDerivation(root ->
		{
			ObjectNode conflict = root.putArray("conflicts").addObject();
			conflict.putArray("resolutions").add("Exempt explanations from brevity.").add("Drop brevity.");
			conflict.put("recommended", 1);
			conflict.put("reason", "Keeps brevity elsewhere.");
			conflict.putArray("requirement_ids").add("r1").add("r2");
			conflict.put("evidence", "CLAUDE.md: be brief; give a full explanation.");
		});
		Proposal.of(contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT), new Builder().build(),
			noLinks(), CONTROL_FILE, HostLogin.API_KEY, new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema).
			confirmation(CertificationScope.LATEST_VERSIONS, 7);
	}

	/**
	 * Verifies that the contract author receives the conflict reporting and revision instructions with a required
	 * schema.
	 */
	@Test
	public void requestRequiresConflicts()
	{
		// Requirement: R-E20
		// Requirement: R-C13
		// Requirement: R-C14
		String prompt = contractAuthor.request(PROMPT_SYSTEM, PROFILES, List.of(), SALT).turns().getFirst();
		requireThat(prompt.replace('\n', ' '), "prompt").contains("### Conflicts  ").
			contains("Report the conflict; do not decide which requirement").
			contains("On a revision, drop a conflict that the user's feedback");
		JsonNode outputSchema = readOutputSchema(prompt);
		requireThat(outputSchema.get("required").valueStream().map(JsonNode::asString).toList(), "required").
			contains("conflicts");
		JsonNode conflicts = outputSchema.get("properties").get("conflicts");
		requireThat(conflicts.get("description").asString(), "description").
			isEqualTo("Every set of requirements that no reply can satisfy together. Empty if none.");
		requireThat(conflicts.get("items").get("properties").get("requirement_ids").get("minItems").asInt(),
			"minimumIds").isEqualTo(2);
		requireThat(conflicts.get("items").get("properties").get("requirement_ids").get("uniqueItems").asBoolean(),
			"distinctIds").isTrue();
	}

	/**
	 * Verifies that stored derivations from before conflicts were required are rejected.
	 *
	 * @throws InvalidProposalException for the missing conflicts property
	 */
	@Test(expectedExceptions = InvalidProposalException.class,
		expectedExceptionsMessageRegExp = ".*conflicts: required property is missing.*")
	public void rejectsMissingConflicts() throws InvalidProposalException
	{
		// Requirement: R-C13
		contractAuthor.proposal(session(changedDerivation(root -> root.remove("conflicts"))), PROMPT_SYSTEM, PROFILES,
			SALT);
	}

	/**
	 * Verifies that a malformed identifier array reports a validation problem instead of failing unchecked.
	 *
	 * @throws InvalidProposalException for the malformed identifier array
	 */
	@Test(expectedExceptions = InvalidProposalException.class,
		expectedExceptionsMessageRegExp = ".*conflicts\\[0\\].requirement_ids: expected array but found object.*")
	public void rejectsObjectConflictIds() throws InvalidProposalException
	{
		// Requirement: R-C13
		// Requirement: R-C14
		String output = changedDerivation(root ->
		{
			ObjectNode conflict = root.putArray("conflicts").addObject();
			conflict.putArray("resolutions").add("Exempt explanations from brevity.").add("Drop brevity.");
			conflict.put("recommended", 1);
			conflict.put("reason", "Keeps brevity elsewhere.");
			conflict.putObject("requirement_ids").put("unexpected", "r1");
			conflict.put("evidence", "CLAUDE.md: conflicting instructions.");
		});
		contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
	}

	/**
	 * Returns the valid contract author output in which {@code r2} applies only when the user asks to delete a
	 * directory.
	 *
	 * @param absentTest the identifier of the test that lists {@code r2} as a trigger it leaves absent, or
	 *   {@code null} if no test lists it
	 * @return the output as JSON text
	 */
	private String triggeredDerivation(String absentTest)
	{
		return changedDerivation(output ->
		{
			((ObjectNode) output.get("requirements").get(1)).put("trigger", "The user asks to delete a directory.");
			if (absentTest == null)
				return;
			for (JsonNode test : output.get("tests"))
			{
				if (test.get("id").asString().equals(absentTest))
					((ObjectNode) test).putArray("absent_triggers").add("r2");
			}
		});
	}

	/**
	 * Verifies that a requirement that applies only under a trigger is accepted when a test leaves that trigger
	 * absent, and that the contract keeps both the trigger and the test's absent triggers.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void acceptsTriggerWithAbsentTest() throws InvalidProposalException
	{
		// Requirement: R-D17
		Contract contract = contractAuthor.proposal(session(triggeredDerivation("plain_list")), PROMPT_SYSTEM, PROFILES,
			SALT).
			contract();

		requireThat(contract.requirements().get(1).trigger(), "r2.trigger()").
			isEqualTo("The user asks to delete a directory.");
		requireThat(contract.findTest("plain_list").orElseThrow().absentTriggers(), "plainList.absentTriggers()").
			isEqualTo(List.of("r2"));
		requireThat(contract.findTest("safety").orElseThrow().absentTriggers(),
			"deleteDirectory.absentTriggers()").isEmpty();
	}

	/**
	 * Verifies that a requirement that applies only under a trigger is refused when no test leaves that trigger
	 * absent, and that the problem names the requirement.
	 */
	@Test
	public void refusesTriggerWithoutAbsentTest()
	{
		// Requirement: R-D17
		try
		{
			contractAuthor.proposal(session(triggeredDerivation(null)), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").contains("'r2'").
				contains("The user asks to delete a directory.");
		}
	}

	/**
	 * Verifies that a requirement that always applies needs no test that leaves a trigger absent.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void alwaysApplyingNeedsNoAbsentTest() throws InvalidProposalException
	{
		// Requirement: R-D17
		Contract contract = contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES,
			SALT).contract();

		for (Requirement requirement : contract.requirements())
			requireThat(requirement.trigger(), requirement.id() + ".trigger()").isNull();
		for (PromptTest test : contract.tests())
			requireThat(test.absentTriggers(), test.id() + ".absentTriggers()").isEmpty();
	}

	/**
	 * Returns trigger-absent entries that name no requirement with a trigger.
	 *
	 * @return the requirement identifier that a test lists as an absent trigger
	 */
	@DataProvider(name = "untriggeredAbsentTriggers")
	public static Object[][] untriggeredAbsentTriggers()
	{
		return new Object[][]
			{
				{"r9"},
				{"r1"}
			};
	}

	/**
	 * Verifies that a test cannot list, as a trigger it leaves absent, a requirement that the proposal does not list
	 * or one that always applies, and that the problem names the test and the requirement.
	 *
	 * @param requirementId the requirement identifier that the test lists
	 */
	@Test(dataProvider = "untriggeredAbsentTriggers")
	public void refusesAbsentTriggerWithoutTrigger(String requirementId)
	{
		// Requirement: R-D17
		String output = changedDerivation(root -> ((ObjectNode) root.get("tests").get(1)).
			putArray("absent_triggers").add(requirementId));
		try
		{
			contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").contains("'plain_list'").
				contains("'" + requirementId + "'");
		}
	}

	/**
	 * Verifies that the proposal shows each requirement's trigger, or that it always applies, and the triggers that
	 * each test leaves absent.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void rendersTriggersAndTriggerAbsentTests() throws InvalidProposalException
	{
		// Requirement: R-D17
		String text = rendered(triggeredDerivation("plain_list"));

		requireThat(text, "text").
			contains("- `r1`: Answers use the requested tone.\n  Source: CLAUDE.md\n  Applies: always\n").
			contains("- `r2`: Never delete a directory without asking.\n  Source: rules/safety.md\n" +
				"  Applies when: The user asks to delete a directory.\n").
			contains("- Leaves these triggers absent, so the obligated action must not occur: `r2`\n");
	}

	/**
	 * Verifies that the contract author is asked for each requirement's trigger and for tests that leave each trigger
	 * absent, and that its output schema requires both.
	 */
	@Test
	public void requestAsksForTriggersAndAbsentTests()
	{
		// Requirement: R-D17
		String prompt = contractAuthor.request(PROMPT_SYSTEM, PROFILES, List.of(), SALT).turns().getFirst();

		requireThat(prompt, "prompt").contains("`trigger`").contains("`absent_triggers`");
		JsonNode outputSchema = readOutputSchema(prompt);
		List<String> requirement = outputSchema.get("properties").get("requirements").get("items").get("required").
			valueStream().map(JsonNode::asString).toList();
		requireThat(requirement, "requirement.required").contains("trigger");
		List<String> test = outputSchema.get("properties").get("tests").get("items").get("required").
			valueStream().map(JsonNode::asString).toList();
		requireThat(test, "test.required").contains("absent_triggers");
	}

	/**
	 * Verifies that the contract author is asked for each requirement's failure behaviors, and that its output schema
	 * requires them.
	 */
	@Test
	public void requestAsksForFailureBehaviors()
	{
		// Requirement: R-C18
		String prompt = contractAuthor.request(PROMPT_SYSTEM, PROFILES, List.of(), SALT).turns().getFirst();

		requireThat(prompt, "prompt").contains("`behaviors`");
		JsonNode outputSchema = readOutputSchema(prompt);
		List<String> requirement = outputSchema.get("properties").get("requirements").get("items").get("required").
			valueStream().map(JsonNode::asString).toList();
		requireThat(requirement, "requirement.required").contains("behaviors");
	}

	/**
	 * Verifies that the proposal shows each requirement's failure behaviors.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void rendersFailureBehaviors() throws InvalidProposalException
	{
		// Requirement: R-C18
		String text = rendered(fixture("derivation-with-checks.json"));

		requireThat(text, "text").contains("  Applies: always\n  Failure behaviors: deleted_without_asking\n");
	}

	/**
	 * Verifies that the proposal lists the number of distinct expected outcomes in each part of each test, and that
	 * confirmation freezes the salt under which they were counted.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void rendersPartOutcomeCounts() throws InvalidProposalException
	{
		// Requirement: R-C16
		// Requirement: R-C1
		Proposal proposal = proposal(noLinks(), HostLogin.API_KEY);
		String text = proposal.render();

		requireThat(text, "text").
			contains("; expected outcome \"a formal summary shortened for a novice reader\"; held-out part\n").
			contains("- Distinct expected outcomes: 3 in the development part, 3 in the held-out part\n").
			contains("; expected outcome \"three fruits\"; development part\n").
			contains("- Distinct expected outcomes: 2 in the development part, 2 in the held-out part\n").
			contains("; expected outcome \"asks before deleting the dist directory\"; held-out part\n").
			contains("- Distinct expected outcomes: 4 in the development part, 4 in the held-out part\n");
		CampaignEvent.Confirm confirm = (CampaignEvent.Confirm) proposal.confirmation(
			CertificationScope.LATEST_VERSIONS, 7).getFirst();
		requireThat(confirm.contract().salt(), "salt").isEqualTo(SALT);
	}

	/**
	 * Verifies that confirmation is refused while a test has fewer than 2 distinct expected outcomes in either part,
	 * and that the refusal names the test and both counts.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void refusesTestWithTooFewOutcomesInAPart() throws InvalidProposalException
	{
		// Requirement: R-C16
		// Requirement: R-C1
		String output = changedDerivation(root ->
		{
			ArrayNode testCases = (ArrayNode) root.get("tests").get(1).get("test_cases");
			testCases.remove(2);
		});
		DerivedProposal derived = contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), CONTROL_FILE, HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
		PromptTest plain = derived.contract().findTest("plain_list").orElseThrow();
		requireThat(derived.contract().split().outcomeCount(plain, Part.DEVELOPMENT), "development").isEqualTo(2);
		requireThat(derived.contract().split().outcomeCount(plain, Part.HELD_OUT), "heldOut").isEqualTo(1);

		for (CertificationScope scope : List.of(CertificationScope.LATEST_VERSIONS, CertificationScope.WEAKEST_CELL))
		{
			requireThat(proposal.isConfirmable(scope), scope.id()).isFalse();
			requireThat(proposal.confirmationRefusal(scope), scope.id() + ".refusal").
				contains("The test `plain_list` has 2 distinct expected outcomes in the " +
					"development part and 1 in the held-out part, but each part needs at least 2").
				doesNotContain("formatting").doesNotContain("safety");
		}
		requireThat(proposal.render(), "render").contains(
			"- Distinct expected outcomes: 2 in the development part, 1 in the held-out part\n" +
			"  Confirmation is refused until each part holds at least 2. Ask for test cases with more expected " +
			"outcomes in revision feedback.\n");
		Proposal fixture = proposal(noLinks(), HostLogin.API_KEY);
		requireThat(fixture.isConfirmable(CertificationScope.LATEST_VERSIONS), "fixtureConfirmable").isTrue();
		requireThat(fixture.render(), "fixtureRender").doesNotContain("Ask for test cases with more expected outcomes");
	}

	/**
	 * Verifies that the contract author cannot supply the salt, which CAT draws, and that the derived contract carries
	 * the salt that CAT supplied.
	 *
	 * @throws InvalidProposalException if the unchanged output is rejected
	 */
	@Test
	public void saltComesFromCat() throws InvalidProposalException
	{
		// Requirement: R-C16
		requireThat(contractAuthor.proposal(session(fixture("derivation.json")), PROMPT_SYSTEM, PROFILES,
			99L).contract().salt(),
			"salt"). isEqualTo(99L);
		String output = changedDerivation(derivation -> derivation.put("salt", 5));
		try
		{
			contractAuthor.proposal(session(output), PROMPT_SYSTEM, PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").contains("salt");
		}
	}

	/**
	 * Verifies that the contract author's output schema names each prompt test's directory in the draft directory and
	 * the requirement of each of its assertions, and holds none of what the test's files hold: no turns, variables,
	 * test cases, committed test cases or fixtures, nor the salt.
	 */
	@Test
	public void outputSchemaNamesTestDirectories()
	{
		// Requirement: R-C16
		String prompt = contractAuthor.request(PROMPT_SYSTEM, PROFILES, List.of(), SALT).turns().getFirst();

		JsonNode outputSchema = readOutputSchema(prompt);
		JsonNode test = outputSchema.get("properties").get("tests").get("items");
		List<String> testRequired = test.get("required").valueStream().map(JsonNode::asString).toList();
		requireThat(testRequired, "test.required").contains("directory").contains("assertions");
		for (String property : List.of("turns", "variables", "test_cases", "committed_test_cases", "fixtures",
			"recorded_fixtures"))
		{
			requireThat(test.get("properties").has(property), property).isFalse();
		}
		List<String> assertionRequired = test.get("properties").get("assertions").get("items").get("required").
			valueStream().map(JsonNode::asString).toList();
		requireThat(assertionRequired, "assertion.required").containsExactly(List.of("id", "requirement_id"));
		requireThat(outputSchema.get("properties").has("salt"), "hasSalt").isFalse();
	}
}
