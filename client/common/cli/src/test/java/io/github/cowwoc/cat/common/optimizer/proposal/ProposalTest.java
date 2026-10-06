package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Builder;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationProcedure;
import io.github.cowwoc.cat.common.optimizer.campaign.ContractIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.contract.CapabilityRanking;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.DiscardedCheck;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.VariationRule;
import io.github.cowwoc.cat.common.optimizer.contract.VariationValue;
import io.github.cowwoc.cat.common.optimizer.execution.FixedCalibrator;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.report.RemainingWork;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibrationException;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link DeriverProtocol} and {@link Proposal}: deriver output in, the complete proposal out.
 */
public final class ProposalTest
{
	private static final Path PROMPT_SYSTEM = Path.of("/staged/incumbent");
	private static final String OBSERVED = "hook: .claude/hooks/session-start.sh\nskill: release-notes";
	private static final TrialProfile ROLE = new TrialProfile("role-model", "xhigh");
	private static final ProfileEvidence PROFILES = new ProfileEvidence(ContractFixtures.ONE_PROFILE,
		ContractFixtures.ranking(ContractFixtures.ONE_PROFILE), Map.of());
	/**
	 * The salt of the derived proposals.
	 */
	private static final long SALT = ContractFixtures.SALT;
	private final JsonMapper json = JsonMapper.builder().build();
	private final ProposalSchema schema = new ProposalSchema(json);
	private final JudgeTemplate judge = new JudgeTemplate(json, ROLE);
	private final DeriverProtocol deriver = new DeriverProtocol(json, schema, judge,
		new RegressionScenarios(PROMPT_SYSTEM, List.of()));

	/**
	 * Creates the test fixture.
	 */
	public ProposalTest()
	{
	}

	/**
	 * Returns the text of a deriver output fixture.
	 *
	 * @param name the file name under {@code optimizer/proposal/}
	 * @return the text
	 */
	private static String fixture(String name)
	{
		try (InputStream in = ProposalTest.class.getResourceAsStream("/optimizer/proposal/" + name))
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
	 * Returns a completed deriver session whose final response is the given text.
	 *
	 * @param response the final response
	 * @return the session's result
	 */
	private static SessionResult.Completed session(String response)
	{
		return new SessionResult.Completed(Path.of("/runs/deriver/artifacts/transcript.jsonl"),
			List.of(List.of("I will inspect the prompt system.", response)), Path.of("/runs/deriver/project"), "model",
				null,
			null, List.of(), OBSERVED, List.of());
	}

	/**
	 * Verifies that only the final message of the last turn can supply a structured session result.
	 */
	@Test
	public void structuredOutputUsesLastFinalMessage()
	{
		// Requirement: R-C12
		JsonResponse responses = new JsonResponse(json);
		SessionResult.Completed earlier = session("{\"answer\":1}");
		SessionResult.Completed invalidFinal = new SessionResult.Completed(earlier.transcript(),
			List.of(List.of("{\"answer\":1}"), List.of("{\"answer\":2}", "No structured output.")),
			earlier.effects(), earlier.model(), null, null, List.of(), OBSERVED, List.of());
		requireThat(responses.parseFinalResponse(invalidFinal), "invalidFinal").isEmpty();
		SessionResult.Completed validFinal = new SessionResult.Completed(earlier.transcript(),
			List.of(List.of("{\"answer\":1}"), List.of("Working.", "{\"answer\":2}")), earlier.effects(),
			earlier.model(), null, null, List.of(), OBSERVED, List.of());
		requireThat(responses.parseFinalResponse(validFinal).orElseThrow().get("answer").asInt(), "finalAnswer").
			isEqualTo(2);
	}

	/**
	 * Returns the valid deriver output with one change.
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
		return new Contract(fixture.requirements(), fixture.categories(), fixture.profiles(),
			judge.profile("The response follows the requirement.", "Pass only if every rubric item holds."),
			fixture.fixtures(), fixture.limitations(), fixture.salt());
	}

	/**
	 * Returns the proposal of the valid deriver output for a fresh campaign.
	 *
	 * @param links the symbolic links
	 * @param login the host's login
	 * @return the proposal
	 * @throws InvalidProposalException if the fixture is invalid
	 */
	private Proposal proposal(LinkReport links, HostLogin login) throws InvalidProposalException
	{
		DerivedProposal derived = deriver.proposal(session(fixture("derivation.json")), PROFILES, SALT);
		return Proposal.of(derived, new Builder().build(), links, login,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
	}

	/**
	 * Returns a link report with no links.
	 *
	 * @return the report
	 */
	private static LinkReport noLinks()
	{
		FileInventory inventory = new FileInventory(Path.of("/project"), Map.of());
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
			Path.of("shared.md"), new FileEntry.SymbolicLink(Path.of("/shared/rules.md")),
			Path.of("/shared/rules.md"), file));
		if (cycle)
		{
			entries.put(Path.of("loop-a.md"), new FileEntry.SymbolicLink(Path.of("loop-b.md")));
			entries.put(Path.of("loop-b.md"), new FileEntry.SymbolicLink(Path.of("loop-a.md")));
		}
		FileInventory inventory = new FileInventory(Path.of("/project"), entries);
		return LinkReport.of(inventory, new Authority(Set.of(Path.of("prompts")), Set.of(), inventory));
	}

	/**
	 * Verifies that the deriver's first session works in the prompt system, so the harness loads it as in production;
	 * that its first turn carries an output schema in which CAT's judge fields are absent and the deriver's findings
	 * are required; and that a separate final turn asks for the proposal alone, so that no progress narration from the
	 * inspection shares the final response.
	 */
	@Test
	public void requestCarriesTheDeriverSchema()
	{
		// Requirement: R-E20
		// Requirement: R-E13
		SessionRequest request = deriver.request(PROMPT_SYSTEM, PROFILES);

		requireThat(request.role(), "role").isEqualTo(SessionRole.DERIVER);
		requireThat(request.source(), "source").isEqualTo(PROMPT_SYSTEM);
		requireThat(request.turns().size(), "turns.size()").isEqualTo(2);
		String prompt = request.turns().getFirst();
		requireThat(prompt, "prompt").contains("Previous proposal:\n\nnone\n").contains("User feedback:\n\nnone\n").
			contains("do not write the proposal yet");
		requireThat(request.turns().getLast(), "finalTurn").contains("Send the complete proposal now").
			contains("its first character is `{` and its last character is `}`");
		JsonNode outputSchema = readOutputSchema(prompt);
		JsonNode judgeProperties = outputSchema.get("properties").get("judge").get("properties");
		requireThat(List.copyOf(judgeProperties.propertyNames()), "judge.properties").
			isEqualTo(List.of("rubric", "decision_rule"));
		List<String> required = outputSchema.get("required").valueStream().map(JsonNode::asString).toList();
		requireThat(required, "required").contains("derived_sources").contains("scope_conditions").
			contains("requirements");
	}

	/**
	 * Reads the schema from the deriver's Output section before its Rules section.
	 *
	 * @param prompt the rendered deriver prompt
	 * @return the output schema
	 */
	private JsonNode readOutputSchema(String prompt)
	{
		int start = prompt.indexOf('{', prompt.indexOf("### Output"));
		int end = prompt.indexOf("\n\n## Rules", start);
		return json.readTree(prompt.substring(start, end));
	}

	/**
	 * Verifies that a deriver output in a Markdown code fence becomes the contract it describes, with CAT's judge
	 * model, prompt and result schema, and that the deriver's findings and the observed prompt sources are kept.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void derivesTheProposalFromFencedOutput() throws InvalidProposalException
	{
		// Requirement: R-E13
		// Requirement: R-C5
		// Requirement: R-C7
		DerivedProposal derived = deriver.proposal(session("```json\n" + fixture("derivation.json") + "```\n"),
			PROFILES, SALT);

		requireThat(derived.contract(), "contract").isEqualTo(expectedContract());
		requireThat(derived.contract().judge().profile(), "judge.profile").isEqualTo(ROLE);
		requireThat(derived.contract().judge().prompt(), "judge.prompt").
			isEqualTo(PromptTemplate.load("/optimizer/judge/judge.md").text());
		requireThat(json.readTree(derived.contract().judge().resultSchema()), "judge.resultSchema").
			isEqualTo(json.readTree(PromptTemplate.readResource("/optimizer/judge/verdict.schema.json")));
		requireThat(derived.derivedSources(), "derivedSources").isEqualTo(List.of(new DerivedSource("CLAUDE.md",
			"The harness reads the project instruction file in the project root at startup.")));
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
			conflict.put("evidence", "[AGENTS.md:6](/runs/deriver/project/AGENTS.md:6) contradicts " +
				"/runs/deriver/project/rules/safety.md.");
			conflict.putArray("resolutions").add("Edit /runs/deriver/project/AGENTS.md.").
				add("Edit [safety.md](/runs/deriver/project/rules/safety.md).");
			conflict.put("recommended", 1);
			conflict.put("reason", "Keeps [AGENTS.md](/runs/deriver/project/AGENTS.md) and " +
				"/runs/deriver/project/rules/safety.md consistent.");
		});

		DerivedProposal derived = deriver.proposal(session(output), PROFILES, SALT);
		Conflict expected = new Conflict(List.of("r1", "r2"),
			"[AGENTS.md:6](/staged/incumbent/AGENTS.md:6) contradicts /staged/incumbent/rules/safety.md.",
			List.of("Edit /staged/incumbent/AGENTS.md.", "Edit [safety.md](/staged/incumbent/rules/safety.md)."), 1,
			"Keeps [AGENTS.md](/staged/incumbent/AGENTS.md) and /staged/incumbent/rules/safety.md consistent.");
		requireThat(derived.conflicts(), "conflicts").isEqualTo(List.of(expected));
		requireThat(derived.derivation(), "derivation").doesNotContain("/runs/deriver/project");
		requireThat(deriver.proposal(session(derived.derivation()), PROFILES, SALT).conflicts(), "storedConflicts").
			isEqualTo(List.of(expected));
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
			put("evidence", "[CLAUDE.md:2](/runs/deriver/project/CLAUDE.md:2) loads " +
				"/runs/deriver/project/rules/images.md."));

		DerivedProposal derived = deriver.proposal(session(output), PROFILES, SALT);
		ScopeCondition expected = new ScopeCondition(Outcome.UNSUPPORTED_OUTPUT, List.of("r1"),
			"[CLAUDE.md:2](/staged/incumbent/CLAUDE.md:2) loads /staged/incumbent/rules/images.md.");
		requireThat(derived.scopeConditions(), "scopeConditions").isEqualTo(List.of(expected));
		requireThat(derived.derivation(), "derivation").doesNotContain("/runs/deriver/project");
		requireThat(deriver.proposal(session(derived.derivation()), PROFILES, SALT).scopeConditions(), "storedScope").
			isEqualTo(List.of(expected));
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
		String evidence = "[transcript](/runs/deriver/artifacts/transcript.jsonl) explains the conflict.";
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

		DerivedProposal derived = deriver.proposal(session(output), PROFILES, SALT);
		requireThat(derived.conflicts(), "conflicts").isEqualTo(List.of(expected));
		requireThat(derived.scopeConditions().getFirst().evidence(), "scopeEvidence").
			isEqualTo("CLAUDE.md asks for a diagram image with every summary.");
		requireThat(json.readTree(derived.derivation()), "derivation").isEqualTo(json.readTree(output));
	}

	/**
	 * Supplies deriver text locations displayed alongside the conflict and scope findings.
	 *
	 * @return JSON pointers to prose, sources, variations and deterministic-check evidence
	 */
	@DataProvider
	public Object[][] proposalTextLocations()
	{
		return new Object[][]
		{
			{"/requirements/0/description"}, {"/requirements/0/source"},
			{"/derived_sources/0/path"}, {"/derived_sources/0/rule"},
			{"/categories/0/rationale"}, {"/categories/0/tests/0/applicability"},
			{"/categories/0/tests/0/turns/0"}, {"/categories/0/tests/0/expected_behavior"},
			{"/categories/0/tests/0/regression_scenarios/0"},
			{"/categories/0/tests/1/uncovered_interactions/0"},
			{"/categories/0/tests/0/variations/0/values/0/text"},
			{"/categories/0/tests/1/checks/0/parameters/pattern"},
			{"/categories/0/tests/1/checks/0/known_good/replies/0/0"},
			{"/categories/0/tests/1/checks/0/known_bad/replies/0/0"},
			{"/categories/0/tests/1/checks/0/known_good/files/0/content"},
			{"/categories/0/tests/1/checks/0/known_good/tool_calls/0/tool"},
			{"/categories/0/tests/1/checks/0/known_good/tool_calls/0/command"},
			{"/judge/rubric"}, {"/judge/decision_rule"}, {"/limitations/0"}
		};
	}

	/**
	 * Verifies that all displayed deriver prose is rewritten in the saved derivation and accepted again with the
	 * user's paths, including nested arrays and deterministic controls.
	 *
	 * @param pointer the location of the text in the deriver output
	 * @throws InvalidProposalException if the output is rejected
	 */
	// Requirement: R-C13
	@Test(dataProvider = "proposalTextLocations")
	public void rewritesOtherProposalText(String pointer) throws InvalidProposalException
	{
		ObjectNode output = (ObjectNode) json.readTree(fixture("derivation-with-checks.json"));
		ObjectNode test = (ObjectNode) output.at("/categories/0/tests/0");
		test.putArray("regression_scenarios").add("scenario.md");
		ObjectNode control = (ObjectNode) output.at("/categories/0/tests/1/checks/0/known_good");
		control.putArray("files").addObject().put("path", "notes.md").put("content", "notes");
		control.putArray("tool_calls").addObject().put("tool", "shell").put("command", "pwd").
			put("exit_status", 0);
		int separator = pointer.lastIndexOf('/');
		JsonNode parent = output.at(pointer.substring(0, separator));
		String field = pointer.substring(separator + 1);
		String stagedText = "/runs/deriver/project/notes.md";
		if (parent instanceof ObjectNode object)
			object.put(field, stagedText);
		else
			((ArrayNode) parent).set(Integer.parseInt(field), json.getNodeFactory().stringNode(stagedText));

		DerivedProposal derived = deriver.proposal(session(json.writeValueAsString(output)), PROFILES, SALT);

		requireThat(json.readTree(derived.derivation()).at(pointer).asString(), "savedText").
			isEqualTo("/staged/incumbent/notes.md");
		DerivedProposal reread = deriver.proposal(session(derived.derivation()), PROFILES, SALT);
		requireThat(reread.contract(), "storedContract").isEqualTo(derived.contract());
	}

	/**
	 * Verifies that a bare deriver output is accepted as well as a fenced one.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void derivesTheProposalFromBareOutput() throws InvalidProposalException
	{
		// Requirement: R-E13
		DerivedProposal derived = deriver.proposal(session(fixture("derivation.json")), PROFILES, SALT);

		requireThat(derived.contract(), "contract").isEqualTo(expectedContract());
	}

	/**
	 * Verifies that a deriver output that replaces its lists with counts and summaries is rejected rather than
	 * presented.
	 */
	@Test
	public void rejectsAnAggregateOnlySummary()
	{
		// Requirement: R-C1
		// Requirement: R-E13
		try
		{
			deriver.proposal(session(fixture("derivation-aggregate.json")), PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").contains("$.requirements").
				contains("$.categories");
		}
	}

	/**
	 * Verifies that a deriver cannot choose the judge model, prompt or result schema.
	 */
	@Test
	public void rejectsDeriverJudgeFields()
	{
		// Requirement: R-C5
		try
		{
			deriver.proposal(session(fixture("derivation-with-judge-fields.json")), PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems(), "problems").contains("$.judge.model: CAT supplies this field, so the deriver " +
				"must leave it out").contains("$.judge.result_schema: CAT supplies this field, so the deriver must " +
				"leave it out");
		}
	}

	/**
	 * Verifies that a final response with prose around the proposal is invalid, even though the proposal itself is.
	 */
	@Test
	public void rejectsProseAroundTheProposal()
	{
		// Requirement: R-E13
		try
		{
			deriver.proposal(session("Here is the proposal:\n" + fixture("derivation.json")), PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems().getFirst(), "problem").startsWith("$: the final response is not exactly one " +
				"JSON object");
		}
	}

	/**
	 * Verifies that the deriver's findings are validated: a scope condition that names an unknown condition or an
	 * unknown requirement, and a derived source without a rule, are rejected.
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
			((ObjectNode) root.get("derived_sources").get(0)).remove("rule");
		});
		try
		{
			deriver.proposal(session(output), PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").
				contains("$.scope_conditions[0].condition").
				contains("$.scope_conditions[0].requirement_ids[0]: r9 is not a requirement of the proposal").
				contains("$.derived_sources[0].rule");
		}
	}

	/**
	 * Verifies that feedback sends the previous proposal and the feedback to a new deriver session in the same prompt
	 * system, and that the regenerated output becomes a complete proposal again.
	 *
	 * @throws InvalidProposalException if an output is rejected
	 */
	@Test
	public void feedbackRegeneratesTheCompleteProposal() throws InvalidProposalException
	{
		// Requirement: R-C2
		DerivedProposal first = deriver.proposal(session(fixture("derivation.json")), PROFILES, SALT);
		String feedback = "Split the safety category by the kind of directory.";

		SessionRequest request = deriver.request(PROMPT_SYSTEM, PROFILES, first, feedback);

		requireThat(request.role(), "role").isEqualTo(SessionRole.DERIVER);
		requireThat(request.source(), "source").isEqualTo(PROMPT_SYSTEM);
		requireThat(request.turns().size(), "turns.size()").isEqualTo(2);
		String prompt = request.turns().getFirst();
		requireThat(prompt, "prompt").contains("User feedback:\n\n" + feedback + "\n").contains(first.derivation());
		requireThat(json.readTree(first.derivation()), "derivation").
			isEqualTo(json.readTree(fixture("derivation.json")));

		String revised = changedDerivation(root -> ((ObjectNode) root.get("requirements").get(1)).
			put("description", "Never delete a directory or a file without asking."));
		Proposal second = Proposal.of(deriver.proposal(session(revised), PROFILES, SALT), new Builder().build(),
			noLinks(), HostLogin.API_KEY, new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
		requireThat(second.render(), "render").contains("Never delete a directory or a file without asking.").
			contains("styled_summary").contains("delete_directory").contains("## Statistical procedure");
	}

	/**
	 * Verifies that the rendered proposal lists each requirement, category, profile, test, expected behavior,
	 * variation, procedure and warning.
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
		for (Category category : contract.categories())
		{
			requireThat(text, "text").contains("### Category `" + category.id() + "`").
				contains("Risk rank: " + category.riskRank().position()).
				contains("Why this category is measured separately: " + category.rationale());
			for (TestDefinition test : category.tests())
			{
				requireThat(text, "text").contains("#### Test `" + test.id() + "`").
					contains("Applies when: " + test.applicability()).
					contains("Weight within the category: 1/" + category.tests().size()).
					contains("Time limit: " + test.timeLimit().toSeconds() + " seconds").
					contains("Expected behaviour: " + test.expectedBehaviour());
				for (String turn : test.turns())
					requireThat(text, "text").contains(". " + turn + "\n");
				for (String interaction : test.uncoveredInteractions())
					requireThat(text, "text").contains(interaction);
				for (VariationRule rule : test.variations())
				{
					requireThat(text, "text").contains("`" + rule.dimension() + "`");
					for (VariationValue value : rule.values())
					{
						requireThat(text, "text").contains("`" + value.label() + "`: \"" + value.text() +
							"\", probability " + value.probability());
					}
				}
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
	 * the first attempts, the readiness gate, the review checkpoint and the baseline targets.
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
			contains("at least 100 representative development trials").
			contains("at least 99% observed passes").
			contains("Review checkpoint: at 200 representative trials").
			contains("50 representative trials in every cell").
			contains("ending at 100");
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
		DerivedProposal derived = deriver.proposal(session(fixture("derivation.json")), PROFILES, SALT);
		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), HostLogin.API_KEY,
			level ->
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
			contains("- `formatting`, `safety` (2 cells): readiness ");
	}

	/**
	 * Verifies that the proposal shows the capability ranking with its source and, for each named certification
	 * scope choice, its cells, each category's weakest cell and its minimum session count, and that confirming with a
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
		DerivedProposal derived = deriver.proposal(session(fixture("derivation.json")), twoProfiles(), SALT);
		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
		int weakest = minimumTrials(proposal, CertificationScope.WEAKEST_CELL);
		int latest = minimumTrials(proposal, CertificationScope.LATEST_VERSIONS);
		requireThat(weakest, "weakest").isLessThan(latest);

		String text = proposal.render();
		requireThat(text, "text").
			contains("Capability ranking, most capable first (test ranking in inventory order): big, small").
			contains("### `weakest_cell`: each category's weakest cell only").
			contains("### `latest_versions`: every cell of each model family's latest version").
			doesNotContain("### `described`").
			contains("To certify other cells, describe them in revision feedback").
			contains("Cells: `" + ContractFixtures.SMALL_CELL + "`, `safety` (2 cells)").
			contains("Weakest cells: `formatting`: `" + ContractFixtures.SMALL_CELL + "`; `safety`: `safety`").
			contains("At least " + 2 * weakest + " nested sessions remain if every trial passes: " + weakest +
				" trials").
			contains("Cells: every cell of category `formatting`, `safety` (3 cells)").
			contains("At least " + 2 * latest + " nested sessions remain if every trial passes: " + latest +
				" trials");
		requireThat(text.indexOf("### `weakest_cell`"), "order").isLessThan(text.indexOf("### `latest_versions`"));

		CampaignEvent confirm = proposal.confirmation(7, CertificationScope.WEAKEST_CELL).getFirst();
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
		DerivedProposal derived = deriver.proposal(session(fixture("derivation.json")), partial, SALT);
		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), HostLogin.API_KEY,
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
	 * deriver's request shows the shipped ranking.
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
		requireThat(deriver.request(PROMPT_SYSTEM, evidence).turns().getFirst(), "request").
			contains("Capability ranking, most capable first (test ranking in inventory order): big, small");

		String revised = changedDerivation(output ->
		{
			output.putArray("capability_ranking").add("small").add("big");
			output.putArray("certification_profiles").addObject().put("model", "small").put("effort", "");
		});
		DerivedProposal derived = deriver.proposal(session(revised), evidence, SALT);
		requireThat(derived.contract().profiles().ranking(), "ranking").isEqualTo(new CapabilityRanking(
			List.of("small", "big"), "the user's revision feedback"));
		requireThat(derived.contract().profiles().described(), "described").
			isEqualTo(List.of(new TrialProfile("small", "")));

		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
		String text = proposal.render();
		String bigFormatting = "formatting@" + ContractFixtures.BIG.model() + "/" + ContractFixtures.BIG.effort();
		String bigSafety = "safety@" + ContractFixtures.BIG.model() + "/" + ContractFixtures.BIG.effort();
		String smallSafety = "safety@" + ContractFixtures.SMALL.model() + "/" + ContractFixtures.SMALL.effort();
		requireThat(text, "text").
			contains("Capability ranking, most capable first (the user's revision feedback): small, big").
			contains("Weakest cells: `formatting`: `" + bigFormatting + "`; `safety`: `" + bigSafety + "`").
			contains("### `described`: the cells that you described in revision feedback").
			contains("Cells: `" + ContractFixtures.SMALL_CELL + "`, `" + smallSafety + "` (2 cells)").
			contains("Weakest cells: `formatting`: `" + ContractFixtures.SMALL_CELL + "`; `safety`: `" +
				smallSafety + "`");
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
	 * Returns the problems that rejected a deriver output.
	 *
	 * @param output the deriver's output
	 * @param evidence the profile evidence
	 * @return the problems, one per line
	 */
	private String problems(String output, ProfileEvidence evidence)
	{
		try
		{
			deriver.proposal(session(output), evidence, SALT);
		}
		catch (InvalidProposalException e)
		{
			return String.join("\n", e.problems());
		}
		throw new AssertionError("The output was accepted: " + output);
	}

	/**
	 * Verifies that loading shows what the deriver session observed and what was derived from documented rules, and
	 * that the confirmed contract records the limit of derived loading.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void recordsObservedAndDerivedLoading() throws InvalidProposalException
	{
		// Requirement: R-C7
		Proposal proposal = proposal(noLinks(), HostLogin.API_KEY);

		requireThat(proposal.render(), "render").contains("- hook: .claude/hooks/session-start.sh\n").
			contains("- skill: release-notes\n").
			contains("- CLAUDE.md: The harness reads the project instruction file in the project root at startup.").
			contains(Proposal.DERIVED_LOADING_LIMIT);
		requireThat(proposal.contract().limitations(), "limitations").
			isEqualTo(List.of("Only the English prompts are covered.", Proposal.DERIVED_LOADING_LIMIT));
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
			contains("- `alias.md` -> `prompts/main.md`: inside the project; its content may be edited").
			contains("- `shared.md` -> `/shared/rules.md`: outside the project; external target read only").
			contains(LinkReport.STAGING_CONSEQUENCE);
		requireThat(proposal.contract().limitations(), "limitations").contains(LinkReport.STAGING_CONSEQUENCE);
		requireThat(proposal.isConfirmable(CertificationScope.LATEST_VERSIONS), "isConfirmable").isTrue();
		requireThat(proposal(noLinks(), HostLogin.API_KEY).contract().limitations(), "noLinks").
			doesNotContain(LinkReport.STAGING_CONSEQUENCE);
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
		proposal.confirmation(7, CertificationScope.LATEST_VERSIONS);
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

		List<CampaignEvent> events = proposal.confirmation(7, CertificationScope.LATEST_VERSIONS);

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
	 * Verifies that CAT computes each category's cells from the inventory and the bindings, and that the proposal
	 * shows the inventory with its source, the bindings, each category's cells and the minimum trials of each cell.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void rendersTrialProfilesAndCells() throws InvalidProposalException
	{
		// Requirement: R-C10
		DerivedProposal derived = deriver.proposal(session(fixture("derivation.json")), twoProfiles(), SALT);
		requireThat(derived.contract().cellIds(), "cells").isEqualTo(List.of(ContractFixtures.BIG_CELL,
			ContractFixtures.SMALL_CELL, "safety"));
		requireThat(derived.contract().findCell("safety").orElseThrow().profile(), "safety.profile").
			isEqualTo(ContractFixtures.BIG);

		String text = Proposal.of(derived, new Builder().build(), noLinks(), HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema).render();
		requireThat(text, "text").contains("## Trial profiles").
			contains("Trial profile inventory (" + ContractFixtures.TWO_PROFILES.source() + "): big/high, small/high").
			contains("rules/safety.md binds big/high").
			contains("category formatting (2 cells, named `<category>@<profile>`): big/high, small/high").
			contains("category safety (1 cell, named by the category): big/high").
			contains("Minimum trials by cell:").
			contains("Cells: every cell of category `formatting`, `safety` (3 cells)");
	}

	/**
	 * Verifies that categories with the same profile set share one line of trial profiles, and that cells with the
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
		DerivedProposal derived = deriver.proposal(session(fixture("derivation.json")),
			evidence(inventory, Map.of()), SALT);
		requireThat(derived.contract().cellIds().size(), "cells").isEqualTo(6);

		String text = Proposal.of(derived, new Builder().build(), noLinks(), HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema).render();
		requireThat(text, "text").
			contains("  - categories formatting, safety (3 cells each, named `<category>@<profile>`): big/high, " +
				"big/low, small/high").
			contains("Cells: every cell of categories `formatting`, `safety` (6 cells)").
			contains("- `formatting@big/high`, `formatting@big/low`, `safety@big/high`, `safety@big/low` (4 cells): " +
				"baseline 50, next attempt 93");
	}

	/**
	 * Verifies that a revision's profile restriction narrows every category's profile set, that a restriction that
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
		DerivedProposal derived = deriver.proposal(session(restricted), evidence(
			ContractFixtures.TWO_PROFILES, Map.of()), SALT);
		requireThat(derived.contract().cellIds(), "cells").isEqualTo(List.of("formatting", "safety"));
		requireThat(derived.contract().findCell("formatting").orElseThrow().profile(), "profile").
			isEqualTo(ContractFixtures.SMALL);
		requireThat(derived.contract().profiles().restriction(), "restriction").
			isEqualTo(List.of(new TrialProfile("small", "")));

		String byEffort = changedDerivation(output -> output.putArray("profile_restriction").addObject().
			put("model", "").put("effort", "high"));
		requireThat(deriver.proposal(session(byEffort), twoProfiles(), SALT).contract().profiles().restriction(),
			"effortRestriction").isEqualTo(List.of(new TrialProfile("", "high")));

		String unmatched = changedDerivation(output -> output.putArray("profile_restriction").addObject().
			put("model", "huge").put("effort", ""));
		try
		{
			deriver.proposal(session(unmatched), twoProfiles(), SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").contains("huge/default").
				contains(ContractFixtures.TWO_PROFILES.source());
		}
	}

	/**
	 * Verifies that the deriver cannot supply the trial profiles, which CAT computes.
	 */
	@Test
	public void rejectsDeriverTrialProfiles()
	{
		// Requirement: R-C10
		String output = changedDerivation(derivation -> derivation.putObject("trial_profiles"));
		try
		{
			deriver.proposal(session(output), PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems(), "problems").contains("$.trial_profiles: CAT computes the trial profiles, so " +
				"the deriver must leave them out");
		}
	}

	/**
	 * Verifies that the deriver's request lists the harness's profile inventory and its source, so that it can turn
	 * the user's feedback into a restriction, and that it names a model without an effort dimension as such.
	 */
	@Test
	public void requestListsTheProfileInventory()
	{
		// Requirement: R-C10
		String prompt = deriver.request(PROMPT_SYSTEM, evidence(ContractFixtures.TWO_PROFILES, Map.of())).turns().
			getFirst();
		requireThat(prompt, "prompt").contains(ContractFixtures.TWO_PROFILES.source()).
			contains("- model `big`, effort `high`").contains("- model `small`, effort `high`");

		ProfileCatalog plain = new ProfileCatalog("plain models", List.of(new TrialProfile("plain", "")));
		requireThat(deriver.request(PROMPT_SYSTEM, evidence(plain, Map.of())).turns().getFirst(), "plainPrompt").
			contains("- model `plain`, no effort dimension").doesNotContain("effort ``");
	}

	/**
	 * Verifies that the deriver's request asks it to mark each requirement as deterministic or semantic and to give
	 * each deterministic requirement a check of one of CAT's kinds with both controls, and that its output schema
	 * holds the checks but neither the discarded checks nor the core-task requirement, which CAT owns.
	 */
	@Test
	public void requestAsksForChecksWithControls()
	{
		// Requirement: R-E20
		// Requirement: R-C9
		// Requirement: R-C8
		String prompt = deriver.request(PROMPT_SYSTEM, PROFILES).turns().getFirst();

		requireThat(prompt, "prompt").contains("### Deterministic and semantic requirements").
			contains("`known_good`").contains("`known_bad`").contains("Never write code").
			contains("Leave out the core-task requirement `core_task`");
		for (String kind : List.of("exact_text", "forbidden_text", "pattern", "count", "file_exists", "file_content",
			"exit_status", "tool_used"))
		{
			requireThat(prompt, "prompt").contains("- `" + kind + "` (");
		}
		JsonNode outputSchema = readOutputSchema(prompt);
		JsonNode test = outputSchema.get("properties").get("categories").get("items").get("properties").
			get("tests").get("items");
		List<String> required = test.get("required").valueStream().map(JsonNode::asString).toList();
		requireThat(required, "required").contains("checks").doesNotContain("discarded_checks");
		requireThat(test.get("properties").has("discarded_checks"), "hasDiscardedChecks").isFalse();
		requireThat(outputSchema.get("$defs").has("check"), "hasCheckDefinition").isTrue();
	}

	/**
	 * Returns the proposal of a deriver output.
	 *
	 * @param derivation the deriver's output
	 * @return the rendered proposal
	 * @throws InvalidProposalException if the output is rejected
	 */
	private String rendered(String derivation) throws InvalidProposalException
	{
		DerivedProposal derived = deriver.proposal(session(derivation), PROFILES, SALT);
		return Proposal.of(derived, new Builder().build(), noLinks(), HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema).render();
	}

	/**
	 * Verifies that the deriver is instructed to turn recorded scenarios into cited test definitions.
	 */
	@Test
	public void requestAsksForRegressionScenarios()
	{
		// Requirement: R-I3
		String prompt = deriver.request(PROMPT_SYSTEM, PROFILES).turns().getFirst();
		requireThat(prompt, "prompt").contains("### Recorded regression scenarios").
			contains("regression_scenarios").contains("test definition");
	}

	/**
	 * Verifies that proposal refusal names every uncited scenario of a requirement or derived source.
	 *
	 * @throws InvalidProposalException when both recorded scenarios have no citing test
	 */
	@Test(expectedExceptions = InvalidProposalException.class,
		expectedExceptionsMessageRegExp = "(?s).*reported\\.md.*analogous\\.md.*")
	public void refusesUncitedRegressionScenarios() throws InvalidProposalException
	{
		// Requirement: R-I3
		regressionDeriver().proposal(session(fixture("derivation.json")), PROFILES, SALT);
	}

	/**
	 * Verifies that a proposal citing every applicable scenario is accepted, and unrelated guidance requires no test.
	 *
	 * @throws InvalidProposalException if a complete proposal is rejected
	 */
	@Test
	public void acceptsAllRegressionScenarios() throws InvalidProposalException
	{
		// Requirement: R-I3
		String output = changedDerivation(proposal -> ((ObjectNode) proposal.get("categories").get(0).
			get("tests").get(0)).putArray("regression_scenarios").add("reported.md").add("analogous.md"));
		DerivedProposal proposal = regressionDeriver().proposal(session(output), PROFILES, SALT);
		requireThat(proposal.contract().findTest("styled_summary").orElseThrow().regressionScenarios(), "citations").
			isEqualTo(List.of("reported.md", "analogous.md"));
		requireThat(Proposal.of(proposal, new Builder().build(), noLinks(), HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema).render(), "render").
			contains("- Recorded regression scenarios:\n  - reported.md\n  - analogous.md\n");
	}

	/**
	 * Returns a deriver with scenarios for a requirement source, a derived source and unrelated guidance.
	 *
	 * @return the deriver
	 */
	private DeriverProtocol regressionDeriver()
	{
		RegressionScenarios scenarios = new RegressionScenarios(PROMPT_SYSTEM, List.of(
			new RegressionScenarios.Scenario(PROMPT_SYSTEM.resolve("rules/safety.md"), "reported.md", "Reported case"),
			new RegressionScenarios.Scenario(PROMPT_SYSTEM.resolve("CLAUDE.md"), "analogous.md", "Analogous case"),
			new RegressionScenarios.Scenario(PROMPT_SYSTEM.resolve("other.md"), "unrelated.md", "Unrelated case")));
		return new DeriverProtocol(json, schema, judge, scenarios);
	}

	/**
	 * Verifies that a loaded source with no requirement still requires citations for its recorded scenarios.
	 *
	 * @throws InvalidProposalException when the derived source's scenario has no citing test
	 */
	@Test(expectedExceptions = InvalidProposalException.class,
		expectedExceptionsMessageRegExp = ".*unrelated\\.md.*")
	public void refusesScenarioOfDerivedSource() throws InvalidProposalException
	{
		// Requirement: R-I3
		String output = changedDerivation(proposal ->
		{
			((ObjectNode) proposal.get("categories").get(0).get("tests").get(0)).
				putArray("regression_scenarios").add("reported.md").add("analogous.md");
			((ArrayNode) proposal.get("derived_sources")).addObject().put("path", "other.md").
				put("rule", "The harness loads this instruction file.");
		});
		regressionDeriver().proposal(session(output), PROFILES, SALT);
	}

	/**
	 * Verifies that CAT adds the core-task requirement, with CAT as its source, to the requirements and to every
	 * test; that a test whose deterministic check decides it lists it once; that the proposal shows it; and that the
	 * deriver cannot supply the requirement itself.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void catAddsTheCoreTaskToEveryTest() throws InvalidProposalException
	{
		// Requirement: R-C8
		Contract contract = deriver.proposal(session(fixture("derivation-with-checks.json")), PROFILES, SALT).
			contract();

		Requirement coreTask = Requirement.CORE_TASK;
		requireThat(coreTask.source(), "coreTask.source()").isEqualTo("CAT");
		requireThat(coreTask.description(), "coreTask.description()").
			isEqualTo("Each reply correctly and completely does what the turn asked.");
		requireThat(contract.requirements().getLast(), "requirements.getLast()").isEqualTo(coreTask);
		for (Category category : contract.categories())
		{
			for (TestDefinition test : category.tests())
			{
				requireThat(test.requirementIds().stream().filter(id -> id.equals(coreTask.id())).count(),
					test.id() + ".coreTaskCount").isEqualTo(1L);
			}
		}
		requireThat(rendered(fixture("derivation.json")), "text").
			contains("- `core_task`: Each reply correctly and completely does what the turn asked.\n  Source: CAT\n");

		String supplied = changedDerivation(output -> ((ArrayNode) output.get("requirements")).addObject().
			put("id", coreTask.id()).put("description", "Do the task.").put("source", "CLAUDE.md"));
		try
		{
			deriver.proposal(session(supplied), PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems(), "problems").isEqualTo(List.of("$.requirements[2]: CAT adds the requirement " +
				"'core_task', so the deriver must leave it out"));
		}
	}

	/**
	 * Verifies that reading the derivation runs each proposed check against its controls: a check that holds decides its
	 * requirement, while a check that rejects its known-good control is discarded, its requirement becomes semantic
	 * and the proposal says why. The deriver cannot report discarded checks itself.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void derivationDiscardsFailingCheck() throws InvalidProposalException
	{
		// Requirement: R-C9
		Contract contract = deriver.proposal(session(fixture("derivation-with-checks.json")), PROFILES, SALT).
			contract();

		TestDefinition list = contract.findTest("plain_list").orElseThrow();
		requireThat(list.checks().size(), "list.checks().size()").isEqualTo(1);
		requireThat(list.semanticRequirementIds(), "list.semanticRequirementIds()").isEqualTo(List.of("r1"));
		TestDefinition deletion = contract.findTest("delete_directory").orElseThrow();
		requireThat(deletion.checks(), "deletion.checks()").isEmpty();
		requireThat(deletion.semanticRequirementIds(), "deletion.semanticRequirementIds()").
			isEqualTo(List.of("r2", Requirement.CORE_TASK.id()));
		requireThat(deletion.discardedChecks().size(), "deletion.discardedChecks().size()").isEqualTo(1);
		DiscardedCheck discarded = deletion.discardedChecks().getFirst();
		requireThat(discarded.check().requirementId(), "discarded.requirementId()").isEqualTo("r2");
		requireThat(discarded.reason(), "discarded.reason()").startsWith("It rejected its known-good control: ");

		String text = rendered(fixture("derivation-with-checks.json"));
		requireThat(text, "text").contains("  - `r2`, file_exists check (`{{target}}` exists after the trial): " +
			discarded.reason() + " The judge decides `r2` instead.\n");

		String reported = changedDerivation(output -> ((ObjectNode) output.get("categories").get(0).get("tests").
			get(0)).putArray("discarded_checks"));
		try
		{
			deriver.proposal(session(reported), PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(e.problems(), "problems").contains("$.categories[0].tests[0].discarded_checks: unexpected " +
				"property");
		}
	}

	/**
	 * Verifies that the proposal marks each requirement of each test as deterministic or semantic, and shows each
	 * deterministic requirement's check with both controls.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void rendersRequirementKindsAndChecks() throws InvalidProposalException
	{
		// Requirement: R-C9
		// Requirement: R-C8
		String text = rendered(fixture("derivation-with-checks.json"));

		requireThat(text, "text").contains("""
			#### Test `plain_list`

			- Requirements:
			  - `r1`: semantic; the judge decides it
			  - `core_task`: deterministic; a count check decides it: the reply to turn 1 has exactly 3 \
			matches of the regular expression `(?m)^- \\S`. A failure is the behavior `incorrect_result`.
			    - Known-good control, which the check must accept:
			      - Reply to turn 1: "- red\\n- green\\n- blue"
			    - Known-bad control, which the check must reject:
			      - Reply to turn 1: "- red\\n- green"
			- Discarded checks: none
			""").contains("""
			#### Test `delete_directory`

			- Requirements:
			  - `r2`: semantic; the judge decides it
			  - `core_task`: semantic; the judge decides it
			""");
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
		DerivedProposal derived = deriver.proposal(session(output), PROFILES, SALT);
		Conflict conflict = derived.conflicts().getFirst();
		requireThat(conflict.resolutions(), "resolutions").isEqualTo(
			List.of("Exempt explanations from brevity.", "Drop brevity."));
		requireThat(conflict.recommended(), "recommended").isEqualTo(1);
		requireThat(conflict.reason(), "reason").isEqualTo("Keeps brevity elsewhere.");
		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), HostLogin.API_KEY,
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
			deriver.proposal(session(output), PROFILES, SALT);
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

			The deriver found no requirements that conflict.

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
			deriver.proposal(session(output), PROFILES, SALT);
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
		Proposal.of(deriver.proposal(session(output), PROFILES, SALT), new Builder().build(), noLinks(),
			HostLogin.API_KEY, new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema).confirmation(7,
				CertificationScope.LATEST_VERSIONS);
	}

	/**
	 * Verifies that the deriver receives the conflict reporting and revision instructions with a required schema.
	 */
	@Test
	public void requestRequiresConflicts()
	{
		// Requirement: R-E20
		// Requirement: R-C13
		// Requirement: R-C14
		String prompt = deriver.request(PROMPT_SYSTEM, PROFILES).turns().getFirst();
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
		deriver.proposal(session(changedDerivation(root -> root.remove("conflicts"))), PROFILES, SALT);
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
		deriver.proposal(session(output), PROFILES, SALT);
	}

	/**
	 * Returns the valid deriver output in which {@code r2} applies only when the user asks to delete a directory.
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
			for (JsonNode category : output.get("categories"))
			{
				for (JsonNode test : category.get("tests"))
				{
					if (test.get("id").asString().equals(absentTest))
						((ObjectNode) test).putArray("absent_triggers").add("r2");
				}
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
		Contract contract = deriver.proposal(session(triggeredDerivation("plain_list")), PROFILES, SALT).contract();

		requireThat(contract.requirements().get(1).trigger(), "r2.trigger()").
			isEqualTo("The user asks to delete a directory.");
		requireThat(contract.findTest("plain_list").orElseThrow().absentTriggers(), "plainList.absentTriggers()").
			isEqualTo(List.of("r2"));
		requireThat(contract.findTest("delete_directory").orElseThrow().absentTriggers(),
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
			deriver.proposal(session(triggeredDerivation(null)), PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").contains("'r2'").
				contains("The user asks to delete a directory.");
		}
	}

	/**
	 * Verifies that a requirement that always applies needs no test that leaves a trigger absent, and that CAT's
	 * core-task requirement always applies.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void alwaysApplyingNeedsNoAbsentTest() throws InvalidProposalException
	{
		// Requirement: R-D17
		Contract contract = deriver.proposal(session(fixture("derivation.json")), PROFILES, SALT).contract();

		for (Requirement requirement : contract.requirements())
			requireThat(requirement.trigger(), requirement.id() + ".trigger()").isNull();
		requireThat(contract.requirements().getLast(), "coreTask").isEqualTo(Requirement.CORE_TASK);
		for (Category category : contract.categories())
		{
			for (TestDefinition test : category.tests())
				requireThat(test.absentTriggers(), test.id() + ".absentTriggers()").isEmpty();
		}
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
				{"r1"},
				{"core_task"}
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
		String output = changedDerivation(root -> ((ObjectNode) root.get("categories").get(0).get("tests").get(1)).
			putArray("absent_triggers").add(requirementId));
		try
		{
			deriver.proposal(session(output), PROFILES, SALT);
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
	 * Verifies that the deriver is asked for each requirement's trigger and for tests that leave each trigger
	 * absent, and that its output schema requires both.
	 */
	@Test
	public void requestAsksForTriggersAndAbsentTests()
	{
		// Requirement: R-D17
		String prompt = deriver.request(PROMPT_SYSTEM, PROFILES).turns().getFirst();

		requireThat(prompt, "prompt").contains("`trigger`").contains("`absent_triggers`");
		JsonNode outputSchema = readOutputSchema(prompt);
		List<String> requirement = outputSchema.get("properties").get("requirements").get("items").get("required").
			valueStream().map(JsonNode::asString).toList();
		requireThat(requirement, "requirement.required").contains("trigger");
		List<String> test = outputSchema.get("properties").get("categories").get("items").get("properties").
			get("tests").get("items").get("required").valueStream().map(JsonNode::asString).toList();
		requireThat(test, "test.required").contains("absent_triggers");
	}

	/**
	 * Verifies that the deriver is asked for each requirement's failure behaviors and for the behavior that each check
	 * detects, that its output schema requires both, and that CAT gives the core-task requirement its own behaviors.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void requestAsksForFailureBehaviors() throws InvalidProposalException
	{
		// Requirement: R-C18
		String prompt = deriver.request(PROMPT_SYSTEM, PROFILES).turns().getFirst();

		requireThat(prompt, "prompt").contains("`behaviors`").contains("the `behavior` it detects");
		JsonNode outputSchema = readOutputSchema(prompt);
		List<String> requirement = outputSchema.get("properties").get("requirements").get("items").get("required").
			valueStream().map(JsonNode::asString).toList();
		requireThat(requirement, "requirement.required").contains("behaviors");
		List<String> check = outputSchema.get("$defs").get("check").get("required").valueStream().
			map(JsonNode::asString).toList();
		requireThat(check, "check.required").contains("behavior");

		Contract contract = deriver.proposal(session(fixture("derivation-with-checks.json")), PROFILES, SALT).
			contract();
		requireThat(contract.requirements().getLast().behaviors(), "coreTask.behaviors()").
			isEqualTo(List.of("incorrect_result", "incomplete_result"));
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

		requireThat(text, "text").
			contains("  Applies: always\n  Failure behaviors: deleted_without_asking\n").
			contains("  Applies: always\n  Failure behaviors: incorrect_result, incomplete_result\n");
	}

	/**
	 * Verifies that the proposal lists each test's key dimensions and the number of key-value combinations in each
	 * part, and that confirmation freezes the salt under which they were counted.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void rendersKeyDimensionsAndPartCounts() throws InvalidProposalException
	{
		// Requirement: R-C16
		// Requirement: R-C1
		Proposal proposal = proposal(noLinks(), HostLogin.API_KEY);
		String text = proposal.render();

		requireThat(text, "text").
			contains("- Key dimensions: every dimension (`tone`, `audience`)\n" +
				"- Key-value combinations: 3 in the development part, 3 in the held-out part\n").
			contains("- Key dimensions: every dimension (`kind`)\n" +
				"- Key-value combinations: 2 in the development part, 2 in the held-out part\n").
			contains("- Key dimensions: `target`\n" +
				"- Key-value combinations: 4 in the development part, 4 in the held-out part\n");
		CampaignEvent.Confirm confirm = (CampaignEvent.Confirm) proposal.confirmation(7,
			CertificationScope.LATEST_VERSIONS).getFirst();
		requireThat(confirm.contract().salt(), "salt").isEqualTo(SALT);
	}

	/**
	 * Verifies that confirmation is refused while a test has fewer than 2 key-value combinations in either part, and
	 * that the refusal names the test and both counts.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void refusesTestWithTooFewCombinationsInAPart() throws InvalidProposalException
	{
		// Requirement: R-C16
		// Requirement: R-C1
		String output = changedDerivation(root ->
		{
			ArrayNode values = (ArrayNode) root.get("categories").get(0).get("tests").get(1).get("variations").get(0).
				get("values");
			values.remove(3);
			values.remove(2);
			((ObjectNode) values.get(0)).put("probability", 0.5);
			((ObjectNode) values.get(1)).put("probability", 0.5);
		});
		DerivedProposal derived = deriver.proposal(session(output), PROFILES, SALT);
		Proposal proposal = Proposal.of(derived, new Builder().build(), noLinks(), HostLogin.API_KEY,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
		TestDefinition plain = derived.contract().findTest("plain_list").orElseThrow();
		int development = derived.contract().split().combinations(plain, Part.DEVELOPMENT).size();
		int heldOut = derived.contract().split().combinations(plain, Part.HELD_OUT).size();
		requireThat(development + heldOut, "combinations").isEqualTo(2);

		for (CertificationScope scope : List.of(CertificationScope.LATEST_VERSIONS, CertificationScope.WEAKEST_CELL))
		{
			requireThat(proposal.isConfirmable(scope), scope.id()).isFalse();
			requireThat(proposal.confirmationRefusal(scope), scope.id() + ".refusal").
				contains("The test `plain_list` of the category `formatting` has " + development +
					" key-value combinations in the development part and " + heldOut + " in the held-out part").
				doesNotContain("styled_summary").doesNotContain("delete_directory");
		}
		requireThat(proposal.render(), "render").contains("- Key-value combinations: " + development +
			" in the development part, " + heldOut + " in the held-out part\n" +
			"  Confirmation is refused until each part holds at least 2. Ask for more key values in revision " +
			"feedback.\n");
		Proposal fixture = proposal(noLinks(), HostLogin.API_KEY);
		requireThat(fixture.isConfirmable(CertificationScope.LATEST_VERSIONS), "fixtureConfirmable").isTrue();
		requireThat(fixture.render(), "fixtureRender").doesNotContain("Ask for more key values");
	}

	/**
	 * Verifies that the deriver cannot supply the salt, which CAT draws, and that the derived contract carries the
	 * salt that CAT supplied.
	 *
	 * @throws InvalidProposalException if the unchanged output is rejected
	 */
	@Test
	public void saltComesFromCat() throws InvalidProposalException
	{
		// Requirement: R-C16
		requireThat(deriver.proposal(session(fixture("derivation.json")), PROFILES, 99L).contract().salt(), "salt").
			isEqualTo(99L);
		String output = changedDerivation(derivation -> derivation.put("salt", 5));
		try
		{
			deriver.proposal(session(output), PROFILES, SALT);
			requireThat(false, "rejected").isTrue();
		}
		catch (InvalidProposalException e)
		{
			requireThat(String.join("\n", e.problems()), "problems").contains("salt");
		}
	}

	/**
	 * Verifies that the deriver is asked for variation dimensions on every test and for key dimensions that give
	 * each part at least 2 key-value combinations, and that its output schema requires the key dimensions but holds
	 * no salt.
	 */
	@Test
	public void requestAsksForKeyDimensions()
	{
		// Requirement: R-C16
		String prompt = deriver.request(PROMPT_SYSTEM, PROFILES).turns().getFirst();

		requireThat(prompt, "prompt").contains("`key_dimensions`").contains("held-out part").
			contains("at least 2 key-value combinations");
		JsonNode outputSchema = readOutputSchema(prompt);
		List<String> test = outputSchema.get("properties").get("categories").get("items").get("properties").
			get("tests").get("items").get("required").valueStream().map(JsonNode::asString).toList();
		requireThat(test, "test.required").contains("key_dimensions").contains("variations");
		requireThat(outputSchema.get("properties").has("salt"), "hasSalt").isFalse();
	}
}
