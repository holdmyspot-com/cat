package io.github.cowwoc.cat.common.optimization;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.Fingerprints;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Verifies one operation-owned terminal evidence envelope before a certification process transition records it.
 */
public final class OptimizationOperationEvidenceDocument
{
	private static final int MAXIMUM_DOCUMENT_BYTES = 64 * 1024;
	private static final String SCHEMA_VERSION = "optimization-operation-evidence-v1";
	private static final Set<String> FIELDS = Set.of("schema_version", "certification_process_id", "predecessor_sequence",
		"contract_sha256", "environment_sha256", "operation", "evidence_schema_id", "result", "successor",
		"payload_path", "payload_sha256", "completed_at");

	/**
	 * Reads and verifies one retained operation evidence envelope.
	 *
	 * @param json the JSON codec
	 * @param source the evidence envelope
	 * @param certificationProcess the verified certification process state
	 * @return the verified transition evidence
	 * @throws IOException if the envelope, retained payload, or certification process binding is invalid
	 */
	public static VerifiedEvidence readAndVerify(JsonMapper json, Path source,
		CertificationProcessLedger.Snapshot certificationProcess) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		Objects.requireNonNull(certificationProcess, "certification process must not be null");
		Path envelopePath = source.toAbsolutePath().normalize();
		String contents = BoundedInput.readUtf8(envelopePath, MAXIMUM_DOCUMENT_BYTES,
			"Optimization operation evidence");
		ObjectNode document = parse(json, contents, envelopePath);
		verifyFields(document, envelopePath);
		String schemaVersion = requiredText(document, "schema_version", envelopePath);
		if (!schemaVersion.equals(SCHEMA_VERSION))
			throw new IOException("Unsupported optimization operation evidence schema: " + schemaVersion);

		long predecessorSequence = nonnegativeLong(document, "predecessor_sequence", envelopePath);
		verifyCertificationProcessBinding(document, predecessorSequence, certificationProcess, envelopePath);
		OperationKind operation = operation(
			requiredText(document, "operation", envelopePath), envelopePath);
		OperationResultKind result = result(requiredText(document, "result", envelopePath),
			envelopePath);
		Optional<OperationKind> successor = successor(document, envelopePath);
		verifyOperationBinding(document, operation, result, successor, certificationProcess, predecessorSequence,
			envelopePath);

		String payloadName = requiredText(document, "payload_path", envelopePath);
		Path payloadPath = resolvePayload(envelopePath, payloadName);
		String declaredPayloadFingerprint = requireSha256(requiredText(document, "payload_sha256", envelopePath),
			"payload_sha256", envelopePath);
		String actualPayloadFingerprint = sha256(payloadPath);
		if (!actualPayloadFingerprint.equals(declaredPayloadFingerprint))
			throw new IOException("Optimization operation payload fingerprint does not match: " + payloadPath);
		Instant completedAt = instant(document, "completed_at", envelopePath);
		String evidenceFingerprint = fingerprint(json, certificationProcess, predecessorSequence, operation, result,
			successor,
			requiredText(document, "evidence_schema_id", envelopePath), payloadName, actualPayloadFingerprint, completedAt);
		return new VerifiedEvidence(predecessorSequence, operation, result, successor, completedAt, evidenceFingerprint,
			payloadPath, actualPayloadFingerprint);
	}

	/**
	 * Parses one strict JSON object.
	 *
	 * @param json the caller's JSON codec
	 * @param contents the document contents
	 * @param source the document path
	 * @return the parsed object
	 * @throws IOException if the document is malformed or is not an object
	 */
	private static ObjectNode parse(JsonMapper json, String contents, Path source) throws IOException
	{
		JsonMapper strictJson = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
			DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
		try
		{
			JsonNode parsed = strictJson.readTree(contents);
			if (parsed instanceof ObjectNode object)
				return object;
			throw new IOException("Optimization operation evidence is not a JSON object: " + source);
		}
		catch (JacksonException e)
		{
			throw new IOException("Invalid optimization operation evidence JSON at " + source + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Verifies the exact versioned field set.
	 *
	 * @param document the parsed document
	 * @param source the document path
	 * @throws IOException if a field is missing or unsupported
	 */
	private static void verifyFields(ObjectNode document, Path source) throws IOException
	{
		if (!new HashSet<>(document.propertyNames()).equals(FIELDS))
			throw new IOException("Optimization operation evidence has unsupported or missing fields: " + source);
	}

	/**
	 * Verifies immutable certification process identities and predecessor ordering.
	 *
	 * @param document the parsed document
	 * @param predecessorSequence the declared predecessor sequence
	 * @param certificationProcess the verified certification process
	 * @param source the document path
	 * @throws IOException if the evidence belongs to another certification process state
	 */
	private static void verifyCertificationProcessBinding(ObjectNode document, long predecessorSequence,
		CertificationProcessLedger.Snapshot certificationProcess, Path source) throws IOException
	{
		compare(requiredText(document, "certification_process_id", source),
			certificationProcess.certificationProcessIdentifier(), "certification_process_id", source);
		compare(requireSha256(requiredText(document, "contract_sha256", source), "contract_sha256", source),
			certificationProcess.contractFingerprint(), "contract_sha256", source);
		compare(requireSha256(requiredText(document, "environment_sha256", source), "environment_sha256", source),
			certificationProcess.environmentFingerprint(), "environment_sha256", source);
		if (predecessorSequence > certificationProcess.state().sequence())
		{
			throw new IOException("Optimization operation evidence predecessor sequence " + predecessorSequence +
				" is ahead of certification process sequence " + certificationProcess.state().sequence() + ": " + source);
		}
	}

	/**
	 * Verifies the registered evidence schema, transition, and current operation when this is a new transition.
	 *
	 * @param document the parsed document
	 * @param operation the completed operation
	 * @param result the terminal result
	 * @param successor the selected successor
	 * @param certificationProcess the verified certification process
	 * @param predecessorSequence the declared predecessor sequence
	 * @param source the document path
	 * @throws IOException if the operation evidence cannot produce the declared transition
	 */
	private static void verifyOperationBinding(ObjectNode document, OperationKind operation,
		OperationResultKind result,
		Optional<OperationKind> successor, CertificationProcessLedger.Snapshot certificationProcess,
		long predecessorSequence, Path source) throws IOException
	{
		OptimizationOperationRegistry.Definition definition =
			OptimizationOperationRegistry.definitions().get(operation);
		String evidenceSchema = requiredText(document, "evidence_schema_id", source);
		if (!evidenceSchema.equals(definition.evidenceSchemaId()))
		{
			throw new IOException("Optimization evidence schema " + evidenceSchema +
				" does not match the registered operation schema " + definition.evidenceSchemaId() + ": " + source);
		}
		try
		{
			OptimizationStateMachine.advance(OptimizationStateMachine.activeAt(operation), result, successor);
		}
		catch (IllegalArgumentException | IllegalStateException e)
		{
			throw new IOException("Optimization operation evidence declares an invalid transition: " + e.getMessage(), e);
		}
		if (predecessorSequence == certificationProcess.state().sequence())
		{
			if (certificationProcess.state().terminal())
				throw new IOException("A terminal certification process cannot accept operation evidence: " + source);
			if (certificationProcess.state().currentOperation().orElseThrow() != operation)
			{
				throw new IOException("Optimization operation evidence names " + operation.externalValue() +
					" but the certification process requires " +
					certificationProcess.state().currentOperation().orElseThrow().externalValue() +
					": " + source);
			}
		}
	}

	/**
	 * Resolves one normalized regular payload below the evidence directory.
	 *
	 * @param envelopePath the evidence envelope path
	 * @param payloadName the declared relative payload path
	 * @return the verified absolute payload path
	 * @throws IOException if the path is unsafe or not a regular file
	 */
	private static Path resolvePayload(Path envelopePath, String payloadName) throws IOException
	{
		Path relative;
		try
		{
			relative = Path.of(payloadName);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Optimization operation evidence has an invalid payload_path: " + payloadName, e);
		}
		if (relative.isAbsolute() || !relative.normalize().equals(relative) || relative.getNameCount() == 0 ||
			relative.startsWith(".."))
		{
			throw new IOException("Optimization operation evidence has an unsafe payload_path: " + payloadName);
		}
		Path root = envelopePath.getParent();
		if (root == null)
			throw new IOException("Optimization operation evidence has no parent directory: " + envelopePath);
		Path payload = root.resolve(relative).toAbsolutePath().normalize();
		if (!payload.startsWith(root.toAbsolutePath().normalize()) ||
			!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS))
		{
			throw new IOException("Optimization operation evidence payload is not a retained regular file: " + payload);
		}
		return payload;
	}

	/**
	 * Calculates a bounded-memory file fingerprint.
	 *
	 * @param source the retained payload
	 * @return its lowercase SHA-256 fingerprint
	 * @throws IOException if the payload cannot be read
	 */
	private static String sha256(Path source) throws IOException
	{
		return Fingerprints.ofFile(source);
	}

	/**
	 * Calculates the canonical evidence-envelope identity.
	 *
	 * @param json the JSON codec
	 * @param certificationProcess the bound certification process
	 * @param predecessorSequence the predecessor sequence
	 * @param operation the completed operation
	 * @param result the terminal result
	 * @param successor the selected successor
	 * @param evidenceSchema the registered evidence schema
	 * @param payloadName the retained relative payload path
	 * @param payloadFingerprint the verified payload fingerprint
	 * @param completedAt the operation completion time
	 * @return the lowercase SHA-256 fingerprint
	 * @throws IOException if canonical serialization fails
	 */
	private static String fingerprint(JsonMapper json, CertificationProcessLedger.Snapshot certificationProcess,
		long predecessorSequence, OperationKind operation,
		OperationResultKind result,
		Optional<OperationKind> successor, String evidenceSchema, String payloadName,
		String payloadFingerprint, Instant completedAt) throws IOException
	{
		ObjectNode canonical = json.createObjectNode();
		canonical.put("schema_version", SCHEMA_VERSION);
		canonical.put("certification_process_id", certificationProcess.certificationProcessIdentifier());
		canonical.put("predecessor_sequence", predecessorSequence);
		canonical.put("contract_sha256", certificationProcess.contractFingerprint());
		canonical.put("environment_sha256", certificationProcess.environmentFingerprint());
		canonical.put("operation", operation.externalValue());
		canonical.put("evidence_schema_id", evidenceSchema);
		canonical.put("result", result.externalValue());
		if (successor.isPresent())
			canonical.put("successor", successor.orElseThrow().externalValue());
		else
			canonical.putNull("successor");
		canonical.put("payload_path", payloadName);
		canonical.put("payload_sha256", payloadFingerprint);
		canonical.put("completed_at", completedAt.toString());
		return Fingerprints.ofUtf8(json.writeValueAsString(canonical));
	}

	/**
	 * Parses one closed operation identifier.
	 *
	 * @param value the external identifier
	 * @param source the document path
	 * @return the operation identifier
	 * @throws IOException if the identifier is unsupported
	 */
	private static OperationKind operation(String value, Path source) throws IOException
	{
		try
		{
			return OperationKind.fromExternalValue(value);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(e.getMessage() + ": " + source, e);
		}
	}

	/**
	 * Parses one closed result identifier.
	 *
	 * @param value the external identifier
	 * @param source the document path
	 * @return the result identifier
	 * @throws IOException if the identifier is unsupported
	 */
	private static OperationResultKind result(String value, Path source) throws IOException
	{
		try
		{
			return OperationResultKind.fromExternalValue(value);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(e.getMessage() + ": " + source, e);
		}
	}

	/**
	 * Parses the nullable closed successor identifier.
	 *
	 * @param document the parsed document
	 * @param source the document path
	 * @return the successor, or empty for a terminal result
	 * @throws IOException if the value is neither null nor a supported identifier
	 */
	private static Optional<OperationKind> successor(ObjectNode document, Path source)
		throws IOException
	{
		JsonNode value = document.path("successor");
		if (value.isNull())
			return Optional.empty();
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Optimization operation evidence has invalid successor: " + source);
		return Optional.of(operation(value.asString(), source));
	}

	/**
	 * Reads one required nonblank text field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the document path
	 * @return the text
	 * @throws IOException if the value is invalid
	 */
	private static String requiredText(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Optimization operation evidence has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Reads one nonnegative integral long field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the document path
	 * @return the nonnegative long
	 * @throws IOException if the value is invalid
	 */
	private static long nonnegativeLong(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
			throw new IOException("Optimization operation evidence has invalid " + field + ": " + source);
		return value.asLong();
	}

	/**
	 * Reads one canonical instant.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the document path
	 * @return the instant
	 * @throws IOException if the value is invalid or noncanonical
	 */
	private static Instant instant(ObjectNode document, String field, Path source) throws IOException
	{
		String value = requiredText(document, field, source);
		try
		{
			Instant result = Instant.parse(value);
			if (!result.toString().equals(value))
				throw new IOException("Optimization operation evidence has noncanonical " + field + ": " + source);
			return result;
		}
		catch (DateTimeException e)
		{
			throw new IOException("Optimization operation evidence has invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Compares one immutable certification process value.
	 *
	 * @param actual the evidence value
	 * @param expected the certification process value
	 * @param field the field name
	 * @param source the document path
	 * @throws IOException if the values differ
	 */
	private static void compare(String actual, String expected, String field, Path source) throws IOException
	{
		if (!actual.equals(expected))
			throw new IOException("Optimization operation evidence has a different " + field + ": " + source);
	}

	/**
	 * Validates one SHA-256 fingerprint.
	 *
	 * @param value the external fingerprint
	 * @param field the field name
	 * @param source the document path
	 * @return the fingerprint
	 * @throws IOException if the value is not lowercase SHA-256
	 */
	private static String requireSha256(String value, String field, Path source) throws IOException
	{
		if (!value.matches("[0-9a-f]{64}"))
			throw new IOException("Optimization operation evidence has invalid " + field + ": " + source);
		return value;
	}

	/**
	 * Holds one certification process-bound, payload-verified terminal result.
	 *
	 * @param predecessorSequence the certification process sequence before this operation completed
	 * @param operation the completed operation
	 * @param result the operation's terminal result
	 * @param successor the selected successor, or empty at certification process termination
	 * @param completedAt the operation completion time
	 * @param evidenceFingerprint the canonical evidence-envelope fingerprint
	 * @param payloadPath the verified retained payload path
	 * @param payloadFingerprint the verified payload fingerprint
	 */
	public record VerifiedEvidence(long predecessorSequence, OperationKind operation,
		OperationResultKind result,
		Optional<OperationKind> successor, Instant completedAt, String evidenceFingerprint,
		Path payloadPath, String payloadFingerprint)
	{
		/**
		 * Validates one verified evidence result.
		 *
		 * @param predecessorSequence the certification process sequence before this operation completed
		 * @param operation the completed operation
		 * @param result the operation's terminal result
		 * @param successor the selected successor, or empty at certification process termination
		 * @param completedAt the operation completion time
		 * @param evidenceFingerprint the canonical evidence-envelope fingerprint
		 * @param payloadPath the verified retained payload path
		 * @param payloadFingerprint the verified payload fingerprint
		 */
		public VerifiedEvidence
		{
			if (predecessorSequence < 0)
				throw new IllegalArgumentException("predecessorSequence must not be negative");
			Objects.requireNonNull(operation, "operation must not be null");
			Objects.requireNonNull(result, "result must not be null");
			Objects.requireNonNull(successor, "successor must not be null");
			Objects.requireNonNull(completedAt, "completedAt must not be null");
			Objects.requireNonNull(evidenceFingerprint, "evidenceFingerprint must not be null");
			Objects.requireNonNull(payloadPath, "payloadPath must not be null");
			Objects.requireNonNull(payloadFingerprint, "payloadFingerprint must not be null");
		}
	}

	/**
	 * Prevents construction of this static document boundary.
	 */
	private OptimizationOperationEvidenceDocument()
	{
	}
}
