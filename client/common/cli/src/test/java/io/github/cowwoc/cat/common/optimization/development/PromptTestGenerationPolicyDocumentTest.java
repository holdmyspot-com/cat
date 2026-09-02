package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimization.contract.CertificationContract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the strict executable prompt_test-policy document boundary.
 */
public final class PromptTestGenerationPolicyDocumentTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptTestGenerationPolicyDocumentTest()
	{
	}

	/**
	 * Verifies one document reconstructs complete, disjoint policies for all three evidence pools.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void readsCompletePoolCatalog() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt_test-policy-document-"));
			Path document = Files.writeString(root.resolve("policy.json"), document("development", ""));

			PromptTestGenerationPolicyDocument.Catalog catalog =
				PromptTestGenerationPolicyDocument.read(new JsonMapper(), document);

			long supportCount = catalog.policies().stream().
				map(PromptTestGenerationPolicy::validateSupport).
				mapToLong(PromptTestGenerationPolicy.Validation::validCombinationCount).sum();
			requireThat(catalog.policies(), "policies").size().isEqualTo(3);
			requireThat(supportCount, "supportCount").isEqualTo(6L);
			requireThat(catalog.policy("family-clear", PromptTestPoolPartition.Pool.FORMAL).pool(), "formal.pool()").
				isEqualTo(PromptTestPoolPartition.Pool.FORMAL);
		}
	}

	/**
	 * Verifies the complete policy document selects and binds only formal pool policies to the frozen contract.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void bindsFormalPoliciesToContract() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt_test-policy-binding-"));
			Path document = Files.writeString(root.resolve("policy.json"), document("development", ""));
			PromptTestGenerationPolicyDocument.Catalog catalog =
				PromptTestGenerationPolicyDocument.read(new JsonMapper(), document);
			PromptTestGenerationPolicy formal = catalog.policy("family-clear", PromptTestPoolPartition.Pool.FORMAL);
			CertificationContract contract = CertificationContract.freeze(contractDraft(formal)).contract().orElseThrow();

			PromptTestContractCatalog.Result result = catalog.bindFormalContract(contract);

			requireThat(result.status(), "status").isEqualTo(PromptTestContractCatalog.Status.READY);
			requireThat(result.catalog().orElseThrow().policy("family-clear") == formal, "boundPolicy").isTrue();
		}
	}

	/**
	 * Verifies a pool outside the closed external domain is rejected.
	 *
	 * @throws IOException for the expected pool rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported prompt_test evidence pool: experimental.*")
	public void rejectsUnsupportedPool() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt_test-policy-pool-"));
			Path document = Files.writeString(root.resolve("policy.json"), document("experimental", ""));

			PromptTestGenerationPolicyDocument.read(new JsonMapper(), document);
		}
	}

	/**
	 * Verifies a policy field outside the versioned schema is rejected.
	 *
	 * @throws IOException for the expected field-set rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*unsupported or missing fields.*")
	public void rejectsUnknownField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt_test-policy-field-"));
			Path document = Files.writeString(root.resolve("policy.json"), document("development", ",\"unknown\":true"));

			PromptTestGenerationPolicyDocument.read(new JsonMapper(), document);
		}
	}

	/**
	 * Verifies every family must define development, readiness, and formal evidence pools.
	 *
	 * @throws IOException for the expected incomplete-family rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*does not define every evidence pool: family-clear.*")
	public void rejectsIncompletePoolCatalog() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt_test-policy-incomplete-"));
			String contents = "{\"schema_version\":\"prompt_test-generation-policy-v1\",\"policies\":[" +
				policy("development", "development-dist", "development-clear") + "," +
				policy("readiness", "readiness-dist", "readiness-clear") + "]}";
			Path document = Files.writeString(root.resolve("policy.json"), contents);

			PromptTestGenerationPolicyDocument.read(new JsonMapper(), document);
		}
	}

	/**
	 * Verifies all pools for one family must partition the same base prompt_test support.
	 *
	 * @throws IOException for the expected source-identity rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*different base support: family-clear.*")
	public void rejectsDifferentPoolSource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt_test-policy-source-"));
			String formal = policy("formal", "dist-clear", "formal-clear").replace("Explain {{name}}",
				"Describe {{name}}");
			String contents = "{\"schema_version\":\"prompt_test-generation-policy-v1\",\"policies\":[" +
				policy("development", "development-dist", "development-clear") + "," +
				policy("readiness", "readiness-dist", "readiness-clear") + "," + formal + "]}";
			Path document = Files.writeString(root.resolve("policy.json"), contents);

			PromptTestGenerationPolicyDocument.read(new JsonMapper(), document);
		}
	}

	/**
	 * Creates one complete strict document.
	 *
	 * @param firstPool the first policy's pool value
	 * @param rootSuffix fields appended before the root object closes
	 * @return the JSON document
	 */
	private static String document(String firstPool, String rootSuffix)
	{
		return "{\"schema_version\":\"prompt_test-generation-policy-v1\",\"policies\":[" +
			policy(firstPool, "development-dist", "development-clear") + "," +
			policy("readiness", "readiness-dist", "readiness-clear") + "," +
			policy("formal", "dist-clear", "formal-clear") + "]" + rootSuffix + "}";
	}

	/**
	 * Creates one evidence-pool policy object.
	 *
	 * @param pool the external pool value
	 * @param distribution the pool-specific distribution identifier
	 * @param seedNamespace the pool-specific seed namespace
	 * @return the JSON object
	 */
	private static String policy(String pool, String distribution, String seedNamespace)
	{
		return """
			{"pool":"%s","family_id":"family-clear","distribution_id":"%s",
			"sampler_id":"uniform-finite-v1","generator_id":"generator-clear",
			"seed_namespace":"%s","prompt_template":"Explain {{name}} at {{path}}.",
			"dimensions":{"name":["Ada","Lin"],"path":["A","B","C"]},
			"exclusions":[],"maximum_support_size":1000}
			""".formatted(pool, distribution, seedNamespace).strip();
	}

	/**
	 * Creates one frozen contract draft that names a formal policy.
	 *
	 * @param policy the formal executable policy
	 * @return the contract draft
	 */
	private static CertificationContract.Draft contractDraft(PromptTestGenerationPolicy policy)
	{
		String fingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
		CertificationContract.Artifact prompt =
			new CertificationContract.Artifact("artifact-prompt", "AGENTS.md", fingerprint);
		CertificationContract.SourceSpan span = new CertificationContract.SourceSpan("span-clear", "artifact-prompt",
			0, 12, "Explain it.");
		CertificationContract.ApplicabilityRule rule = new CertificationContract.ApplicabilityRule("always", "true",
			Set.of("executor-output"), Set.of(CertificationContract.ApplicabilityOutcome.APPLICABLE,
			CertificationContract.ApplicabilityOutcome.NOT_APPLICABLE,
			CertificationContract.ApplicabilityOutcome.INVALID_EVIDENCE));
		CertificationContract.Requirement requirement = new CertificationContract.Requirement("req-clear",
			"Explain it clearly.", List.of("span-clear"), List.of("always"), List.of());
		CertificationContract.Distribution distribution = new CertificationContract.Distribution("dist-clear",
			"uniform-finite-v1", List.of("name", "path"), List.of(), "formal-clear",
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
