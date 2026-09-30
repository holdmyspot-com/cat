package io.github.cowwoc.cat.common.optimizer.contract;

import io.github.cowwoc.cat.common.optimizer.loading.LoadMode;
import io.github.cowwoc.cat.common.optimizer.candidate.EditableRoot;
import io.github.cowwoc.cat.common.optimizer.loading.LoadedPromptContext;
import io.github.cowwoc.cat.common.optimizer.loading.PromptLoadingEvidence;
import io.github.cowwoc.cat.common.optimizer.loading.PromptLoadingEvidenceValidator;
import io.github.cowwoc.cat.common.optimizer.loading.PromptSourceBinding;
import io.github.cowwoc.cat.common.optimizer.loading.PromptSourceReceipt;
import io.github.cowwoc.cat.common.optimizer.loading.SourceOrigin;
import io.github.cowwoc.cat.common.optimizer.loading.ValidatedPromptLoading;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLocator;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLookupKey;
import io.github.cowwoc.cat.common.optimizer.state.CampaignCreationRequest;
import io.github.cowwoc.cat.common.optimizer.state.InitialTargetCaptureService;
import io.github.cowwoc.cat.common.optimizer.state.ProspectiveCampaignState;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Shared exact-byte C4 fixtures. */
public final class ContractTestFixtures
{
	private static final byte[] TARGET_BYTES = "target prompt".getBytes(StandardCharsets.UTF_8);
	private static final byte[] RULE_BYTES = "loaded rule".getBytes(StandardCharsets.UTF_8);

	/** Prevents construction. */
	private ContractTestFixtures()
	{
	}

	/**
	 * Builds a two-category contract graph without registering a campaign or retaining source files.
	 *
	 * @param contractId contract identity used by downstream records
	 * @return contract data for sampling and policy tests
	 * @throws IOException if source identities cannot be computed or validated
	 */
	public static ConfirmedContract createInMemoryContract(String contractId) throws IOException
	{
		String targetHash = sha256(TARGET_BYTES);
		String ruleHash = sha256(RULE_BYTES);
		Map<String, byte[]> contents = Map.of(targetHash, TARGET_BYTES, ruleHash, RULE_BYTES);
		ValidatedPromptLoading loading = PromptLoadingEvidenceValidator.validate(
			createLoadingEvidence("conversation", targetHash, ruleHash), contents::get);
		ContractProposal proposal = proposal("proposal", loading, "Expected first");
		return new ConfirmedContract(ConfirmedContract.SCHEMA_VERSION, contractId, proposal.proposalId(),
			proposal.promptSystem(), proposal.requirements(), proposal.categories(), proposal.tests(),
			proposal.developmentAllocationPolicy(), proposal.developmentReadiness(), proposal.developmentRoundPolicy(),
			proposal.formalProcedure(), proposal.evaluator(), proposal.limitations());
	}

	/**
	 * Creates an initialized store and a complete recorded proposal.
	 *
	 * @param directory campaign directory
	 * @return complete recorded fixture
	 * @throws IOException if evidence cannot be persisted
	 */
	public static RecordedFixture recorded(Path directory) throws IOException
	{
		CampaignStore store = new CampaignStore(directory);
		ProspectiveCampaignState.initialize(store, "campaign");
		return recordIn(store);
	}

	/**
	 * Creates a registered campaign whose target exactly matches the prompt-source fixture.
	 *
	 * @param fixtureRoot existing test container for the project and workflow directories
	 * @return registered campaign and complete proposal
	 * @throws IOException if campaign registration or proposal recording fails
	 */
	public static RecordedFixture registered(Path fixtureRoot) throws IOException
	{
		return registered(fixtureRoot, project -> {});
	}

	/**
	 * Creates a registered campaign after adding test-owned project files to its first Git commit.
	 *
	 * @param fixtureRoot existing test container for the project and workflow directories
	 * @param files additional test-owned files created before campaign registration
	 * @return registered campaign and complete proposal
	 * @throws IOException if project setup, registration, or proposal recording fails
	 */
	public static RecordedFixture registered(Path fixtureRoot, ProjectFiles files) throws IOException
	{
		Path project = Files.createDirectory(fixtureRoot.resolve("project"));
		Path workflow = Files.createDirectory(fixtureRoot.resolve("workflow"));
		git(project, "init", "-q");
		Files.write(project.resolve("prompt.md"), TARGET_BYTES);
		Files.write(project.resolve("rules.md"), RULE_BYTES);
		files.create(project);
		git(project, "add", ".");
		git(project, "-c", "user.email=test@example.invalid", "-c", "user.name=Contract Test",
			"commit", "-q", "-m", "initial");
		CampaignLocator locator = new CampaignLocator(workflow);
		Path campaignDirectory = locator.createOrFind(new CampaignLookupKey(project, Path.of("prompt.md")),
			new CampaignCreationRequest("campaign", ProspectiveCampaignState.profile())).handle().campaignDirectory();
		CampaignStore store = CampaignStore.open(campaignDirectory);
		new InitialTargetCaptureService(locator, store).capture(store.binding());
		return recordIn(store);
	}

	/**
	 * Adds test-owned project files before the fixture's first Git commit.
	 */
	@FunctionalInterface
	public interface ProjectFiles
	{
		/**
		 * Creates the additional files in the new project root.
		 *
		 * @param project new fixture project root
		 * @throws IOException if a file cannot be created
		 */
		void create(Path project) throws IOException;
	}

	/**
	 * Runs one fixture Git command in the isolated project.
	 *
	 * @param project fixture worktree
	 * @param arguments Git arguments
	 * @throws IOException if the command fails or is interrupted
	 */
	private static void git(Path project, String... arguments) throws IOException
	{
		List<String> command = new java.util.ArrayList<>();
		command.add("git");
		command.addAll(List.of(arguments));
		try (Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).
			start())
		{
			byte[] output = process.getInputStream().readAllBytes();
			if (process.waitFor() != 0)
				throw new IOException("Git fixture command failed: " + new String(output, StandardCharsets.UTF_8));
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Git fixture command was interrupted", e);
		}
	}

	/**
	 * Records a complete proposal in an already initialized campaign.
	 *
	 * @param store initialized campaign store
	 * @return complete recorded fixture
	 * @throws IOException if evidence cannot be persisted
	 */
	public static RecordedFixture recordIn(CampaignStore store) throws IOException
	{
		ValidatedPromptLoading loading = loading(store, "conversation", TARGET_BYTES, RULE_BYTES);
		ContractProposal proposal = identified(proposal("pending", loading, "Expected first"));
		ContractService service = new ContractService(store);
		StoredProposal stored = service.recordProposal(bytes(proposal), loading);
		return new RecordedFixture(store, service, loading, proposal, stored);
	}

	/**
	 * Captures and validates two exact prompt sources.
	 *
	 * @param store campaign evidence store
	 * @param conversationId capture conversation identity
	 * @param targetBytes exact target bytes
	 * @param ruleBytes exact rule bytes
	 * @return validated loading graph
	 * @throws IOException if evidence cannot be persisted or read
	 */
	static ValidatedPromptLoading loading(CampaignStore store, String conversationId, byte[] targetBytes,
		byte[] ruleBytes) throws IOException
	{
		String targetHash = sha256(targetBytes);
		String ruleHash = sha256(ruleBytes);
		store.appendImmutableBytes("prompt-source-content", targetBytes);
		store.appendImmutableBytes("prompt-source-content", ruleBytes);
		return PromptLoadingEvidenceValidator.validate(createLoadingEvidence(conversationId, targetHash, ruleHash),
			id -> store.readImmutableBytes("prompt-source-content", id));
	}

	/**
	 * Describes the same ordered target and rule receipts for memory and persisted fixture readers.
	 *
	 * @param conversationId captured conversation identity
	 * @param targetHash target byte identity
	 * @param ruleHash rule byte identity
	 * @return ordered loading evidence
	 */
	private static PromptLoadingEvidence createLoadingEvidence(String conversationId, String targetHash, String ruleHash)
	{
		List<PromptSourceReceipt> receipts = List.of(
			new PromptSourceReceipt(conversationId, "target", SourceOrigin.TARGET, targetHash, targetHash, 0, null,
				LoadMode.DIRECT),
			new PromptSourceReceipt(conversationId, "rule", SourceOrigin.PROJECT_RULE, ruleHash, ruleHash, 1,
				"target", LoadMode.CONDITIONAL));
		return new PromptLoadingEvidence(Path.of("prompt.md"),
			new LoadedPromptContext("loaded", receipts), List.of(
				new PromptSourceBinding("target", Path.of("prompt.md")),
				new PromptSourceBinding("rule", Path.of("rules.md"))));
	}

	/**
	 * Creates a complete proposal bound to loading evidence.
	 *
	 * @param proposalId declared proposal identity
	 * @param loading validated source graph
	 * @param expectedOutcome first test expected result
	 * @return complete proposal
	 */
	static ContractProposal proposal(String proposalId, ValidatedPromptLoading loading, String expectedOutcome)
	{
		PromptSystem system = new PromptSystem(loading.targetPath(), loading.sources(), List.of(
			new EditableRoot(loading.targetPath(), EditableRoot.Kind.FILE)));
		Requirement first = new Requirement("first-requirement", "First obligation",
			new SourceLocation(Path.of("prompt.md"), "1"));
		Requirement second = new Requirement("second-requirement", "Second obligation",
			new SourceLocation(Path.of("rules.md"), "2"));
		Category firstCategory = new Category("first", "First separate category", List.of(first.id()));
		Category secondCategory = new Category("second", "Second separate category", List.of(second.id()));
		List<TestDefinition> tests = List.of(test("first-test", "first", first.id(), "always",
			List.of("Task {{first}}"), expectedOutcome, "first", "{{first}}", List.of(
				new VariationValue("one", BigDecimal.ONE))),
			test("second-test", "second", second.id(), "always", List.of("Task {{second}}"), "Expected second",
				"second", "{{second}}", List.of(new VariationValue("one", BigDecimal.ONE))));
		return new ContractProposal(ContractProposal.SCHEMA_VERSION, proposalId, loading.loadingEvidenceId(), system,
			List.of(first, second), List.of(firstCategory, secondCategory), tests,
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE, readiness("first", "second"),
			roundPolicy(), procedure(), evaluator(), List.of("known limitation"));
	}

	/**
	 * Creates one complete review proposal for a source selected by an adopted inspection.
	 *
	 * @param loadingEvidenceId immutable inspected-source evidence identity
	 * @param source requested source with H1 retained-byte identity
	 * @param editableRoot reviewed resolved-file edit root
	 * @return canonical proposal for the inspected source
	 * @throws IOException if the proposal cannot be serialized for its identity
	 */
	public static ContractProposal inspectedProposal(String loadingEvidenceId, PromptSource source,
		EditableRoot editableRoot) throws IOException
	{
		Requirement requirement = new Requirement("selected-requirement", "Selected source obligation",
			new SourceLocation(source.path(), "1"));
		Category category = new Category("selected", "Selected source behavior", List.of(requirement.id()));
		TestDefinition test = test("selected-test", category.id(), requirement.id(), "always",
			List.of("Task {{selected}}"), "Expected selected behavior", "selected", "{{selected}}",
			List.of(new VariationValue("one", BigDecimal.ONE)));
		ContractProposal pending = new ContractProposal(ContractProposal.SCHEMA_VERSION, "pending",
			loadingEvidenceId, new PromptSystem(source.path(), List.of(source), List.of(editableRoot)),
			List.of(requirement), List.of(category), List.of(test),
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE, readiness(category.id()), roundPolicy(), procedure(),
			evaluator(), List.of("Native prompt-source coverage is limited"));
		return identified(pending);
	}

	/**
	 * Creates a reviewed proposal with the selected target and one observed, read-only project rule.
	 *
	 * @param loadingEvidenceId immutable inspected-source evidence identity
	 * @param selectedSource requested source with retained bytes
	 * @param ruleSource observed rule with retained bytes
	 * @param editableRoot reviewed target edit root
	 * @return canonical proposal containing both source obligations
	 * @throws IOException if the proposal cannot be serialized for its identity
	 */
	public static ContractProposal inspectedProposalWithRule(String loadingEvidenceId,
		PromptSource selectedSource, PromptSource ruleSource, EditableRoot editableRoot) throws IOException
	{
		ContractProposal selected = inspectedProposal(loadingEvidenceId, selectedSource, editableRoot);
		Requirement ruleRequirement = new Requirement("rule-requirement", "Observed rule obligation",
			new SourceLocation(ruleSource.path(), "1"));
		Category ruleCategory = new Category("rule", "Observed rule behavior", List.of(ruleRequirement.id()));
		TestDefinition ruleTest = test("rule-test", ruleCategory.id(), ruleRequirement.id(), "always",
			List.of("Task {{rule}}"), "Expected rule behavior", "rule", "{{rule}}",
			List.of(new VariationValue("one", BigDecimal.ONE)));
		ContractProposal pending = new ContractProposal(ContractProposal.SCHEMA_VERSION, "pending",
			loadingEvidenceId, new PromptSystem(selectedSource.path(), List.of(selectedSource, ruleSource),
				List.of(editableRoot)),
			List.of(selected.requirements().getFirst(), ruleRequirement),
			List.of(selected.categories().getFirst(), ruleCategory), List.of(selected.tests().getFirst(), ruleTest),
			selected.developmentAllocationPolicy(), readiness("selected", "rule"),
			selected.developmentRoundPolicy(), selected.formalProcedure(),
			selected.evaluator(), selected.limitations());
		return identified(pending);
	}

	/**
	 * Replaces a reviewed proposal's source list while retaining its other obligations.
	 *
	 * @param proposal reviewed proposal to vary
	 * @param sources replacement source identities
	 * @return identified proposal with the replacement source list
	 * @throws IOException if the revised proposal cannot be serialized for its identity
	 */
	public static ContractProposal withSources(ContractProposal proposal, List<PromptSource> sources)
		throws IOException
	{
		PromptSystem promptSystem = proposal.promptSystem();
		ContractProposal pending = new ContractProposal(ContractProposal.SCHEMA_VERSION, "pending",
			proposal.loadingEvidenceId(), new PromptSystem(promptSystem.targetPath(), sources,
				promptSystem.editableRoots()), proposal.requirements(), proposal.categories(), proposal.tests(),
			proposal.developmentAllocationPolicy(), proposal.developmentReadiness(),
			proposal.developmentRoundPolicy(), proposal.formalProcedure(),
			proposal.evaluator(),
			proposal.limitations());
		return identified(pending);
	}

	/**
	 * Creates a test definition.
	 *
	 * @param id test identity
	 * @param categoryId category identity
	 * @param requirementId covered requirement
	 * @param applicability applicability predicate
	 * @param turns ordered turn templates
	 * @param expected expected result
	 * @param dimensionId variation dimension identity
	 * @param placeholder variation placeholder
	 * @param values variation values
	 * @return test definition
	 */
	static TestDefinition test(String id, String categoryId, String requirementId, String applicability,
		List<String> turns, String expected, String dimensionId, String placeholder, List<VariationValue> values)
	{
		return new TestDefinition(id, categoryId, List.of(requirementId), applicability, turns, expected,
			SessionMode.SINGLE_TURN, List.of("tool"), List.of(new VariationDimension(dimensionId, placeholder, values)));
	}

	/**
	 * Creates readiness requirements for the named categories.
	 *
	 * @param categoryIds category identities
	 * @return readiness definition
	 */
	static DevelopmentReadiness readiness(String... categoryIds)
	{
		Map<String, Integer> evidence = new java.util.LinkedHashMap<>();
		for (String categoryId : categoryIds)
			evidence.put(categoryId, 100);
		return new DevelopmentReadiness(new BigDecimal("0.99"), evidence, new BigDecimal("0.98"), 2,
			new BigDecimal("0.95"), new BigDecimal("0.95"), 200,
			ComparisonPolicy.SORTED_READINESS_DEFICIT_THEN_PARETO);
	}

	/**
	 * Supplies finite diagnosis and generation limits for contract fixtures.
	 *
	 * @return development round limits
	 */
	static DevelopmentRoundPolicy roundPolicy()
	{
		return new DevelopmentRoundPolicy(3, 1);
	}

	/**
	 * Creates the complete fixed formal procedure.
	 *
	 * @return formal procedure
	 */
	static FormalProcedure procedure()
	{
		List<DependenceCondition> conditions = List.of(DependenceSource.SHARED_SESSION,
			DependenceSource.PROVIDER_CACHE, DependenceSource.THROTTLING, DependenceSource.EVALUATOR_MEMORY,
			DependenceSource.ADAPTIVE_ROUTING).stream().map(source -> new DependenceCondition(source,
			DependenceDisposition.CONTROLLED, "evidence-" + source)).toList();
		return new FormalProcedure(FormalMethod.ANYTIME_BERNOULLI, new BigDecimal("0.95"), new BigDecimal("0.99"),
			new BigDecimal("0.90"), "0.05/(i*(i+1))",
			FreshSessionPolicy.FRESH_PER_LOGICAL_TRIAL, new FormalAssumptions(
				OutcomeModel.IID_BERNOULLI_WITHIN_CATEGORY, StoppingRule.ANYTIME_FROZEN_CAP, conditions));
	}

	/**
	 * Creates the frozen evaluator definition.
	 *
	 * @return evaluator definition
	 */
	static EvaluatorDefinition evaluator()
	{
		return new EvaluatorDefinition("model", "reasoning", "rubric", "rule",
			EvaluatorResultSchema.LABEL_AND_REASON_JSON_V1);
	}

	/**
	 * Assigns the canonical identity to a pending proposal.
	 *
	 * @param pending proposal whose identity is {@code pending}
	 * @return identified proposal
	 * @throws IOException if canonical serialization fails
	 */
	static ContractProposal identified(ContractProposal pending) throws IOException
	{
		ContractProposal unsigned = copy(pending, "pending");
		return copy(pending, sha256(bytes(unsigned)));
	}

	/**
	 * Revises a proposal's review checkpoint while retaining its other confirmed choices.
	 *
	 * @param base complete proposal to revise
	 * @param checkpoint representative count that prompts an unresolved-category review
	 * @return complete proposal with a canonical identity
	 * @throws IOException if the revised proposal cannot be serialized
	 */
	public static ContractProposal withReviewCheckpoint(ContractProposal base, int checkpoint) throws IOException
	{
		DevelopmentReadiness original = base.developmentReadiness();
		DevelopmentReadiness readiness = new DevelopmentReadiness(original.targetRate(),
			original.minimumEvidence(), original.recentBatchMinimumRate(), original.recentBatchCount(),
			original.weakUpperEndpointThreshold(), original.weakIntervalConfidenceLevel(), checkpoint,
			original.comparisonPolicy());
		ContractProposal changed = new ContractProposal(ContractProposal.SCHEMA_VERSION, "pending",
			base.loadingEvidenceId(), base.promptSystem(), base.requirements(), base.categories(), base.tests(),
			base.developmentAllocationPolicy(), readiness, base.developmentRoundPolicy(), base.formalProcedure(),
			base.evaluator(), base.limitations());
		return identified(changed);
	}

	/**
	 * Revises the weakness threshold while retaining the proposal's other confirmed choices.
	 *
	 * @param base complete proposal to revise
	 * @param threshold upper interval endpoint below which a category is weak
	 * @return complete proposal with a canonical identity
	 * @throws IOException if the revised proposal cannot be serialized
	 */
	public static ContractProposal withWeakThreshold(ContractProposal base, BigDecimal threshold) throws IOException
	{
		DevelopmentReadiness original = base.developmentReadiness();
		DevelopmentReadiness readiness = new DevelopmentReadiness(original.targetRate(),
			original.minimumEvidence(), original.recentBatchMinimumRate(), original.recentBatchCount(),
			threshold, original.weakIntervalConfidenceLevel(), original.reviewCheckpoint(),
			original.comparisonPolicy());
		ContractProposal changed = new ContractProposal(ContractProposal.SCHEMA_VERSION, "pending",
			base.loadingEvidenceId(), base.promptSystem(), base.requirements(), base.categories(), base.tests(),
			base.developmentAllocationPolicy(), readiness, base.developmentRoundPolicy(), base.formalProcedure(),
			base.evaluator(), base.limitations());
		return identified(changed);
	}

	/**
	 * Keeps the first category, requirement, and test when a fixture needs one coverage batch.
	 *
	 * @param base complete proposal with a first category and its test
	 * @return identified proposal whose initial coverage requires one category batch
	 * @throws IOException if the revised proposal cannot be serialized
	 */
	public static ContractProposal withFirstCategoryOnly(ContractProposal base) throws IOException
	{
		Category category = base.categories().getFirst();
		ContractProposal changed = new ContractProposal(ContractProposal.SCHEMA_VERSION, "pending",
			base.loadingEvidenceId(), base.promptSystem(), List.of(base.requirements().getFirst()),
			List.of(category), List.of(base.tests().getFirst()), base.developmentAllocationPolicy(),
			readiness(category.id()), base.developmentRoundPolicy(), base.formalProcedure(), base.evaluator(),
			base.limitations());
		return identified(changed);
	}

	/**
	 * Copies a proposal with a new identity.
	 *
	 * @param proposal source proposal
	 * @param proposalId replacement identity
	 * @return copied proposal
	 */
	static ContractProposal copy(ContractProposal proposal, String proposalId)
	{
		return new ContractProposal(ContractProposal.SCHEMA_VERSION, proposalId, proposal.loadingEvidenceId(),
			proposal.promptSystem(), proposal.requirements(), proposal.categories(), proposal.tests(),
			proposal.developmentAllocationPolicy(), proposal.developmentReadiness(),
			proposal.developmentRoundPolicy(), proposal.formalProcedure(),
			proposal.evaluator(), proposal.limitations());
	}

	/**
	 * Serializes a value with the production canonical mapper.
	 *
	 * @param value value to serialize
	 * @return canonical bytes
	 * @throws IOException if serialization fails
	 */
	public static byte[] bytes(Object value) throws IOException
	{
		return json().writeValueAsBytes(value);
	}

	/**
	 * Returns the production canonical mapper.
	 *
	 * @return canonical mapper
	 */
	static JsonMapper json()
	{
		return ContractJson.mapper();
	}

	/**
	 * Computes an exact byte identity.
	 *
	 * @param content bytes to identify
	 * @return lowercase SHA-256 identity
	 * @throws IOException if SHA-256 is unavailable
	 */
	static String sha256(byte[] content) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Complete recorded C4 fixture.
	 *
	 * @param store campaign store
	 * @param service contract service
	 * @param loading validated production loading
	 * @param proposal identified proposal
	 * @param stored persisted proposal result
	 */
	public record RecordedFixture(CampaignStore store, ContractService service, ValidatedPromptLoading loading,
		ContractProposal proposal, StoredProposal stored)
	{
	}
}
