package io.github.cowwoc.cat.common.optimization.certification;

import io.github.cowwoc.cat.common.runner.PromptInput;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.optimization.contract.CertificationContract;
import io.github.cowwoc.cat.common.optimization.development.PromptTestContractCatalog;
import io.github.cowwoc.cat.common.optimization.development.PromptTestGenerationPolicy;
import io.github.cowwoc.cat.common.optimization.development.PromptTestIdentityLedger;
import io.github.cowwoc.cat.common.optimization.development.PromptTestPoolPartition;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ReasoningEffort;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests one proof-directed formal scheduling and execution step.
 */
public final class FormalCertificationRoundTest
{
	private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public FormalCertificationRoundTest()
	{
	}

	/**
	 * Closes the shared JSON scope.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies the statistical decision selects the family and its formal distribution before harness execution.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void executesSelectedFormalFamily() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("formal-certification-round-"));
			FamilySequentialPolicyDocument.Loaded statisticalPolicy = statisticalPolicy(root);
			Path allowances = root.resolve("allowances.json");
			CandidateAllowanceLedger.initialize(scope.json(), allowances, "lineage-a", new BigDecimal("0.05"),
				CandidateAllowanceLedger.Schedule.HALVING_V1);
			CandidateAllowanceLedger.Assignment assignment = CandidateAllowanceLedger.assign(scope.json(), allowances,
				"candidate-a", Instant.parse("2026-09-01T11:00:00Z"));
			Path trials = root.resolve("trials.json");
			FormalTrialLedger.initialize(scope.json(), trials, new FormalTrialLedger.Identity("candidate-a", SHA, SHA, SHA,
				SHA, statisticalPolicy.fingerprint(), assignment.entryFingerprint()), List.of("family-a"));
			Path identities = root.resolve("identities.jsonl");
			PromptTestIdentityLedger.initialize(scope.json(), identities, SHA);
			PromptTestGenerationPolicy formalPolicy = formalPolicy();
			PromptTestContractCatalog catalog = PromptTestContractCatalog.bind(contract(formalPolicy), List.of(formalPolicy)).
				catalog().orElseThrow();
			AtomicInteger runnerInvocations = new AtomicInteger();

			FormalCertificationRound.Result result = FormalCertificationRound.execute(scope.json(),
				Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC), allowances, trials,
				statisticalPolicy, catalog, identities, 7, "formal-operation-1", (promptTest, operation) ->
				{
					Path evidence = Files.createDirectory(root.resolve("evidence"));
					return new HarnessEvaluationRequest(root.resolve("runner"), root.resolve("artifact"),
						root.resolve("project"), new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM),
						PromptInput.forPrompt(scope.json(), promptTest.promptTest().renderedPrompt()),
						root.resolve("transcript.jsonl"), root.resolve("runner-receipt.json"), false,
						List.of(evidence));
				}, request ->
				{
					runnerInvocations.incrementAndGet();
					Path retainedProject = Files.createDirectories(root.resolve("retained-project"));
					Path rawResult = Files.writeString(root.resolve("raw-result.jsonl"), "completed\n");
					return new HarnessEvaluationExecution(HarnessEvaluationResult.retained(retainedProject, rawResult), 10, 20);
				}, ignored -> new FormalTrialOperation.Evaluation(FormalTrialLedger.Verdict.PASS, SHA));

			requireThat(result.status(), "status").isEqualTo(FormalCertificationRound.Status.EXECUTED);
			requireThat(result.operationResult().orElseThrow().retainedOutcome().outcome().familyId(), "familyId").
				isEqualTo("family-a");
			requireThat(runnerInvocations.get(), "runnerInvocations").isEqualTo(1);
		}
	}

	/**
	 * Verifies a formal draw cannot reuse generated content observed by another evidence pool.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void blocksCrossPoolContentLeakage() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("formal-certification-collision-"));
			FamilySequentialPolicyDocument.Loaded statisticalPolicy = statisticalPolicy(root);
			Path allowances = root.resolve("allowances.json");
			CandidateAllowanceLedger.initialize(scope.json(), allowances, "lineage-a", new BigDecimal("0.05"),
				CandidateAllowanceLedger.Schedule.HALVING_V1);
			CandidateAllowanceLedger.Assignment assignment = CandidateAllowanceLedger.assign(scope.json(), allowances,
				"candidate-a", Instant.parse("2026-09-01T11:00:00Z"));
			Path trials = root.resolve("trials.json");
			FormalTrialLedger.initialize(scope.json(), trials, new FormalTrialLedger.Identity("candidate-a", SHA, SHA, SHA,
				SHA, statisticalPolicy.fingerprint(), assignment.entryFingerprint()), List.of("family-a"));
			Path identities = root.resolve("identities.jsonl");
			PromptTestIdentityLedger.initialize(scope.json(), identities, SHA);
			PromptTestGenerationPolicy formalPolicy = formalPolicy();
			PromptTestContractCatalog catalog = PromptTestContractCatalog.bind(contract(formalPolicy), List.of(formalPolicy)).
				catalog().orElseThrow();
			PromptTestGenerationPolicy.PromptTest formalPromptTest = formalPolicy.generate(7);
			PromptTestPoolPartition.Identity developmentIdentity = new PromptTestPoolPartition.Identity(
				PromptTestPoolPartition.Pool.DEVELOPMENT, "development-a", "development-seed-a",
				formalPromptTest.contentFingerprint(), "development-operation-a");
			PromptTestIdentityLedger.reserve(scope.json(), identities, developmentIdentity);
			AtomicInteger requestInvocations = new AtomicInteger();
			AtomicInteger runnerInvocations = new AtomicInteger();

			FormalCertificationRound.Result result = FormalCertificationRound.execute(scope.json(),
				Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC), allowances, trials,
				statisticalPolicy, catalog, identities, 7, "formal-operation-1", (promptTest, operation) ->
				{
					requestInvocations.incrementAndGet();
					throw new AssertionError("A colliding formal promptTest must not be materialized");
				}, request ->
				{
					runnerInvocations.incrementAndGet();
					throw new AssertionError("A colliding formal promptTest must not execute");
				}, ignored ->
				{
					throw new AssertionError("A colliding formal promptTest must not be evaluated");
				});

			requireThat(result.status(), "status").isEqualTo(FormalCertificationRound.Status.BLOCKED);
			requireThat(result.collisions().size(), "collisionCount").isEqualTo(1);
			requireThat(requestInvocations.get(), "requestInvocations").isEqualTo(0);
			requireThat(runnerInvocations.get(), "runnerInvocations").isEqualTo(0);
		}
	}

	/**
	 * Loads the fixed family-sequential policy fixture.
	 *
	 * @param root the fixture root
	 * @return the loaded policy
	 * @throws IOException if fixture persistence fails
	 */
	private FamilySequentialPolicyDocument.Loaded statisticalPolicy(Path root) throws IOException
	{
		Path document = Files.writeString(root.resolve("policy.json"),
			FamilySequentialPolicyDocumentTest.policyJson(""));
		return FamilySequentialPolicyDocument.read(scope.json(), document);
	}

	/**
	 * Creates one finite formal promptTest policy.
	 *
	 * @return the policy
	 */
	private static PromptTestGenerationPolicy formalPolicy()
	{
		return new PromptTestGenerationPolicy(PromptTestPoolPartition.Pool.FORMAL, "family-a", "distribution-a",
			"uniform-v1", "generator-a", "formal-a", "Complete the task in {{tone}} style.",
			Map.of("tone", List.of("plain", "direct", "brief")), Set.of(), 10);
	}

	/**
	 * Creates a frozen contract bound to one formal policy.
	 *
	 * @param policy the executable formal policy
	 * @return the frozen contract
	 */
	private static CertificationContract contract(PromptTestGenerationPolicy policy)
	{
		CertificationContract.Artifact artifact = new CertificationContract.Artifact("artifact-a", "AGENTS.md", SHA);
		CertificationContract.SourceSpan span = new CertificationContract.SourceSpan("span-a", "artifact-a", 0, 10,
			"Complete it.");
		CertificationContract.ApplicabilityRule rule = new CertificationContract.ApplicabilityRule("always", "true",
			Set.of("executor-output"), Set.of(CertificationContract.ApplicabilityOutcome.APPLICABLE,
			CertificationContract.ApplicabilityOutcome.NOT_APPLICABLE,
			CertificationContract.ApplicabilityOutcome.INVALID_EVIDENCE));
		CertificationContract.Requirement requirement = new CertificationContract.Requirement("requirement-a",
			"Complete the task.", List.of("span-a"), List.of("always"), List.of());
		CertificationContract.Distribution distribution = new CertificationContract.Distribution("distribution-a",
			"uniform-v1", List.of("tone"), List.of(), "formal-a", policy.supportValidationReceipt());
		CertificationContract.Assertion assertion = new CertificationContract.Assertion("assertion-a", "requirement-a",
			CertificationContract.AssertionClassification.SEMANTIC, "The task is complete.", Set.of("executor-output"),
			"evaluator-a", CertificationContract.InfrastructureFailureRule.EXCLUDE_FROM_FORMAL_OUTCOMES);
		CertificationContract.LeafFamily family = new CertificationContract.LeafFamily("family-a", null,
			List.of("requirement-a"), List.of("always"), "Task completion.", "distribution-a", "generator-a",
			List.of("assertion-a"));
		CertificationContract.Draft draft = new CertificationContract.Draft("certification-contract-v1",
			List.of(artifact), List.of(span), List.of(requirement), List.of(rule), List.of(distribution), List.of(family),
			List.of(assertion), List.of("evaluator-a"), List.of());
		return CertificationContract.freeze(draft).contract().orElseThrow();
	}
}
