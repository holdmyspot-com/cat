package io.github.cowwoc.cat.distribution;

import io.github.cowwoc.cat.common.Fingerprints;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Defines the closed acceptance-gate registry for a frontend release launch.
 */
public final class FrontendLaunchGate
{
	private static final String RECEIPT_SCHEMA = "frontend-launch-gate-receipt-v1";
	private static final String REPORT_SCHEMA = "frontend-launch-gate-report-v1";
	private static final int MAXIMUM_DOCUMENT_BYTES = 1024 * 1024;
	private static final Set<String> RECEIPT_FIELDS = Set.of("schema_version", "gate_id", "status",
		"command", "started_at", "completed_at", "evidence_paths", "evidence_sha256", "diagnostic",
		"authorized_by", "reason");
	private static final Set<String> REPORT_FIELDS = Set.of("schema_version", "version", "gates");
	private static final Set<String> REPORT_ENTRY_FIELDS = Set.of("gate_id", "status", "receipt_path",
		"receipt_sha256", "diagnostic");
	/**
	 * Prevents construction of this static registry owner.
	 */
	private FrontendLaunchGate()
	{
	}

	/**
	 * Returns the required gate identifiers in report order.
	 *
	 * @return the immutable required gate order
	 */
	public static List<Identifier> requiredIdentifiers()
	{
		return List.of(Identifier.PERSONAL_LIFECYCLE, Identifier.COMMERCIAL_LIFECYCLE, Identifier.PLATFORM_MATRIX,
			Identifier.RELEASE_TAG_INTEGRITY, Identifier.COMMERCIAL_LEASE_CONTINUITY,
			Identifier.FEEDBACK_AND_ANALYTICS_PRIVACY, Identifier.PROMPT_SYSTEM_BOUNDARY,
			Identifier.CERTIFICATION_TERMINAL_MATRIX, Identifier.WORK_EXECUTE_CONTRACT,
			Identifier.LICENSE_OFFER_CONSISTENCY);
	}

	/**
	 * Creates one fresh evidence directory for a single launch-gate attempt.
	 *
	 * <p>Receipts remain immutable within this directory. A later attempt for the same release receives a different
	 * directory, preserving every earlier attempt and its report.</p>
	 *
	 * @param evidenceRoot the directory that contains launch-gate evidence for all releases
	 * @param version the release version under test
	 * @return a newly created, empty attempt directory
	 * @throws IOException if the version directory or new attempt directory cannot be created
	 */
	public static Path createAttemptDirectory(Path evidenceRoot, String version) throws IOException
	{
		Objects.requireNonNull(evidenceRoot, "evidenceRoot must not be null");
		version = releaseVersionDirectoryName(version);
		Path versionDirectory = evidenceRoot.toAbsolutePath().normalize().resolve(version);
		Files.createDirectories(versionDirectory);
		return Files.createTempDirectory(versionDirectory, "attempt-");
	}

	/**
	 * Validates the version as one directory name rather than a path supplied by the release caller.
	 *
	 * @param version the release version
	 * @return the validated version
	 */
	private static String releaseVersionDirectoryName(String version)
	{
		version = requireText(version, "version");
		if (version.equals(".") || version.equals("..") || version.contains("/") || version.contains("\\"))
			throw new IllegalArgumentException("version must be one directory name");
		return version;
	}

	/**
	 * Verifies that a complete release gate report contains each required receipt exactly once and that each passed.
	 *
	 * @param receipts the receipts in required report order
	 * @throws IllegalArgumentException if a receipt is missing, reordered, duplicated, or not passed
	 */
	public static void requirePassed(List<Receipt> receipts)
	{
		Objects.requireNonNull(receipts, "receipts must not be null");
		List<Identifier> identifiers = requiredIdentifiers();
		if (receipts.size() != identifiers.size())
			throw new IllegalArgumentException("Frontend launch report must contain every required gate receipt");
		for (int index = 0; index < identifiers.size(); ++index)
		{
			Receipt receipt = Objects.requireNonNull(receipts.get(index), "receipts must contain no null elements");
			if (receipt.identifier() != identifiers.get(index))
				throw new IllegalArgumentException("Frontend launch report has a missing, duplicate, or reordered " +
					"gate receipt");
			if (receipt.status() != Status.PASSED)
				throw new IllegalArgumentException("Required frontend launch gate did not pass: " +
					receipt.identifier().externalValue());
		}
	}

	/**
	 * Writes one immutable launch-gate receipt using the exact JSON schema.
	 *
	 * @param json the JSON codec
	 * @param destination the receipt destination
	 * @param receipt the receipt to retain
	 * @throws IOException if the receipt cannot be encoded or written
	 */
	public static void writeReceipt(JsonMapper json, Path destination, Receipt receipt) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(destination, "destination must not be null");
		Objects.requireNonNull(receipt, "receipt must not be null");
		try
		{
			writeNewText(destination, json.writerWithDefaultPrettyPrinter().writeValueAsString(receiptNode(json, receipt)) +
				System.lineSeparator());
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot serialize frontend launch-gate receipt: " + destination, e);
		}
	}

	/**
	 * Reads and validates one strict launch-gate receipt.
	 *
	 * @param json the JSON codec
	 * @param source the receipt document
	 * @return the validated receipt
	 * @throws IOException if the document is malformed or unsupported
	 */
	public static Receipt readReceipt(JsonMapper json, Path source) throws IOException
	{
		ObjectNode object = readObject(json, source, "Frontend launch-gate receipt");
		verifyFields(object, RECEIPT_FIELDS, "Frontend launch-gate receipt", source);
		if (!requiredText(object, "schema_version", source).equals(RECEIPT_SCHEMA))
			throw new IOException("Unsupported frontend launch-gate receipt schema: " + source);
		Identifier identifier = Identifier.fromExternalValue(requiredText(object, "gate_id", source));
		Status status = Status.fromExternalValue(requiredText(object, "status", source));
		String command = requiredNullableText(object, "command", source);
		Instant startedAt = instant(object, "started_at", source);
		Instant completedAt = instant(object, "completed_at", source);
		List<Path> evidencePaths = paths(object, source);
		List<String> fingerprints = strings(object, "evidence_sha256", source);
		String diagnostic = requiredNullableText(object, "diagnostic", source);
		String authorizedBy = requiredNullableText(object, "authorized_by", source);
		String reason = requiredNullableText(object, "reason", source);
		if (status == Status.EXCLUDED && (authorizedBy == null || authorizedBy.isBlank() || reason == null ||
			reason.isBlank()))
			throw new IOException("An excluded gate requires authorized_by and reason: " + source);
		if (status != Status.EXCLUDED && (authorizedBy != null || reason != null))
			throw new IOException("Only an excluded gate may contain authorization fields: " + source);
		try
		{
			return new Receipt(identifier, status, command, startedAt, completedAt, evidencePaths, fingerprints,
				diagnostic, authorizedBy, reason);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Invalid frontend launch-gate receipt: " + source, e);
		}
	}

	/**
	 * Reads all required receipts and writes the deterministic aggregate launch-gate report.
	 *
	 * @param json the JSON codec
	 * @param version the release version
	 * @param reportDestination the aggregate report destination
	 * @param receiptPaths one receipt path for each gate; absent files become unrun entries
	 * @return the aggregate report
	 * @throws IOException if a present receipt or the report cannot be read or written
	 */
	public static Report writeReport(JsonMapper json, String version, Path reportDestination,
		Map<Identifier, Path> receiptPaths) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		version = requireText(version, "version");
		Objects.requireNonNull(reportDestination, "reportDestination must not be null");
		Objects.requireNonNull(receiptPaths, "receiptPaths must not be null");
		List<ReportEntry> entries = new ArrayList<>();
		for (Identifier identifier : requiredIdentifiers())
		{
			Path path = receiptPaths.get(identifier);
			if (path == null)
				throw new IllegalArgumentException("Missing receipt path for gate: " + identifier.externalValue());
			Path normalized = path.toAbsolutePath().normalize();
			if (!Files.exists(normalized))
				entries.add(new ReportEntry(identifier, Status.UNRUN, normalized, null, "Receipt is missing: " + normalized));
			else
			{
				Receipt receipt = readReceipt(json, normalized);
				if (receipt.identifier() != identifier)
					throw new IOException("Receipt gate does not match its registered path: " + normalized);
				entries.add(new ReportEntry(identifier, receipt.status(), normalized, Fingerprints.ofFile(normalized),
					receipt.diagnostic()));
			}
		}
		Report report = new Report(version, entries);
		try
		{
			writeText(reportDestination, json.writerWithDefaultPrettyPrinter().writeValueAsString(reportNode(json, report)) +
				System.lineSeparator());
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot serialize frontend launch-gate report: " + reportDestination, e);
		}
		return report;
	}

	/**
	 * Reads and validates one aggregate launch-gate report.
	 *
	 * @param json the JSON codec
	 * @param source the aggregate report
	 * @return the validated report
	 * @throws IOException if the report is malformed or not in required order
	 */
	public static Report readReport(JsonMapper json, Path source) throws IOException
	{
		ObjectNode object = readObject(json, source, "Frontend launch-gate report");
		verifyFields(object, REPORT_FIELDS, "Frontend launch-gate report", source);
		if (!requiredText(object, "schema_version", source).equals(REPORT_SCHEMA))
			throw new IOException("Unsupported frontend launch-gate report schema: " + source);
		String version = requiredText(object, "version", source);
		JsonNode gatesNode = object.get("gates");
		if (!(gatesNode instanceof ArrayNode gates) || gates.size() != requiredIdentifiers().size())
			throw new IOException("Frontend launch-gate report must contain exactly ten gates: " + source);
		List<ReportEntry> entries = new ArrayList<>();
		List<Identifier> required = requiredIdentifiers();
		for (int index = 0; index < gates.size(); ++index)
		{
			if (!(gates.get(index) instanceof ObjectNode entry))
				throw new IOException("Frontend launch-gate report contains a non-object entry: " + source);
			verifyFields(entry, REPORT_ENTRY_FIELDS, "Frontend launch-gate report entry", source);
			Identifier identifier = Identifier.fromExternalValue(requiredText(entry, "gate_id", source));
			if (identifier != required.get(index))
				throw new IOException("Frontend launch-gate report gates are not in required order: " + source);
			Status status = Status.fromExternalValue(requiredText(entry, "status", source));
			Path receiptPath = Path.of(requiredText(entry, "receipt_path", source)).toAbsolutePath().normalize();
			String fingerprint = requiredNullableText(entry, "receipt_sha256", source);
			if (fingerprint != null && !fingerprint.matches("[0-9a-f]{64}"))
				throw new IOException("Invalid receipt fingerprint in report: " + source);
			String diagnostic = requiredNullableText(entry, "diagnostic", source);
			if (diagnostic == null)
				diagnostic = "";
			if (status != Status.PASSED && diagnostic.isBlank())
				throw new IOException("A non-passed report entry requires a diagnostic: " + source);
			entries.add(new ReportEntry(identifier, status, receiptPath, fingerprint, diagnostic));
		}
		return new Report(version, entries);
	}

	/**
	 * Names one stable frontend acceptance gate.
	 */
	public enum Identifier
	{
		PERSONAL_LIFECYCLE("personal-lifecycle"),
		COMMERCIAL_LIFECYCLE("commercial-lifecycle"),
		PLATFORM_MATRIX("platform-matrix"),
		RELEASE_TAG_INTEGRITY("release-tag-integrity"),
		COMMERCIAL_LEASE_CONTINUITY("commercial-lease-continuity"),
		FEEDBACK_AND_ANALYTICS_PRIVACY("feedback-and-analytics-privacy"),
		PROMPT_SYSTEM_BOUNDARY("prompt-system-boundary"),
		CERTIFICATION_TERMINAL_MATRIX("certification-terminal-matrix"),
		WORK_EXECUTE_CONTRACT("work-execute-contract"),
		LICENSE_OFFER_CONSISTENCY("license-offer-consistency");

		private final String externalValue;

		/**
		 * Creates one stable identifier.
		 *
		 * @param externalValue the serialized identifier
		 */
		Identifier(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Returns the serialized identifier.
		 *
		 * @return the stable external value
		 */
		public String externalValue()
		{
			return externalValue;
		}

		/**
		 * Parses one exact stable identifier.
		 *
		 * @param value the serialized value
		 * @return the matching gate identifier
		 */
		public static Identifier fromExternalValue(String value)
		{
			Objects.requireNonNull(value, "value must not be null");
			for (Identifier candidate : values())
			{
				if (candidate.externalValue.equals(value))
					return candidate;
			}
			throw new IllegalArgumentException("Unsupported frontend launch gate: " + value);
		}
	}

	/**
	 * Names the durable outcome of one acceptance gate.
	 */
	public enum Status
	{
		PASSED("passed"),
		FAILED("failed"),
		UNRUN("unrun"),
		EXCLUDED("excluded");

		private final String externalValue;

		/**
		 * Creates one stable status.
		 *
		 * @param externalValue the serialized status
		 */
		Status(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Returns the serialized status.
		 *
		 * @return the stable external value
		 */
		public String externalValue()
		{
			return externalValue;
		}

		/**
		 * Parses one exact stable status.
		 *
		 * @param value the serialized status
		 * @return the matching status
		 */
		public static Status fromExternalValue(String value)
		{
			Objects.requireNonNull(value, "value must not be null");
			for (Status candidate : values())
			{
				if (candidate.externalValue.equals(value))
					return candidate;
			}
			throw new IllegalArgumentException("Unsupported frontend launch-gate status: " + value);
		}
	}

	/**
	 * Holds the immutable outcome evidence for one required launch gate.
	 *
	 * @param identifier the required gate identifier
	 * @param status the immutable terminal status
	 * @param command the executed command, or empty for an unrun gate
	 * @param startedAt the start time, or {@code null} for an unrun gate
	 * @param completedAt the completion time, or {@code null} for an unrun gate
	 * @param evidencePaths the ordered evidence paths
	 * @param evidenceFingerprints the ordered SHA-256 fingerprints
	 * @param diagnostic the required diagnostic for non-passed outcomes
	 * @param authorizedBy the product-owner authorization for an excluded gate
	 * @param reason the product-owner reason for an excluded gate
	 */
	public record Receipt(Identifier identifier, Status status, String command, Instant startedAt, Instant completedAt,
		List<Path> evidencePaths, List<String> evidenceFingerprints, String diagnostic, String authorizedBy, String reason)
	{
		/**
		 * Creates a receipt without exclusion authorization fields.
		 *
		 * @param identifier the required gate identifier
		 * @param status the immutable terminal status
		 * @param command the executed command, or empty for an unrun gate
		 * @param startedAt the start time, or {@code null} for an unrun gate
		 * @param completedAt the completion time, or {@code null} for an unrun gate
		 * @param evidencePaths the ordered evidence paths
		 * @param evidenceFingerprints the ordered SHA-256 fingerprints
		 * @param diagnostic the required diagnostic for non-passed outcomes
		 */
		public Receipt(Identifier identifier, Status status, String command, Instant startedAt, Instant completedAt,
			List<Path> evidencePaths, List<String> evidenceFingerprints, String diagnostic)
		{
			this(identifier, status, command, startedAt, completedAt, evidencePaths, evidenceFingerprints, diagnostic, null,
				null);
		}

		/**
		 * Validates one durable gate receipt.
		 *
		 * @param identifier the required gate identifier
		 * @param status the immutable terminal status
		 * @param command the executed command, or empty for an unrun gate
		 * @param startedAt the start time, or {@code null} for an unrun gate
		 * @param completedAt the completion time, or {@code null} for an unrun gate
		 * @param evidencePaths the ordered evidence paths
		 * @param evidenceFingerprints the ordered SHA-256 fingerprints
		 * @param diagnostic the required diagnostic for non-passed outcomes
		 * @param authorizedBy the product-owner authorization for an excluded gate
		 * @param reason the product-owner reason for an excluded gate
		 */
		public Receipt
		{
			Objects.requireNonNull(identifier, "identifier must not be null");
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(command, "command must not be null");
			Objects.requireNonNull(evidencePaths, "evidencePaths must not be null");
			Objects.requireNonNull(evidenceFingerprints, "evidenceFingerprints must not be null");
			Objects.requireNonNull(diagnostic, "diagnostic must not be null");
			if (authorizedBy != null && authorizedBy.isBlank())
				throw new IllegalArgumentException("authorizedBy must not be blank");
			if (reason != null && reason.isBlank())
				throw new IllegalArgumentException("reason must not be blank");
			evidencePaths = evidencePaths.stream().map(path -> path.toAbsolutePath().normalize()).toList();
			evidenceFingerprints = List.copyOf(evidenceFingerprints);
			if (evidencePaths.size() != evidenceFingerprints.size())
				throw new IllegalArgumentException("evidence paths and fingerprints must have the same size");
			if (evidenceFingerprints.stream().anyMatch(value -> !value.matches("[0-9a-f]{64}")))
				throw new IllegalArgumentException("evidence fingerprints must be lowercase SHA-256 values");
			switch (status)
			{
				case PASSED ->
				{
					if (command.isBlank() || startedAt == null || completedAt == null || evidencePaths.isEmpty())
						throw new IllegalArgumentException("A passed gate requires a command, timing, and evidence");
					verifyEvidence(evidencePaths, evidenceFingerprints);
				}
				case FAILED ->
				{
					if (command.isBlank() || startedAt == null || completedAt == null || diagnostic.isBlank())
						throw new IllegalArgumentException("A failed gate requires a command, timing, and diagnostic");
				}
				case UNRUN ->
				{
					if (!command.isBlank() || startedAt != null || completedAt != null || !evidencePaths.isEmpty() ||
						!evidenceFingerprints.isEmpty() || diagnostic.isBlank())
						throw new IllegalArgumentException("An unrun gate requires no execution and a diagnostic");
				}
				case EXCLUDED ->
				{
					if (!command.isBlank() || startedAt != null || completedAt != null || !evidencePaths.isEmpty() ||
						!evidenceFingerprints.isEmpty() || diagnostic.isBlank())
						throw new IllegalArgumentException("An excluded gate requires no execution and a diagnostic");
				}
			}
			if (status == Status.EXCLUDED && (authorizedBy == null || reason == null))
				throw new IllegalArgumentException("An excluded gate requires authorization and reason");
			if (status != Status.EXCLUDED && (authorizedBy != null || reason != null))
				throw new IllegalArgumentException("Authorization fields are only valid for excluded gates");
			if (startedAt != null && completedAt != null && completedAt.isBefore(startedAt))
				throw new IllegalArgumentException("completedAt must not precede startedAt");
		}
	}

	/**
	 * Holds one aggregate report entry.
	 *
	 * @param identifier the registered gate
	 * @param status the retained gate status
	 * @param receiptPath the expected receipt path
	 * @param receiptFingerprint the receipt file SHA-256, or {@code null} when missing
	 * @param diagnostic the retained diagnostic
	 */
	public record ReportEntry(Identifier identifier, Status status, Path receiptPath, String receiptFingerprint,
		String diagnostic)
	{
		/**
		 * Validates one aggregate report entry.
		 *
		 * @param identifier the registered gate
		 * @param status the retained gate status
		 * @param receiptPath the expected receipt path
		 * @param receiptFingerprint the receipt file SHA-256, or {@code null} when missing
		 * @param diagnostic the retained diagnostic
		 */
		public ReportEntry
		{
			Objects.requireNonNull(identifier, "identifier must not be null");
			Objects.requireNonNull(status, "status must not be null");
			receiptPath = Objects.requireNonNull(receiptPath, "receiptPath must not be null").toAbsolutePath().normalize();
			Objects.requireNonNull(diagnostic, "diagnostic must not be null");
			if (receiptFingerprint != null && !receiptFingerprint.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("receiptFingerprint must be lowercase SHA-256");
		}
	}

	/**
	 * Holds one aggregate launch-gate report.
	 *
	 * @param version the release version
	 * @param entries the ten entries in registry order
	 */
	public record Report(String version, List<ReportEntry> entries)
	{
		/**
		 * Validates one aggregate report.
		 *
		 * @param version the release version
		 * @param entries the ten entries in registry order
		 */
		public Report
		{
			version = requireText(version, "version");
			entries = List.copyOf(entries);
			if (entries.size() != requiredIdentifiers().size())
				throw new IllegalArgumentException("A launch-gate report must contain every required gate");
			for (int index = 0; index < entries.size(); ++index)
			{
				if (entries.get(index).identifier() != requiredIdentifiers().get(index))
					throw new IllegalArgumentException("Launch-gate report entries are not in required order");
			}
		}
	}

	/**
	 * Encodes a receipt in deterministic field order.
	 *
	 * @param json the JSON codec
	 * @param receipt the receipt
	 * @return the encoded receipt
	 */
	private static ObjectNode receiptNode(JsonMapper json, Receipt receipt)
	{
		ObjectNode result = json.createObjectNode();
		result.put("schema_version", RECEIPT_SCHEMA);
		result.put("gate_id", receipt.identifier().externalValue());
		result.put("status", receipt.status().externalValue());
		putNullable(result, "command", receipt.command());
		putNullable(result, "started_at", receipt.startedAt());
		putNullable(result, "completed_at", receipt.completedAt());
		ArrayNode paths = result.putArray("evidence_paths");
		for (Path path : receipt.evidencePaths())
			paths.add(path.toString());
		ArrayNode fingerprints = result.putArray("evidence_sha256");
		for (String fingerprint : receipt.evidenceFingerprints())
			fingerprints.add(fingerprint);
		putNullable(result, "diagnostic", receipt.diagnostic());
		putNullable(result, "authorized_by", receipt.authorizedBy());
		putNullable(result, "reason", receipt.reason());
		return result;
	}

	/**
	 * Encodes a report in deterministic field order.
	 *
	 * @param json the JSON codec
	 * @param report the report
	 * @return the encoded report
	 */
	private static ObjectNode reportNode(JsonMapper json, Report report)
	{
		ObjectNode result = json.createObjectNode();
		result.put("schema_version", REPORT_SCHEMA);
		result.put("version", report.version());
		ArrayNode gates = result.putArray("gates");
		for (ReportEntry entry : report.entries())
		{
			ObjectNode gate = gates.addObject();
			gate.put("gate_id", entry.identifier().externalValue());
			gate.put("status", entry.status().externalValue());
			gate.put("receipt_path", entry.receiptPath().toString());
			putNullable(gate, "receipt_sha256", entry.receiptFingerprint());
			gate.put("diagnostic", entry.diagnostic());
		}
		return result;
	}

	/**
	 * Reads one bounded strict JSON object.
	 *
	 * @param json the JSON codec
	 * @param source the source path
	 * @param description the document description
	 * @return the object
	 * @throws IOException if the document is invalid
	 */
	private static ObjectNode readObject(JsonMapper json, Path source, String description) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		String content;
		try
		{
			if (Files.size(source) > MAXIMUM_DOCUMENT_BYTES)
				throw new IOException(description + " exceeds its maximum size: " + source);
			content = Files.readString(source, StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new IOException("Cannot read " + description.toLowerCase(Locale.ROOT) + ": " + source, e);
		}
		try
		{
			JsonMapper strictJson = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			JsonNode value = strictJson.readTree(content);
			if (value instanceof ObjectNode object)
				return object;
			throw new IOException(description + " must be a JSON object: " + source);
		}
		catch (JacksonException e)
		{
			throw new IOException("Invalid " + description.toLowerCase(Locale.ROOT) + " JSON: " + source, e);
		}
	}

	/**
	 * Verifies an exact object field set.
	 *
	 * @param object the object
	 * @param expected the expected fields
	 * @param description the document description
	 * @param source the source path
	 * @throws IOException if fields differ
	 */
	private static void verifyFields(ObjectNode object, Set<String> expected, String description, Path source)
		throws IOException
	{
		if (!new HashSet<>(object.propertyNames()).equals(expected))
			throw new IOException(description + " has unsupported or missing fields: " + source);
	}

	/**
	 * Reads one required text value.
	 *
	 * @param object the object
	 * @param field the field
	 * @param source the source path
	 * @return the text
	 * @throws IOException if absent or not text
	 */
	private static String requiredText(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.get(field);
		if (value == null || !value.isString() || value.stringValue().isBlank())
			throw new IOException("Missing or blank " + field + " in " + source);
		return value.stringValue();
	}

	/**
	 * Reads a nullable text value.
	 *
	 * @param object the object
	 * @param field the field
	 * @param source the source path
	 * @return the text or null
	 * @throws IOException if the value is neither null nor text
	 */
	private static String requiredNullableText(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.get(field);
		if (value == null || value.isNull())
			return null;
		if (!value.isString())
			throw new IOException(field + " must be text or null: " + source);
		return value.stringValue();
	}

	/**
	 * Reads a nullable instant.
	 *
	 * @param object the object
	 * @param field the field
	 * @param source the source path
	 * @return the instant or null
	 * @throws IOException if the value is invalid
	 */
	private static Instant instant(ObjectNode object, String field, Path source) throws IOException
	{
		String value = requiredNullableText(object, field, source);
		if (value == null)
			return null;
		try
		{
			return Instant.parse(value);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Invalid " + field + " in " + source, e);
		}
	}

	/**
	 * Reads an array of paths.
	 *
	 * @param object the object
	 * @param source the source path
	 * @return the paths
	 * @throws IOException if the value is invalid
	 */
	private static List<Path> paths(ObjectNode object, Path source) throws IOException
	{
		JsonNode value = object.get("evidence_paths");
		if (!(value instanceof ArrayNode array))
			throw new IOException("evidence_paths must be an array: " + source);
		List<Path> result = new ArrayList<>();
		for (JsonNode item : array)
		{
			if (!item.isString() || item.stringValue().isBlank())
				throw new IOException("evidence_paths contains invalid text: " + source);
			result.add(Path.of(item.stringValue()));
		}
		return result;
	}

	/**
	 * Reads an array of strings.
	 *
	 * @param object the object
	 * @param field the field
	 * @param source the source path
	 * @return the strings
	 * @throws IOException if the value is invalid
	 */
	private static List<String> strings(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.get(field);
		if (!(value instanceof ArrayNode array))
			throw new IOException(field + " must be an array: " + source);
		List<String> result = new ArrayList<>();
		for (JsonNode item : array)
		{
			if (!item.isString())
				throw new IOException(field + " contains a non-text value: " + source);
			result.add(item.stringValue());
		}
		return result;
	}

	/**
	 * Writes a nullable text value.
	 *
	 * @param object the object
	 * @param field the field
	 * @param value the value
	 */
	private static void putNullable(ObjectNode object, String field, Object value)
	{
		if (value == null)
			object.putNull(field);
		else
			object.put(field, value.toString());
	}

	/**
	 * Writes one document atomically.
	 *
	 * @param destination the destination
	 * @param content the content
	 * @throws IOException if writing fails
	 */
	private static void writeText(Path destination, String content) throws IOException
	{
		Path normalized = destination.toAbsolutePath().normalize();
		Path parent = normalized.getParent();
		if (parent == null)
			throw new IOException("Document destination has no parent: " + destination);
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, normalized.getFileName().toString(), ".tmp");
		try
		{
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
			try
			{
				Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
		catch (AtomicMoveNotSupportedException _)
			{
				Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Writes one new immutable document without replacing an existing receipt.
	 *
	 * @param destination the receipt destination
	 * @param content the document content
	 * @throws IOException if the destination already exists or writing fails
	 */
	private static void writeNewText(Path destination, String content) throws IOException
	{
		Path normalized = destination.toAbsolutePath().normalize();
		Path parent = normalized.getParent();
		if (parent == null)
			throw new IOException("Document destination has no parent: " + destination);
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, normalized.getFileName().toString(), ".tmp");
		try
		{
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
			Files.createLink(normalized, temporary);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Validates nonblank text.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireText(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Verifies each passed-gate evidence file before its receipt can become durable.
	 *
	 * @param paths the evidence paths
	 * @param fingerprints their declared fingerprints
	 */
	private static void verifyEvidence(List<Path> paths, List<String> fingerprints)
	{
		for (int index = 0; index < paths.size(); ++index)
		{
			Path path = paths.get(index);
			if (!Files.isRegularFile(path))
				throw new IllegalArgumentException("Passed-gate evidence must be a regular file: " + path);
			try
			{
				if (!Fingerprints.ofFile(path).equals(fingerprints.get(index)))
					throw new IllegalArgumentException("Passed-gate evidence fingerprint differs: " + path);
			}
			catch (IOException e)
			{
				throw new IllegalArgumentException("Unable to fingerprint passed-gate evidence: " + path, e);
			}
		}
	}
}
