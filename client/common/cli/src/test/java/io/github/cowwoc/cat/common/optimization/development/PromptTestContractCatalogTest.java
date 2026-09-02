package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.optimization.contract.CertificationContract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests the executable binding between frozen prompt_test families and their generation policies.
 */
public final class PromptTestContractCatalogTest
{
	private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	/**
	 * Creates the test fixture.
	 */
	public PromptTestContractCatalogTest()
	{
	}

	/**
	 * Verifies every frozen family resolves to the exact executable distribution and generator it names.
	 */
	@Test
	public void bindsExactExecutablePolicies()
	{
		PromptTestGenerationPolicy policy = policy("generator-clear-v1");
		CertificationContract contract = CertificationContract.freeze(draft(policy)).contract().orElseThrow();

		PromptTestContractCatalog.Result result = PromptTestContractCatalog.bind(contract, List.of(policy));

		requireThat(result.status(), "status").isEqualTo(PromptTestContractCatalog.Status.READY);
		requireThat(result.issues(), "issues").isEmpty();
		requireThat(result.catalog().orElseThrow().policy("family-clear") == policy, "boundPolicy").isTrue();
	}

	/**
	 * Verifies a generator implementation that differs from the frozen family is rejected before sampling.
	 */
	@Test
	public void rejectsChangedGenerator()
	{
		PromptTestGenerationPolicy frozenPolicy = policy("generator-clear-v1");
		CertificationContract contract = CertificationContract.freeze(draft(frozenPolicy)).contract().orElseThrow();
		PromptTestGenerationPolicy changedPolicy = policy("generator-clear-v2");

		PromptTestContractCatalog.Result result = PromptTestContractCatalog.bind(contract, List.of(changedPolicy));

		requireThat(result.status(), "status").isEqualTo(PromptTestContractCatalog.Status.BLOCKED);
			requireThat(result.issues().getFirst().code(), "issueCode").isEqualTo(
			PromptTestContractCatalog.IssueCode.GENERATOR_MISMATCH);
	}

	/**
	 * Verifies a development distribution cannot become the frozen formal distribution merely by matching its receipt.
	 */
	@Test
	public void rejectsDevelopmentEvidencePolicy()
	{
		PromptTestGenerationPolicy policy = policy("generator-clear-v1", PromptTestPoolPartition.Pool.DEVELOPMENT);
		CertificationContract contract = CertificationContract.freeze(draft(policy)).contract().orElseThrow();

		PromptTestContractCatalog.Result result = PromptTestContractCatalog.bind(contract, List.of(policy));

		requireThat(result.status(), "status").isEqualTo(PromptTestContractCatalog.Status.BLOCKED);
		requireThat(result.issues().getFirst().code(), "issueCode").isEqualTo(
			PromptTestContractCatalog.IssueCode.WRONG_EVIDENCE_POOL);
	}

	/**
	 * Creates one executable finite policy.
	 *
	 * @param generatorIdentifier the generator revision
	 * @return the policy
	 */
	private static PromptTestGenerationPolicy policy(String generatorIdentifier)
	{
		return policy(generatorIdentifier, PromptTestPoolPartition.Pool.FORMAL);
	}

	/**
	 * Creates one executable finite policy for a selected evidence pool.
	 *
	 * @param generatorIdentifier the generator revision
	 * @param pool the evidence pool
	 * @return the policy
	 */
	private static PromptTestGenerationPolicy policy(String generatorIdentifier, PromptTestPoolPartition.Pool pool)
	{
		return new PromptTestGenerationPolicy(pool, "family-clear", "dist-clear",
			"uniform-finite-v1", generatorIdentifier, "clear-v1", "Explain {{identifier}} using {{wording}} wording.",
			Map.of("identifier", List.of("Ada", "Lin"), "wording", List.of("brief", "detailed")), Set.of(), 1_000);
	}

	/**
	 * Creates one frozen-contract draft that names the executable policy.
	 *
	 * @param policy the executable prompt_test policy
	 * @return the contract draft
	 */
	private static CertificationContract.Draft draft(PromptTestGenerationPolicy policy)
	{
		CertificationContract.Artifact prompt = new CertificationContract.Artifact("artifact-prompt", "AGENTS.md", SHA);
		CertificationContract.SourceSpan span = new CertificationContract.SourceSpan("span-clear", "artifact-prompt",
			0, 12, "Explain it.");
		CertificationContract.ApplicabilityRule rule = new CertificationContract.ApplicabilityRule("always", "true",
			Set.of("executor-output"), Set.of(CertificationContract.ApplicabilityOutcome.APPLICABLE,
			CertificationContract.ApplicabilityOutcome.NOT_APPLICABLE,
			CertificationContract.ApplicabilityOutcome.INVALID_EVIDENCE));
		CertificationContract.Requirement requirement = new CertificationContract.Requirement("req-clear",
			"Explain it clearly.", List.of("span-clear"), List.of("always"), List.of());
		CertificationContract.Distribution distribution = new CertificationContract.Distribution("dist-clear",
			"uniform-finite-v1", List.of("identifier", "wording"), List.of(), "clear-v1",
			policy.supportValidationReceipt());
		CertificationContract.Assertion assertion = new CertificationContract.Assertion("assert-clear", "req-clear",
			CertificationContract.AssertionClassification.SEMANTIC, "The output explains the result.",
			Set.of("executor-output"), "judge-clear-v1",
			CertificationContract.InfrastructureFailureRule.EXCLUDE_FROM_FORMAL_OUTCOMES);
		CertificationContract.LeafFamily family = new CertificationContract.LeafFamily("family-clear", null,
			List.of("req-clear"), List.of("always"), "Clear explanations.", "dist-clear",
			policy.generatorIdentifier(), List.of("assert-clear"));
		return new CertificationContract.Draft("certification-contract-v1", List.of(prompt), List.of(span),
			List.of(requirement), List.of(rule), List.of(distribution), List.of(family), List.of(assertion),
			List.of("judge-clear-v1"), List.of());
	}
}
