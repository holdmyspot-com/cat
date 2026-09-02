package io.github.cowwoc.cat.common.optimization.history;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.RecordStore;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exports and imports bounded strict JSON for locally signed certificate history.
 */
public final class LocalCertificateDocument
{
	private static final int MAXIMUM_DOCUMENT_BYTES = 4 * 1024 * 1024;
	private static final String DOCUMENT_SCHEMA = "local-certificate-document-v1";
	private static final String PAYLOAD_SCHEMA = "certificate-v1";
	private static final Set<String> DOCUMENT_FIELDS = Set.of("schema_version", "key_identifier", "payload",
		"signature_base64");
	private static final Set<String> PAYLOAD_FIELDS = Set.of("schema_version", "terminal_result",
		"candidate_id", "candidate_sha256", "current_lower_bound", "target", "contract_sha256",
		"environment_sha256", "lineage_ledger_sha256", "evaluator_protocol_sha256", "statistical_revision",
		"evidence_sha256");

	/**
	 * Writes one certificate without changing its signed payload or signature.
	 *
	 * @param json the JSON codec
	 * @param destination the certificate destination
	 * @param certificate the locally signed certificate
	 * @throws IOException if the document cannot be encoded or written atomically
	 */
	public static void write(JsonMapper json, Path destination, LocalCertificate certificate) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(destination, "destination must not be null");
		Objects.requireNonNull(certificate, "certificate must not be null");
		ObjectNode document = json.createObjectNode();
		document.put("schema_version", DOCUMENT_SCHEMA);
		document.put("key_identifier", certificate.keyIdentifier());
		document.set("payload", payload(json, certificate.payload()));
		document.put("signature_base64", Base64.getEncoder().encodeToString(certificate.signature()));
		try
		{
			RecordStore.writeText(destination,
				json.writerWithDefaultPrettyPrinter().writeValueAsString(document) + System.lineSeparator());
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot serialize local certificate: " + destination, e);
		}
	}

	/**
	 * Imports one certificate for later verification with the named installation key.
	 *
	 * @param json the JSON codec
	 * @param source the certificate document
	 * @return the imported, not-yet-verified certificate
	 * @throws IOException if the document is malformed, unsupported, or exceeds its bound
	 */
	public static LocalCertificate read(JsonMapper json, Path source) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		String contents = BoundedInput.readUtf8(source, MAXIMUM_DOCUMENT_BYTES, "Local certificate");
		try
		{
			JsonMapper strictJson = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			JsonNode parsed = strictJson.readTree(contents);
			if (!(parsed instanceof ObjectNode document))
				throw new IOException("Local certificate is not a JSON object: " + source);
			verifyFields(document, DOCUMENT_FIELDS, "Local certificate", source);
			verifySchema(document, "schema_version", DOCUMENT_SCHEMA, "local certificate document", source);
			String keyIdentifier = requiredText(document, "key_identifier", source);
			JsonNode payloadNode = document.path("payload");
			if (!(payloadNode instanceof ObjectNode payloadDocument))
				throw new IOException("Local certificate payload is not a JSON object: " + source);
			LocalCertificate.Payload payload = readPayload(payloadDocument, source);
			String signatureText = requiredText(document, "signature_base64", source);
			byte[] signature = decodeBase64(signatureText, source);
			return LocalCertificate.restore(payload, keyIdentifier, signature);
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid local certificate JSON at " + source + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Encodes the signed payload.
	 *
	 * @param json the JSON codec
	 * @param payload the signed payload
	 * @return the canonical payload object
	 */
	private static ObjectNode payload(JsonMapper json, LocalCertificate.Payload payload)
	{
		ObjectNode result = json.createObjectNode();
		result.put("schema_version", payload.schemaVersion());
		result.put("terminal_result", externalValue(payload.terminalResult()));
		result.put("candidate_id", payload.candidateIdentifier());
		result.put("candidate_sha256", payload.candidateFingerprint());
		if (payload.currentLowerBound().isPresent())
			result.put("current_lower_bound", decimal(payload.currentLowerBound().getAsDouble()));
		else
			result.putNull("current_lower_bound");
		result.put("target", decimal(payload.target()));
		result.put("contract_sha256", payload.contractFingerprint());
		result.put("environment_sha256", payload.environmentFingerprint());
		result.put("lineage_ledger_sha256", payload.lineageLedgerFingerprint());
		putStrings(result.putArray("evaluator_protocol_sha256"), payload.evaluatorProtocolFingerprints());
		result.put("statistical_revision", payload.statisticalRevision());
		putStrings(result.putArray("evidence_sha256"), payload.evidenceFingerprints());
		return result;
	}

	/**
	 * Reads the strict signed payload.
	 *
	 * @param document the payload object
	 * @param source the source path
	 * @return the typed payload
	 * @throws IOException if the payload violates its schema
	 */
	private static LocalCertificate.Payload readPayload(ObjectNode document, Path source) throws IOException
	{
		verifyFields(document, PAYLOAD_FIELDS, "Local certificate payload", source);
		verifySchema(document, "schema_version", PAYLOAD_SCHEMA, "local certificate payload", source);
		LocalCertificate.TerminalResult terminalResult = terminalResult(
			requiredText(document, "terminal_result", source), source);
		OptionalDouble currentLowerBound = optionalRate(document, "current_lower_bound", source);
		double target = rate(document, "target", source);
		List<String> evaluatorProtocols = strings(document, "evaluator_protocol_sha256", source);
		List<String> evidence = strings(document, "evidence_sha256", source);
		LocalCertificate.Payload payload = new LocalCertificate.Payload(PAYLOAD_SCHEMA, terminalResult,
			requiredText(document, "candidate_id", source), requiredText(document, "candidate_sha256", source),
			currentLowerBound, target, requiredText(document, "contract_sha256", source),
			requiredText(document, "environment_sha256", source),
			requiredText(document, "lineage_ledger_sha256", source), evaluatorProtocols,
			requiredText(document, "statistical_revision", source), evidence);
		if (!payload.evaluatorProtocolFingerprints().equals(evaluatorProtocols) ||
			!payload.evidenceFingerprints().equals(evidence))
		{
			throw new IOException("Local certificate fingerprint lists are not in canonical order: " + source);
		}
		return payload;
	}

	/**
	 * Verifies one exact field set.
	 *
	 * @param document the source object
	 * @param fields the complete field set
	 * @param description the diagnostic description
	 * @param source the source path
	 * @throws IOException if the field set differs
	 */
	private static void verifyFields(ObjectNode document, Set<String> fields, String description, Path source)
		throws IOException
	{
		if (!new HashSet<>(document.propertyNames()).equals(fields))
			throw new IOException(description + " has unsupported or missing fields: " + source);
	}

	/**
	 * Verifies one schema field.
	 *
	 * @param document the source object
	 * @param field the schema field
	 * @param expected the supported value
	 * @param description the diagnostic description
	 * @param source the source path
	 * @throws IOException if the schema differs
	 */
	private static void verifySchema(ObjectNode document, String field, String expected, String description,
		Path source) throws IOException
	{
		String value = requiredText(document, field, source);
		if (!value.equals(expected))
			throw new IOException("Unsupported " + description + " schema " + value + ": " + source);
	}

	/**
	 * Reads one required nonblank text field.
	 *
	 * @param document the source object
	 * @param field the field name
	 * @param source the source path
	 * @return the text
	 * @throws IOException if the field is invalid
	 */
	private static String requiredText(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Local certificate has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Reads one canonical probability.
	 *
	 * @param document the source object
	 * @param field the field name
	 * @param source the source path
	 * @return the probability
	 * @throws IOException if the field is not canonical decimal text
	 */
	private static double rate(ObjectNode document, String field, Path source) throws IOException
	{
		String value = requiredText(document, field, source);
		try
		{
			double result = Double.parseDouble(value);
			if (!decimal(result).equals(value))
				throw new IOException("Local certificate has noncanonical " + field + ": " + source);
			return result;
		}
		catch (NumberFormatException e)
		{
			throw new IOException("Local certificate has invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Reads one nullable canonical probability.
	 *
	 * @param document the source object
	 * @param field the field name
	 * @param source the source path
	 * @return the optional probability
	 * @throws IOException if the field is invalid
	 */
	private static OptionalDouble optionalRate(ObjectNode document, String field, Path source) throws IOException
	{
		if (document.path(field).isNull())
			return OptionalDouble.empty();
		return OptionalDouble.of(rate(document, field, source));
	}

	/**
	 * Reads a string array.
	 *
	 * @param document the source object
	 * @param field the field name
	 * @param source the source path
	 * @return the strings in source order
	 * @throws IOException if the field is not a string array
	 */
	private static List<String> strings(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!(value instanceof ArrayNode array))
			throw new IOException("Local certificate has invalid " + field + ": " + source);
		List<String> result = new ArrayList<>(array.size());
		for (JsonNode item : array)
		{
			if (!item.isString() || item.asString().isBlank())
				throw new IOException("Local certificate has invalid " + field + " entry: " + source);
			result.add(item.asString());
		}
		return List.copyOf(result);
	}

	/**
	 * Adds strings to an array.
	 *
	 * @param array the destination array
	 * @param values the strings
	 */
	private static void putStrings(ArrayNode array, List<String> values)
	{
		for (String value : values)
			array.add(value);
	}

	/**
	 * Decodes and validates canonical Base64.
	 *
	 * @param value the encoded signature
	 * @param source the source path
	 * @return the signature bytes
	 * @throws IOException if encoding is invalid or noncanonical
	 */
	private static byte[] decodeBase64(String value, Path source) throws IOException
	{
		try
		{
			byte[] result = Base64.getDecoder().decode(value);
			if (!Base64.getEncoder().encodeToString(result).equals(value))
				throw new IOException("Local certificate signature is not canonical Base64: " + source);
			return result;
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Local certificate signature is invalid Base64: " + source, e);
		}
	}

	/**
	 * Formats a probability as canonical decimal text.
	 *
	 * @param value the probability
	 * @return the decimal text
	 */
	private static String decimal(double value)
	{
		return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
	}

	/**
	 * Maps a terminal result to its stable document value.
	 *
	 * @param result the terminal result
	 * @return the stable value
	 */
	private static String externalValue(LocalCertificate.TerminalResult result)
	{
		return result.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	/**
	 * Parses one terminal result.
	 *
	 * @param value the untyped value
	 * @param source the source path
	 * @return the terminal result
	 * @throws IOException if the value is unsupported
	 */
	private static LocalCertificate.TerminalResult terminalResult(String value, Path source) throws IOException
	{
		return switch (value)
		{
			case "certified" -> LocalCertificate.TerminalResult.CERTIFIED;
			case "not-certified" -> LocalCertificate.TerminalResult.NOT_CERTIFIED;
			default -> throw new IOException("Unsupported local certificate terminal result " + value + ": " + source);
		};
	}

	/**
	 * Prevents construction of this static document boundary.
	 */
	private LocalCertificateDocument()
	{
	}
}
