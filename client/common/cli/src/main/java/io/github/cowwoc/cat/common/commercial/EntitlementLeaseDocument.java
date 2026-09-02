package io.github.cowwoc.cat.common.commercial;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.RecordStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the strict bounded signed commercial-entitlement lease document.
 */
public final class EntitlementLeaseDocument
{
	private static final int MAXIMUM_DOCUMENT_BYTES = 64 * 1024;
	private static final String SUPPORTED_SCHEMA = "lease-v1";
	private static final Set<String> FIELDS = Set.of("schema_version", "license_identifier", "device_identifier",
		"confirmed_at", "expires_at", "entitlement_ends_at", "signature_base64");

	/**
	 * Writes one signed lease document atomically.
	 *
	 * @param json the JSON codec
	 * @param destination the lease-cache destination
	 * @param lease the signed lease
	 * @throws IOException if encoding or publication fails
	 */
	public static void write(JsonMapper json, Path destination, EntitlementLease lease) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(destination, "destination must not be null");
		Objects.requireNonNull(lease, "lease must not be null");
		ObjectNode document = json.createObjectNode();
		document.put("schema_version", lease.schemaVersion());
		document.put("license_identifier", lease.licenseIdentifier());
		document.put("device_identifier", lease.deviceIdentifier());
		document.put("confirmed_at", lease.confirmedAt().toString());
		document.put("expires_at", lease.expiresAt().toString());
		document.put("entitlement_ends_at", lease.entitlementEndsAt().toString());
		document.put("signature_base64", Base64.getEncoder().encodeToString(lease.signature()));
		try
		{
			RecordStore.writeText(destination,
				json.writerWithDefaultPrettyPrinter().writeValueAsString(document) + System.lineSeparator());
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot serialize entitlement lease: " + destination, e);
		}
	}

	/**
	 * Reads one strict signed lease document for later signature verification.
	 *
	 * @param json the JSON codec
	 * @param source the lease-cache document
	 * @return the parsed lease
	 * @throws IOException if the document is malformed, noncanonical, or unsupported
	 */
	public static EntitlementLease read(JsonMapper json, Path source) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		String content = BoundedInput.readUtf8(source, MAXIMUM_DOCUMENT_BYTES, "Entitlement lease");
		try
		{
			JsonMapper strict = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			JsonNode parsed = strict.readTree(content);
			if (!(parsed instanceof ObjectNode document))
				throw new IOException("Entitlement lease is not a JSON object: " + source);
			if (!new HashSet<>(document.propertyNames()).equals(FIELDS))
				throw new IOException("Entitlement lease has unsupported or missing fields: " + source);
			String schemaVersion = text(document, "schema_version", source);
			if (!schemaVersion.equals(SUPPORTED_SCHEMA))
				throw new IOException("Unsupported entitlement lease schema " + schemaVersion + ": " + source);
			return new EntitlementLease(schemaVersion, text(document, "license_identifier", source),
				text(document, "device_identifier", source), instant(document, "confirmed_at", source),
				instant(document, "expires_at", source), instant(document, "entitlement_ends_at", source),
				signature(document, source));
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid entitlement lease at " + source + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Reads required nonblank text.
	 *
	 * @param document the document
	 * @param field the field name
	 * @param source the source path
	 * @return the text
	 * @throws IOException if the field is invalid
	 */
	private static String text(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Entitlement lease has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Reads a canonical UTC instant.
	 *
	 * @param document the document
	 * @param field the timestamp field
	 * @param source the source path
	 * @return the instant
	 * @throws IOException if parsing fails or the spelling is noncanonical
	 */
	private static Instant instant(ObjectNode document, String field, Path source) throws IOException
	{
		String value = text(document, field, source);
		try
		{
			Instant result = Instant.parse(value);
			if (!result.toString().equals(value))
				throw new IOException("Entitlement lease has noncanonical " + field + ": " + source);
			return result;
		}
		catch (DateTimeException e)
		{
			throw new IOException("Entitlement lease has invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Reads the canonical Base64 Ed25519 signature.
	 *
	 * @param document the document
	 * @param source the source path
	 * @return the signature bytes
	 * @throws IOException if the signature is invalid or noncanonical
	 */
	private static byte[] signature(ObjectNode document, Path source) throws IOException
	{
		String value = text(document, "signature_base64", source);
		try
		{
			byte[] result = Base64.getDecoder().decode(value);
			if (!Base64.getEncoder().encodeToString(result).equals(value))
				throw new IOException("Entitlement lease signature is not canonical Base64: " + source);
			return result;
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Entitlement lease signature is invalid Base64: " + source, e);
		}
	}

	/**
	 * Prevents construction of this static document boundary.
	 */
	private EntitlementLeaseDocument()
	{
	}
}
