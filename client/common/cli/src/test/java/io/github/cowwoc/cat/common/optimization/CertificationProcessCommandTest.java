package io.github.cowwoc.cat.common.optimization;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.Fingerprints;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the durable certification process status command projection.
 */
public final class CertificationProcessCommandTest
{
	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	/**
	 * Creates the test fixture.
	 */
	public CertificationProcessCommandTest()
	{
	}

	/**
	 * Verifies status exposes the exact resumable operation and sequence.
	 *
	 * @throws java.io.IOException if fixture persistence fails
	 */
	@Test
	public void reportsResumableStatus() throws java.io.IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path manifest = cleanup.add(Files.createTempDirectory("cat-certificationProcess-command-")).
				resolve("certificationProcess.json");
			JsonMapper json = new JsonMapper();
			CertificationProcessLedger.initializeForFixture(json, manifest, "certificationProcess-1", SHA_A, SHA_B,
				OperationKind.GENERATE_CANDIDATES);
			CertificationProcessLedger.record(json, manifest, 0, OperationResultKind.CHALLENGERS,
				Optional.of(OperationKind.COMPARE_DEVELOPMENT_CANDIDATES), SHA_A,
				Instant.parse("2026-09-01T00:00:00Z"));
			StringBuilder report = new StringBuilder();

			CertificationProcessCommand.status(json, manifest, report);

			requireThat(report.toString(), "report").contains("sequence=1").
				contains("current_operation=compare-development-candidates").contains("terminal=false");
		}
	}

	/**
	 * Verifies an unsupported untyped result is rejected at the command boundary.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported optimization result: almost-ready.*")
	public void rejectsUnsupportedResult()
	{
		OperationResultKind.fromExternalValue("almost-ready");
	}

	/**
	 * Verifies the command accepts a retained round document.
	 *
	 * @throws java.io.IOException if fixture persistence fails
	 */
	@Test
	public void acceptsRetainedRound() throws java.io.IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-round-report-"));
			Path reportDocument = Files.writeString(root.resolve("round.json"), roundDocument(""));

			CertificationProcessCommand.main(new String[]{"report-round", reportDocument.toString()});
		}
	}

	/**
	 * Verifies a retained round is rendered with formal and actionable labels.
	 *
	 * @throws java.io.IOException if fixture persistence fails
	 */
	@Test
	public void reportsRetainedRound() throws java.io.IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-round-report-projection-"));
			Path reportDocument = Files.writeString(root.resolve("round.json"), roundDocument(""));
			StringBuilder report = new StringBuilder();

			CertificationProcessCommand.reportRound(new JsonMapper(), reportDocument, report);

			requireThat(report.toString(), "report").contains("Current lower bound: 92.8%").
				contains("Best validated version: v14").
				contains("Recommended next action: continue with this prompt file");
		}
	}

	/**
	 * Verifies JSON null remains distinct from a numeric current lower bound.
	 *
	 * @throws java.io.IOException if fixture persistence fails
	 */
	@Test
	public void reportsUnavailableLowerBound() throws java.io.IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-round-report-unavailable-"));
			String document = roundDocument("").replace("\"current_lower_bound\":0.928",
				"\"current_lower_bound\":null");
			Path reportDocument = Files.writeString(root.resolve("round.json"), document);
			StringBuilder report = new StringBuilder();

			CertificationProcessCommand.reportRound(new JsonMapper(), reportDocument, report);

			requireThat(report.toString(), "report").startsWith("Current lower bound: unavailable\n");
		}
	}

	/**
	 * Verifies an unknown round field is rejected instead of becoming an implicit extension.
	 *
	 * @throws java.io.IOException for the expected schema rejection
	 */
	@Test(expectedExceptions = java.io.IOException.class,
		expectedExceptionsMessageRegExp = ".*unsupported or missing fields.*")
	public void rejectsUnknownRoundField() throws java.io.IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-round-report-invalid-"));
			Path reportDocument = Files.writeString(root.resolve("round.json"),
				roundDocument(",\"unknown\":true"));

			CertificationProcessCommand.main(new String[]{"report-round", reportDocument.toString()});
		}
	}

	/**
	 * Verifies an unsupported evidence kind is rejected at the untyped document boundary.
	 *
	 * @throws java.io.IOException for the expected closed-domain rejection
	 */
	@Test(expectedExceptions = java.io.IOException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported optimization report evidence kind: experimental.*")
	public void rejectsUnsupportedEvidenceKind() throws java.io.IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-round-report-evidence-"));
			String document = roundDocument("").replace("\"evidence_kind\":\"development\"",
				"\"evidence_kind\":\"experimental\"");
			Path reportDocument = Files.writeString(root.resolve("round.json"), document);

			CertificationProcessCommand.reportRound(new JsonMapper(), reportDocument, new StringBuilder());
		}
	}

	/**
	 * Verifies a certification process transition derives its result and evidence fingerprint from the verified envelope.
	 *
	 * @throws java.io.IOException if fixture persistence fails
	 */
	@Test
	public void recordsVerifiedOperationEvidence() throws java.io.IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-certificationProcess-evidence-"));
			Path manifest = root.resolve("certificationProcess.json");
			JsonMapper json = new JsonMapper();
			CertificationProcessLedger.initializeForFixture(json, manifest, "certificationProcess-1", SHA_A, SHA_B,
				OperationKind.GENERATE_CANDIDATES);
			Path payload = Files.writeString(root.resolve("candidate-result.json"), "{\"status\":\"challengers\"}");
			Path evidenceDocument = Files.writeString(root.resolve("evidence.json"),
				operationEvidence(Fingerprints.ofFile(payload)));

			CertificationProcessLedger.Entry entry =
				CertificationProcessCommand.recordEvidence(json, manifest, evidenceDocument);

			requireThat(entry.operation(), "operation").isEqualTo(
				OperationKind.GENERATE_CANDIDATES);
			requireThat(entry.evidenceFingerprint(), "evidenceFingerprint").matches("[0-9a-f]{64}");
			requireThat(CertificationProcessLedger.read(json, manifest).state().currentOperation().orElseThrow(),
				"currentOperation").isEqualTo(OperationKind.COMPARE_DEVELOPMENT_CANDIDATES);
		}
	}

	/**
	 * Verifies the next-operation projection derives every routine execution constraint from the registry.
	 *
	 * @throws java.io.IOException if fixture persistence fails
	 */
	@Test
	public void reportsRegisteredNextOperation() throws java.io.IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-certificationProcess-next-"));
			Path manifest = root.resolve("certificationProcess.json");
			JsonMapper json = new JsonMapper();
			CertificationProcessLedger.initializeForFixture(json, manifest, "certificationProcess-1", SHA_A, SHA_B,
				OperationKind.GENERATE_CANDIDATES);
			StringBuilder report = new StringBuilder();

			CertificationProcessCommand.next(json, manifest, report);

			requireThat(report.toString(), "report").contains("operation=generate-candidates").
				contains("owner=optimizer").contains("model_consuming=true").
				contains("input_schema=generate-candidates-input-v1").
				contains("result=challengers successors=compare-development-candidates").
				contains("result=no-eligible-challenger successors=decide-readiness").
				doesNotContain("spending_authorization").doesNotContain("pause-campaign");
		}
	}

	/**
	 * Creates one strict round-report document with an optional root suffix.
	 *
	 * @param suffix fields appended before the root object closes
	 * @return the document
	 */
	private static String roundDocument(String suffix)
	{
		return """
			{"schema_version":"optimization-round-report-v1","current_lower_bound":0.928,"target":0.95,
			"best_validated_version":"v14","usage":{"provider_cost":{"amount":"23.10","currency":"USD"},
			"input_tokens":1200,"output_tokens":300,"elapsed_nanos":83000000000,"operations":2},
			"evidence_kind":"development","limiting_families":[{"name":"clarity","formal_trials":12,
			"failures":2,"status":"below_target"}],"next_action":{"action":"continue_target_only",
			"work":"Generate candidates for clarity.","reason":"The target-only scope is still productive."}%s}
			""".formatted(suffix);
	}

	/**
	 * Creates one verified generate-candidates terminal evidence envelope.
	 *
	 * @param payloadFingerprint the retained operation payload fingerprint
	 * @return the strict evidence document
	 */
	private static String operationEvidence(String payloadFingerprint)
	{
		return """
			{"schema_version":"optimization-operation-evidence-v1","certification_process_id":"certificationProcess-1",
			"predecessor_sequence":0,"contract_sha256":"%s","environment_sha256":"%s",
			"operation":"generate-candidates","evidence_schema_id":"generate-candidates-evidence-v1",
			"result":"challengers","successor":"compare-development-candidates",
			"payload_path":"candidate-result.json",
			"payload_sha256":"%s","completed_at":"2026-09-01T00:00:00Z"}
			""".formatted(SHA_A, SHA_B, payloadFingerprint).strip();
	}
}
