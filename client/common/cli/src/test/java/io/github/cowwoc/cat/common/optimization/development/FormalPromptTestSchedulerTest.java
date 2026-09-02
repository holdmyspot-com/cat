package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimization.certification.CandidateAllowanceLedger;
import io.github.cowwoc.cat.common.optimization.certification.FamilyCertification;
import io.github.cowwoc.cat.common.optimization.certification.FamilySequentialDecision;
import io.github.cowwoc.cat.common.optimization.contract.CertificationContract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests formal prompt_test selection from a frozen family distribution.
 */
public final class FormalPromptTestSchedulerTest
{
	private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final CandidateAllowanceLedger.Assignment ALLOWANCE = new CandidateAllowanceLedger.Assignment(
		"candidate-a", 1, new BigDecimal("0.025"), Instant.parse("2026-09-01T12:00:00Z"), "ROOT", "entry-a");
	private static final FamilySequentialDecision.Policy CERTIFICATION_POLICY =
		new FamilySequentialDecision.Policy(0.95, 300);

	/**
	 * Creates the test fixture.
	 */
	public FormalPromptTestSchedulerTest()
	{
	}

	/**
	 * Verifies the statistical family decision selects one supported formal draw.
	 */
	@Test
	public void schedulesSelectedFormalFamily()
	{
		PromptTestGenerationPolicy policy = policy();
		FormalPromptTestScheduler.Result result = FormalPromptTestScheduler.schedule(sampleDecision(), catalog(policy), 42,
			"formal-run-1", List.of());

		requireThat(result.status(), "status").isEqualTo(FormalPromptTestScheduler.Status.SCHEDULED);
		PromptTestGenerationPolicy.PromptTest promptTest = result.scheduledPromptTest().orElseThrow().promptTest();
		requireThat(promptTest.familyIdentifier(), "familyIdentifier").isEqualTo("family-clear");
		requireThat(promptTest.pool(), "pool").isEqualTo(PromptTestPoolPartition.Pool.FORMAL);
		requireThat(policy.contains(promptTest), "policy.contains(promptTest)").isTrue();
	}

	/**
	 * Verifies a cross-pool content collision blocks the formal draw before inference.
	 */
	@Test
	public void blocksKnownCrossPoolCollision()
	{
		PromptTestGenerationPolicy policy = policy();
		FormalPromptTestScheduler.Result first = FormalPromptTestScheduler.schedule(sampleDecision(), catalog(policy), 42,
			"formal-run-1", List.of());
		PromptTestGenerationPolicy.PromptTest promptTest = first.scheduledPromptTest().orElseThrow().promptTest();
		PromptTestPoolPartition.Identity development = new PromptTestPoolPartition.Identity(
			PromptTestPoolPartition.Pool.DEVELOPMENT, "development-instance", "development-seed",
			promptTest.contentFingerprint(), "development-run");

		FormalPromptTestScheduler.Result result = FormalPromptTestScheduler.schedule(sampleDecision(), catalog(policy), 42,
			"formal-run-1", List.of(development));

		requireThat(result.status(), "status").isEqualTo(FormalPromptTestScheduler.Status.BLOCKED);
		requireThat(result.collisions().getFirst().field(), "collision.field()").isEqualTo(
			PromptTestPoolPartition.IdentityField.GENERATED_CONTENT_FINGERPRINT);
	}

	/**
	 * Verifies a terminal statistical decision cannot be converted into another formal draw.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Formal prompt_test requires SAMPLE_FAMILY, received CERTIFIED.*")
	public void rejectsTerminalDecision()
	{
		FamilySequentialDecision.Result certified = FamilySequentialDecision.evaluate(
			List.of(new FamilyCertification.Evidence("family-clear", 180, 180)), ALLOWANCE,
			CERTIFICATION_POLICY);

		FormalPromptTestScheduler.schedule(certified, catalog(policy()), 42, "formal-run-1", List.of());
	}

	/**
	 * Creates the next-family statistical decision.
	 *
	 * @return the sampling decision
	 */
	private static FamilySequentialDecision.Result sampleDecision()
	{
		return FamilySequentialDecision.evaluate(List.of(new FamilyCertification.Evidence("family-clear", 0, 0)),
			ALLOWANCE, CERTIFICATION_POLICY);
	}

	/**
	 * Creates the formal prompt_test policy.
	 *
	 * @return the policy
	 */
	private static PromptTestGenerationPolicy policy()
	{
		return new PromptTestGenerationPolicy(PromptTestPoolPartition.Pool.FORMAL, "family-clear", "dist-clear",
			"uniform-finite-v1", "generator-clear", "formal-clear", "Explain {{name}} at {{path}}.",
			Map.of("name", List.of("Ada", "Lin"), "path", List.of("A", "B", "C")), Set.of(), 1_000);
	}

	/**
	 * Binds one formal policy to its matching frozen contract.
	 *
	 * @param policy the formal policy
	 * @return the ready executable catalog
	 */
	private static PromptTestContractCatalog catalog(PromptTestGenerationPolicy policy)
	{
		CertificationContract.Artifact prompt = new CertificationContract.Artifact("artifact-prompt", "AGENTS.md", SHA);
		CertificationContract.SourceSpan span = new CertificationContract.SourceSpan("span-clear", "artifact-prompt",
			0, 12, "Explain it.");
		CertificationContract.ApplicabilityRule applicability = new CertificationContract.ApplicabilityRule("always",
			"true", Set.of("executor-output"), Set.of(CertificationContract.ApplicabilityOutcome.APPLICABLE,
			CertificationContract.ApplicabilityOutcome.NOT_APPLICABLE,
			CertificationContract.ApplicabilityOutcome.INVALID_EVIDENCE));
		CertificationContract.Requirement requirement = new CertificationContract.Requirement("req-clear",
			"Explain clearly.", List.of("span-clear"), List.of("always"), List.of());
		CertificationContract.Distribution distribution = new CertificationContract.Distribution("dist-clear",
			"uniform-finite-v1", List.of("name", "path"), List.of(), "formal-clear",
			policy.supportValidationReceipt());
		CertificationContract.Assertion assertion = new CertificationContract.Assertion("assert-clear", "req-clear",
			CertificationContract.AssertionClassification.SEMANTIC, "The output is clear.", Set.of("executor-output"),
			"judge-clear", CertificationContract.InfrastructureFailureRule.EXCLUDE_FROM_FORMAL_OUTCOMES);
		CertificationContract.LeafFamily family = new CertificationContract.LeafFamily("family-clear", null,
			List.of("req-clear"), List.of("always"), "Clear explanations.", "dist-clear", "generator-clear",
			List.of("assert-clear"));
		CertificationContract.Draft draft = new CertificationContract.Draft("certification-contract-v1",
			List.of(prompt), List.of(span), List.of(requirement), List.of(applicability), List.of(distribution),
			List.of(family), List.of(assertion), List.of("judge-clear"), List.of());
		CertificationContract contract = CertificationContract.freeze(draft).contract().orElseThrow();
		return PromptTestContractCatalog.bind(contract, List.of(policy)).catalog().orElseThrow();
	}
}
