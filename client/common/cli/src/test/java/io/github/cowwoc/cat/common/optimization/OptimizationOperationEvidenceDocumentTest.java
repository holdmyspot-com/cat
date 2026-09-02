package io.github.cowwoc.cat.common.optimization;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.TemporaryDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the strict certification-process-bound operation evidence envelope.
 */
public final class OptimizationOperationEvidenceDocumentTest
{
	private static final String CONTRACT_SHA =
		"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String ENVIRONMENT_SHA =
		"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	/**
	 * Creates the test fixture.
	 */
	public OptimizationOperationEvidenceDocumentTest()
	{
	}

	/**
	 * Verifies a registered envelope binds its retained payload to the certification process and transition.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void verifiesCertificationProcessBoundPayload() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("operation-evidence-"));
			JsonMapper json = new JsonMapper();
			CertificationProcessLedger.Snapshot certificationProcess = certificationProcess(json, root);
			Path payload = Files.writeString(root.resolve("candidate-result.json"), "{\"status\":\"challengers\"}");
			Path envelope = Files.writeString(root.resolve("evidence.json"), envelope(
				"generate-candidates", "generate-candidates-evidence-v1", Fingerprints.ofFile(payload), ""));

			OptimizationOperationEvidenceDocument.VerifiedEvidence evidence =
				OptimizationOperationEvidenceDocument.readAndVerify(json, envelope, certificationProcess);

			requireThat(evidence.operation(), "operation").isEqualTo(
				OperationKind.GENERATE_CANDIDATES);
			requireThat(evidence.result(), "result").isEqualTo(OperationResultKind.CHALLENGERS);
			requireThat(evidence.successor().orElseThrow(), "successor").isEqualTo(
				OperationKind.COMPARE_DEVELOPMENT_CANDIDATES);
			requireThat(evidence.payloadPath(), "payloadPath").isEqualTo(payload.toAbsolutePath().normalize());
			requireThat(evidence.evidenceFingerprint(), "evidenceFingerprint").matches("[0-9a-f]{64}");
		}
	}

	/**
	 * Verifies an operation outside the registry's closed domain is rejected at the document boundary.
	 *
	 * @throws IOException for the expected operation rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported optimization operation: experimental.*")
	public void rejectsUnsupportedOperation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("operation-evidence-operation-"));
			JsonMapper json = new JsonMapper();
			CertificationProcessLedger.Snapshot certificationProcess = certificationProcess(json, root);
			Path payload = Files.writeString(root.resolve("candidate-result.json"), "{\"status\":\"challengers\"}");
			Path envelope = Files.writeString(root.resolve("evidence.json"), envelope(
				"experimental", "experimental-evidence-v1", Fingerprints.ofFile(payload), ""));

			OptimizationOperationEvidenceDocument.readAndVerify(json, envelope, certificationProcess);
		}
	}

	/**
	 * Verifies changed retained bytes cannot be recorded under the envelope's declared fingerprint.
	 *
	 * @throws IOException for the expected payload-integrity rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*payload fingerprint does not match.*")
	public void rejectsChangedPayload() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("operation-evidence-payload-"));
			JsonMapper json = new JsonMapper();
			CertificationProcessLedger.Snapshot certificationProcess = certificationProcess(json, root);
			Files.writeString(root.resolve("candidate-result.json"), "{\"status\":\"changed\"}");
			Path envelope = Files.writeString(root.resolve("evidence.json"), envelope(
				"generate-candidates", "generate-candidates-evidence-v1", CONTRACT_SHA, ""));

			OptimizationOperationEvidenceDocument.readAndVerify(json, envelope, certificationProcess);
		}
	}

	/**
	 * Verifies an unregistered evidence schema cannot masquerade as the operation's retained evidence.
	 *
	 * @throws IOException for the expected schema-identity rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*does not match the registered operation schema.*")
	public void rejectsWrongEvidenceSchema() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("operation-evidence-schema-"));
			JsonMapper json = new JsonMapper();
			CertificationProcessLedger.Snapshot certificationProcess = certificationProcess(json, root);
			Path payload = Files.writeString(root.resolve("candidate-result.json"), "{\"status\":\"challengers\"}");
			Path envelope = Files.writeString(root.resolve("evidence.json"), envelope(
				"generate-candidates", "other-evidence-v1", Fingerprints.ofFile(payload), ""));

			OptimizationOperationEvidenceDocument.readAndVerify(json, envelope, certificationProcess);
		}
	}

	/**
	 * Creates one empty certification process snapshot.
	 *
	 * @param json the JSON codec
	 * @param root the fixture directory
	 * @return the verified certification process snapshot
	 * @throws IOException if certification process persistence fails
	 */
	private static CertificationProcessLedger.Snapshot certificationProcess(JsonMapper json, Path root) throws IOException
	{
		Path manifest = root.resolve("certificationProcess.json");
		CertificationProcessLedger.initializeForFixture(json, manifest, "certificationProcess-1", CONTRACT_SHA,
			ENVIRONMENT_SHA, OperationKind.GENERATE_CANDIDATES);
		return CertificationProcessLedger.read(json, manifest);
	}

	/**
	 * Creates one strict terminal evidence envelope.
	 *
	 * @param operation the external operation identifier
	 * @param evidenceSchema the registered operation evidence schema
	 * @param payloadFingerprint the retained payload fingerprint
	 * @param suffix fields appended before the root object closes
	 * @return the JSON document
	 */
	private static String envelope(String operation, String evidenceSchema, String payloadFingerprint, String suffix)
	{
		return """
			{"schema_version":"optimization-operation-evidence-v1","certification_process_id":"certificationProcess-1",
			"predecessor_sequence":0,"contract_sha256":"%s","environment_sha256":"%s",
			"operation":"%s","evidence_schema_id":"%s","result":"challengers",
			"successor":"compare-development-candidates","payload_path":"candidate-result.json",
			"payload_sha256":"%s","completed_at":"2026-09-01T00:00:00Z"%s}
			""".formatted(CONTRACT_SHA, ENVIRONMENT_SHA, operation, evidenceSchema, payloadFingerprint, suffix).strip();
	}
}
