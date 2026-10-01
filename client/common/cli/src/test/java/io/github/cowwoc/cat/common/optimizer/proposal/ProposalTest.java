package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignTransitions;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationProcedure;
import io.github.cowwoc.cat.common.optimizer.campaign.ContractIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
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

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link DeriverProtocol} and {@link Proposal}: deriver output in, the complete proposal out.
 */
public final class ProposalTest
{
	private static final Path PROMPT_SYSTEM = Path.of("/staged/incumbent");
	private static final String OBSERVED = "hook: .claude/hooks/session-start.sh\nskill: release-notes";
	private static final String JUDGE_MODEL = "judge-model-from-cat";
	private final JsonMapper json = JsonMapper.builder().build();
	private final ProposalSchema schema = new ProposalSchema(json);
	private final JudgeTemplate judge = new JudgeTemplate(json, JUDGE_MODEL);
	private final DeriverProtocol deriver = new DeriverProtocol(json, schema, judge);

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
			List.of("I will inspect the prompt system.", response), Path.of("/runs/deriver/project"), "model", null,
			null, List.of(), OBSERVED);
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
		return new Contract(fixture.requirements(), fixture.categories(),
			judge.profile("The response follows the requirement.", "Pass only if every rubric item holds."),
			fixture.fixtures(), fixture.limitations());
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
		DerivedProposal derived = deriver.proposal(session(fixture("derivation.json")));
		return Proposal.of(derived, new CampaignBuilder().build(), links, login,
			new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
	}

	/**
	 * Returns a link report with no links.
	 *
	 * @return the report
	 */
	private static LinkReport noLinks()
	{
		return LinkReport.of(new FileInventory(Path.of("/project"), Map.of()),
			new Authority(Set.of(Path.of("prompts")), Set.of(), Set.of()));
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
		return LinkReport.of(new FileInventory(Path.of("/project"), entries),
			new Authority(Set.of(Path.of("prompts")), Set.of(), Set.of()));
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
		// Requirement: R-E13
		SessionRequest request = deriver.request(PROMPT_SYSTEM);

		requireThat(request.role(), "role").isEqualTo(SessionRole.DERIVER);
		requireThat(request.source(), "source").isEqualTo(PROMPT_SYSTEM);
		requireThat(request.turns().size(), "turns.size()").isEqualTo(2);
		String prompt = request.turns().getFirst();
		requireThat(prompt, "prompt").contains("Previous proposal:\n\nnone\n").contains("User feedback:\n\nnone\n").
			contains("do not write the proposal yet");
		requireThat(request.turns().getLast(), "finalTurn").contains("Send the complete proposal now").
			contains("its first character is `{` and its last character is `}`");
		JsonNode outputSchema = json.readTree(prompt.substring(prompt.indexOf('{', prompt.indexOf("### Output"))));
		JsonNode judgeProperties = outputSchema.get("properties").get("judge").get("properties");
		requireThat(List.copyOf(judgeProperties.propertyNames()), "judge.properties").
			isEqualTo(List.of("rubric", "decision_rule"));
		List<String> required = outputSchema.get("required").valueStream().map(JsonNode::asString).toList();
		requireThat(required, "required").contains("derived_sources").contains("scope_conditions").
			contains("requirements");
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
		DerivedProposal derived = deriver.proposal(session("```json\n" + fixture("derivation.json") + "```\n"));

		requireThat(derived.contract(), "contract").isEqualTo(expectedContract());
		requireThat(derived.contract().judge().model(), "judge.model").isEqualTo(JUDGE_MODEL);
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
	 * Verifies that a bare deriver output is accepted as well as a fenced one.
	 *
	 * @throws InvalidProposalException if the output is rejected
	 */
	@Test
	public void derivesTheProposalFromBareOutput() throws InvalidProposalException
	{
		// Requirement: R-E13
		DerivedProposal derived = deriver.proposal(session(fixture("derivation.json")));

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
			deriver.proposal(session(fixture("derivation-aggregate.json")));
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
			deriver.proposal(session(fixture("derivation-with-judge-fields.json")));
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
			deriver.proposal(session("Here is the proposal:\n" + fixture("derivation.json")));
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
			deriver.proposal(session(output));
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
		DerivedProposal first = deriver.proposal(session(fixture("derivation.json")));
		String feedback = "Split the safety category by the kind of directory.";

		SessionRequest request = deriver.request(PROMPT_SYSTEM, first, feedback);

		requireThat(request.role(), "role").isEqualTo(SessionRole.DERIVER);
		requireThat(request.source(), "source").isEqualTo(PROMPT_SYSTEM);
		requireThat(request.turns().size(), "turns.size()").isEqualTo(2);
		String prompt = request.turns().getFirst();
		requireThat(prompt, "prompt").contains("User feedback:\n\n" + feedback + "\n").contains(first.derivation());
		requireThat(json.readTree(first.derivation()), "derivation").
			isEqualTo(json.readTree(fixture("derivation.json")));

		String revised = changedDerivation(root -> ((ObjectNode) root.get("requirements").get(1)).
			put("description", "Never delete a directory or a file without asking."));
		Proposal second = Proposal.of(deriver.proposal(session(revised)), new CampaignBuilder().build(), noLinks(),
			HostLogin.API_KEY, new FixedCalibrator(FixedCalibrator.STANDARD_CAP), schema);
		requireThat(second.render(), "render").contains("Never delete a directory or a file without asking.").
			contains("styled-summary").contains("delete-directory").contains("## Statistical procedure");
	}

	/**
	 * Verifies that the rendered proposal lists every item of design section 6.1 itself, not a count of the items.
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
				contains("Why it needs its own claim: " + category.rationale());
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
			contains("Model: " + JUDGE_MODEL).
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
			contains("50 representative trials in every category").
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
		DerivedProposal derived = deriver.proposal(session(fixture("derivation.json")));
		Proposal proposal = Proposal.of(derived, new CampaignBuilder().build(), noLinks(), HostLogin.API_KEY,
			level ->
			{
				throw new CapCalibrationException("The calibration found no cap");
			}, schema);

		requireThat(proposal.render(), "render").
			contains("Attempt 1: α = 1/50 (2.00%), cap unavailable: The calibration found no cap");
	}

	/**
	 * Verifies that a subscription login, and a login whose kind CAT cannot tell (premise P6's fallback), produce the
	 * warning with the minimum session count and the API key recommendation, and that an API key produces no warning.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void warnsUnlessTheLoginIsAnApiKey() throws InvalidProposalException
	{
		// Requirement: R-E7
		Proposal subscription = proposal(noLinks(), HostLogin.SUBSCRIPTION);
		int trials = minimumTrials(subscription);
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
	 * Returns the minimum remaining trials of a fresh campaign once the proposal is confirmed.
	 *
	 * @param proposal the proposal
	 * @return the trials
	 */
	private int minimumTrials(Proposal proposal)
	{
		Contract contract = proposal.contract();
		Campaign confirmed = CampaignTransitions.apply(new CampaignBuilder().build(),
			new CampaignEvent.Confirm(contract, ContractIdentity.of(schema, contract), 0));
		return RemainingWork.of(confirmed).total();
	}

	/**
	 * Verifies that the minimum session count counts one trial session and one judge session for each of the
	 * minimum remaining trials, with each category's share.
	 *
	 * @throws InvalidProposalException if the fixture is rejected
	 */
	@Test
	public void statesTheMinimumSessionCount() throws InvalidProposalException
	{
		// Requirement: R-E7
		// Requirement: R-C1
		Proposal proposal = proposal(noLinks(), HostLogin.API_KEY);
		int trials = minimumTrials(proposal);

		requireThat(trials, "trials").isPositive();
		requireThat(proposal.render(), "render").contains("## Minimum session count").
			contains("At least " + 2 * trials + " nested sessions remain if every trial passes: " + trials +
				" trials, each with one trial session and one judge session.").
			contains("- `formatting`: readiness ").contains("- `safety`: readiness ");
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
		requireThat(proposal.isConfirmable(), "isConfirmable").isTrue();
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

		requireThat(proposal.isConfirmable(), "isConfirmable").isFalse();
		requireThat(proposal.render(), "render").contains("- `loop-a.md`: the links form a cycle; this blocks " +
			"confirmation");
		proposal.confirmation(7);
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

		List<CampaignEvent> events = proposal.confirmation(7);

		Contract contract = proposal.contract();
		requireThat(events, "events").isEqualTo(List.of(
			new CampaignEvent.Confirm(contract, ContractIdentity.of(schema, contract), 7),
			new CampaignEvent.ReportUnavailable(Outcome.UNSUPPORTED_OUTPUT)));
		Campaign campaign = new CampaignBuilder().build();
		for (CampaignEvent event : events)
			campaign = CampaignTransitions.apply(campaign, event);
		requireThat(campaign.contract().judge(), "judge").isEqualTo(expectedContract().judge());
		requireThat(campaign.lineage().unavailable(), "unavailable").isEqualTo(Set.of(Outcome.UNSUPPORTED_OUTPUT));
	}
}
