package io.github.cowwoc.cat.common.optimization.certification;
import io.github.cowwoc.cat.common.RecordStore;

import io.github.cowwoc.cat.common.BoundedJsonLines;
import io.github.cowwoc.cat.common.Fingerprints;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Owns the append-only candidate allowance ledger for one optimization lineage.
 */
public final class CandidateAllowanceLedger
{
	private static final int SCHEMA_VERSION = 1;
	private static final int MAXIMUM_ASSIGNMENT_CHARACTERS = 16 * 1024;
	private static final String ROOT_FINGERPRINT = "ROOT";
	private static final BigDecimal MAXIMUM_LINEAGE_BUDGET = new BigDecimal("0.05");
	private static final Set<String> MANIFEST_FIELDS = Set.of("schema_version", "lineage_id", "total_budget",
		"schedule", "assignment_ledger");
	private static final Set<String> ASSIGNMENT_FIELDS = Set.of("candidate_id", "ordinal", "allowance",
		"assigned_at", "previous_sha256", "entry_sha256");

	/**
	 * Prevents construction of this static ledger owner.
	 */
	private CandidateAllowanceLedger()
	{
	}

	/**
	 * Creates an empty lineage ledger and its fixed manifest.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the new manifest path
	 * @param lineageId the stable optimization-lineage identifier
	 * @param totalBudget the maximum false-certification probability for the lineage
	 * @param schedule the fixed candidate-allocation schedule
	 * @throws IOException if the path already exists or the ledger cannot be written
	 */
	public static void initialize(JsonMapper json, Path manifestPath, String lineageId, BigDecimal totalBudget,
		Schedule schedule) throws IOException
	{
		if (json == null)
			throw new NullPointerException("json must not be null");
		if (manifestPath == null)
			throw new NullPointerException("manifestPath must not be null");
		validateIdentifier(lineageId, "lineageId");
		validateBudget(totalBudget);
		if (schedule == null)
			throw new NullPointerException("schedule must not be null");

		Path normalizedManifest = manifestPath.toAbsolutePath().normalize();
		RecordStore.withLock(normalizedManifest, () ->
		{
			if (Files.exists(normalizedManifest))
				throw new IOException("Candidate allowance manifest already exists: " + normalizedManifest);
			Path assignmentLedger = assignmentLedgerPath(normalizedManifest);
			if (Files.exists(assignmentLedger))
				throw new IOException("Candidate allowance assignment ledger already exists: " + assignmentLedger);
			RecordStore.writeText(assignmentLedger, "");
			try
			{
				ObjectNode manifest = json.createObjectNode();
				manifest.put("schema_version", SCHEMA_VERSION);
				manifest.put("lineage_id", lineageId);
				manifest.put("total_budget", totalBudget.toPlainString());
				manifest.put("schedule", schedule.externalValue);
				manifest.put("assignment_ledger", assignmentLedger.toString());
				RecordStore.write(json, normalizedManifest, manifest);
			}
			catch (IOException e)
			{
				Files.deleteIfExists(assignmentLedger);
				throw e;
			}
		});
	}

	/**
	 * Assigns the next predeclared allowance before a candidate produces formal evidence.
	 *
	 * <p>An identical candidate retry returns its retained assignment. Candidate order is the only schedule input; a
	 * runtime agent cannot choose the allowance.</p>
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the existing manifest path
	 * @param candidateId the immutable formal-candidate identifier
	 * @param assignedAt the assignment time recorded before formal evaluation
	 * @return the retained or newly appended assignment
	 * @throws IOException if the ledger is malformed, exhausted, or cannot be updated
	 */
	public static Assignment assign(JsonMapper json, Path manifestPath, String candidateId, Instant assignedAt)
		throws IOException
	{
		if (json == null)
			throw new NullPointerException("json must not be null");
		if (manifestPath == null)
			throw new NullPointerException("manifestPath must not be null");
		validateIdentifier(candidateId, "candidateId");
		if (assignedAt == null)
			throw new NullPointerException("assignedAt must not be null");

		Path normalizedManifest = manifestPath.toAbsolutePath().normalize();
		Assignment[] result = new Assignment[1];
		RecordStore.withLock(normalizedManifest, () ->
		{
			Manifest manifest = readManifest(json, normalizedManifest);
			Scan scan = scan(json, manifest, candidateId);
			if (scan.match != null)
			{
				result[0] = scan.match;
				return;
			}

			int ordinal = scan.snapshot.assignmentCount() + 1;
			BigDecimal allowance = manifest.schedule.allowance(manifest.totalBudget, ordinal);
			BigDecimal assignedAllowance = scan.snapshot.assignedAllowance().add(allowance);
			if (assignedAllowance.compareTo(manifest.totalBudget) > 0)
				throw new IOException("Candidate allowance would exceed the optimization-lineage budget");
			Assignment assignment = createAssignment(json, candidateId, ordinal, allowance, assignedAt,
				scan.snapshot.ledgerFingerprint());
			append(json, manifest.assignmentLedger, assignment);
			result[0] = assignment;
		});
		return result[0];
	}

	/**
	 * Reads and validates the bounded summary of an unbounded assignment ledger.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the existing manifest path
	 * @return the validated lineage summary
	 * @throws IOException if the manifest or ledger violates its schema or schedule
	 */
	public static Snapshot read(JsonMapper json, Path manifestPath) throws IOException
	{
		if (json == null)
			throw new NullPointerException("json must not be null");
		if (manifestPath == null)
			throw new NullPointerException("manifestPath must not be null");
		Manifest manifest = readManifest(json, manifestPath.toAbsolutePath().normalize());
		return scan(json, manifest, null).snapshot;
	}

	/**
	 * Finds one candidate's immutable assignment without loading the unbounded ledger into memory.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the existing manifest path
	 * @param candidateId the formal-candidate identifier
	 * @return the retained assignment, or empty when the candidate has no formal allowance
	 * @throws IOException if the manifest or ledger violates its schema or schedule
	 */
	public static Optional<Assignment> find(JsonMapper json, Path manifestPath, String candidateId) throws IOException
	{
		if (json == null)
			throw new NullPointerException("json must not be null");
		if (manifestPath == null)
			throw new NullPointerException("manifestPath must not be null");
		validateIdentifier(candidateId, "candidateId");
		Manifest manifest = readManifest(json, manifestPath.toAbsolutePath().normalize());
		return Optional.ofNullable(scan(json, manifest, candidateId).match);
	}

	/**
	 * Creates one hash-chained assignment.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param candidateId the candidate identifier
	 * @param ordinal the one-based assignment order
	 * @param allowance the exact assigned allowance
	 * @param assignedAt the assignment time
	 * @param previousFingerprint the previous entry fingerprint
	 * @return the complete immutable assignment
	 * @throws IOException if the entry identity cannot be encoded
	 */
	private static Assignment createAssignment(JsonMapper json, String candidateId, int ordinal, BigDecimal allowance,
		Instant assignedAt, String previousFingerprint) throws IOException
	{
		ObjectNode identity = assignmentIdentity(json, candidateId, ordinal, allowance, assignedAt, previousFingerprint);
		String entryFingerprint = Fingerprints.ofUtf8(json.writeValueAsString(identity));
		return new Assignment(candidateId, ordinal, allowance, assignedAt, previousFingerprint, entryFingerprint);
	}

	/**
	 * Appends and forces one complete JSON Lines assignment.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param assignmentLedger the append-only assignment ledger
	 * @param assignment the immutable assignment
	 * @throws IOException if the complete record cannot be retained
	 */
	private static void append(JsonMapper json, Path assignmentLedger, Assignment assignment) throws IOException
	{
		ObjectNode encoded = assignmentIdentity(json, assignment.candidateId(), assignment.ordinal(),
			assignment.allowance(), assignment.assignedAt(), assignment.previousFingerprint());
		encoded.put("entry_sha256", assignment.entryFingerprint());
		ByteBuffer bytes = StandardCharsets.UTF_8.encode(json.writeValueAsString(encoded) + System.lineSeparator());
		try (FileChannel channel = FileChannel.open(assignmentLedger, APPEND, WRITE))
		{
			while (bytes.hasRemaining())
				channel.write(bytes);
			channel.force(true);
		}
	}

	/**
	 * Builds the canonical assignment identity whose hash chains the ledger.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param candidateId the candidate identifier
	 * @param ordinal the one-based assignment order
	 * @param allowance the exact assigned allowance
	 * @param assignedAt the assignment time
	 * @param previousFingerprint the previous entry fingerprint
	 * @return the canonical identity
	 */
	private static ObjectNode assignmentIdentity(JsonMapper json, String candidateId, int ordinal,
		BigDecimal allowance, Instant assignedAt, String previousFingerprint)
	{
		ObjectNode result = json.createObjectNode();
		result.put("candidate_id", candidateId);
		result.put("ordinal", ordinal);
		result.put("allowance", allowance.toPlainString());
		result.put("assigned_at", assignedAt.toString());
		result.put("previous_sha256", previousFingerprint);
		return result;
	}

	/**
	 * Streams and verifies every assignment while retaining only a bounded summary and an optional requested entry.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifest the validated manifest
	 * @param requestedCandidateId the candidate to find, or {@code null} when no assignment is requested
	 * @return the bounded summary and optional matching assignment
	 * @throws IOException if any entry is malformed or violates the fixed schedule
	 */
	private static Scan scan(JsonMapper json, Manifest manifest, String requestedCandidateId) throws IOException
	{
		ScanState state = new ScanState(json, manifest, requestedCandidateId);
		BoundedJsonLines.read(manifest.assignmentLedger, MAXIMUM_ASSIGNMENT_CHARACTERS,
			"Candidate allowance ledger", state::accept);
		return state.result();
	}

	/**
	 * Parses one JSON Lines assignment object.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param line the encoded assignment
	 * @param source the source path used in diagnostics
	 * @return the parsed object
	 * @throws IOException if the line is not valid JSON or not an object
	 */
	private static ObjectNode parseAssignment(JsonMapper json, String line, Path source) throws IOException
	{
		try
		{
			JsonNode parsed = json.readTree(line);
			if (parsed instanceof ObjectNode assignment)
				return assignment;
			throw new IOException("Candidate allowance assignment is not a JSON object: " + source);
		}
		catch (JacksonException e)
		{
			throw new IOException("Candidate allowance ledger contains invalid JSON: " + source, e);
		}
	}

	/**
	 * Decodes and verifies one assignment against its expected position and chain predecessor.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param encoded the assignment object
	 * @param manifest the fixed lineage manifest
	 * @param expectedOrdinal the required one-based ordinal
	 * @param previousFingerprint the required chain predecessor
	 * @return the validated assignment
	 * @throws IOException if the assignment violates its schema, schedule, or hash chain
	 */
	private static Assignment decodeAssignment(JsonMapper json, ObjectNode encoded, Manifest manifest,
		int expectedOrdinal, String previousFingerprint) throws IOException
	{
		if (!new HashSet<>(encoded.propertyNames()).equals(ASSIGNMENT_FIELDS))
			throw new IOException("Candidate allowance assignment has unsupported or missing fields: " +
				manifest.assignmentLedger);
		String candidateId = requiredText(encoded, "candidate_id", manifest.assignmentLedger);
		int ordinal = requiredPositiveInteger(encoded, "ordinal", manifest.assignmentLedger);
		if (ordinal != expectedOrdinal)
			throw new IOException("Candidate allowance assignment has a nonsequential ordinal: " +
				manifest.assignmentLedger);
		BigDecimal allowance = decimal(encoded, "allowance", manifest.assignmentLedger);
		BigDecimal expectedAllowance = manifest.schedule.allowance(manifest.totalBudget, ordinal);
		if (allowance.compareTo(expectedAllowance) != 0)
			throw new IOException("Candidate allowance assignment differs from its fixed schedule: " +
				manifest.assignmentLedger);
		Instant assignedAt = instant(encoded, "assigned_at", manifest.assignmentLedger);
		String declaredPrevious = requiredText(encoded, "previous_sha256", manifest.assignmentLedger);
		if (!declaredPrevious.equals(previousFingerprint))
			throw new IOException("Candidate allowance assignment breaks the ledger hash chain: " +
				manifest.assignmentLedger);
		String entryFingerprint = requiredText(encoded, "entry_sha256", manifest.assignmentLedger);
		Assignment expected = createAssignment(json, candidateId, ordinal, allowance, assignedAt, previousFingerprint);
		if (!entryFingerprint.equals(expected.entryFingerprint()))
			throw new IOException("Candidate allowance assignment fingerprint is invalid: " +
				manifest.assignmentLedger);
		return expected;
	}

	/**
	 * Reads and validates the fixed manifest.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the normalized manifest path
	 * @return the validated manifest
	 * @throws IOException if the manifest is malformed
	 */
	private static Manifest readManifest(JsonMapper json, Path manifestPath) throws IOException
	{
		JsonNode document = RecordStore.read(json, manifestPath);
		if (!(document instanceof ObjectNode manifest))
			throw new IOException("Candidate allowance manifest is not a JSON object: " + manifestPath);
		if (!new HashSet<>(manifest.propertyNames()).equals(MANIFEST_FIELDS))
			throw new IOException("Candidate allowance ledger has unsupported or missing fields: " + manifestPath);
		if (!manifest.path("schema_version").isIntegralNumber() ||
			manifest.path("schema_version").asInt(-1) != SCHEMA_VERSION)
		{
			throw new IOException("Unsupported candidate allowance ledger schema: " + manifestPath);
		}

		String lineageId = requiredText(manifest, "lineage_id", manifestPath);
		BigDecimal totalBudget = decimal(manifest, "total_budget", manifestPath);
		try
		{
			validateBudget(totalBudget);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Candidate allowance manifest has an invalid total budget: " + manifestPath, e);
		}
		Schedule schedule = Schedule.fromExternalValue(requiredText(manifest, "schedule", manifestPath), manifestPath);
		Path assignmentLedger;
		try
		{
			assignmentLedger = Path.of(requiredText(manifest, "assignment_ledger", manifestPath)).normalize();
		}
		catch (java.nio.file.InvalidPathException e)
		{
			throw new IOException("Candidate allowance manifest has an invalid assignment ledger path: " + manifestPath,
				e);
		}
		if (!assignmentLedger.isAbsolute() || !Files.isRegularFile(assignmentLedger))
			throw new IOException("Candidate allowance assignment ledger is unavailable: " + assignmentLedger);
		return new Manifest(lineageId, totalBudget, schedule, assignmentLedger);
	}

	/**
	 * Returns a required non-blank text field.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path used in diagnostics
	 * @return the field value
	 * @throws IOException if the field is absent or blank
	 */
	private static String requiredText(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Candidate allowance record has no " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Parses one exact decimal string.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path used in diagnostics
	 * @return the decimal value
	 * @throws IOException if the field is absent or malformed
	 */
	private static BigDecimal decimal(ObjectNode object, String field, Path source) throws IOException
	{
		String value = requiredText(object, field, source);
		try
		{
			return new BigDecimal(value);
		}
		catch (NumberFormatException e)
		{
			throw new IOException("Candidate allowance record has an invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Parses one positive integer field.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path used in diagnostics
	 * @return the positive integer
	 * @throws IOException if the field is absent or invalid
	 */
	private static int requiredPositiveInteger(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1)
			throw new IOException("Candidate allowance record has an invalid " + field + ": " + source);
		return value.asInt();
	}

	/**
	 * Parses one ISO-8601 instant.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path used in diagnostics
	 * @return the parsed instant
	 * @throws IOException if the field is absent or malformed
	 */
	private static Instant instant(ObjectNode object, String field, Path source) throws IOException
	{
		String value = requiredText(object, field, source);
		try
		{
			return Instant.parse(value);
		}
		catch (DateTimeException e)
		{
			throw new IOException("Candidate allowance record has an invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Returns the fixed assignment-ledger path beside a manifest.
	 *
	 * @param manifestPath the manifest path
	 * @return the assignment-ledger path
	 * @throws IOException if the manifest has no filename
	 */
	private static Path assignmentLedgerPath(Path manifestPath) throws IOException
	{
		Path filename = manifestPath.getFileName();
		if (filename == null)
			throw new IOException("Candidate allowance manifest has no filename: " + manifestPath);
		return manifestPath.resolveSibling(filename + ".assignments.jsonl");
	}

	/**
	 * Validates a stable non-blank identifier without normalizing its identity.
	 *
	 * @param value the identifier
	 * @param name the caller-facing parameter name
	 */
	private static void validateIdentifier(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
	}

	/**
	 * Validates the lineage-wide probability budget.
	 *
	 * @param totalBudget the proposed lineage budget
	 */
	private static void validateBudget(BigDecimal totalBudget)
	{
		if (totalBudget == null)
			throw new NullPointerException("totalBudget must not be null");
		if (totalBudget.signum() <= 0 || totalBudget.compareTo(MAXIMUM_LINEAGE_BUDGET) > 0)
			throw new IllegalArgumentException("totalBudget must be greater than zero and no more than 0.05");
	}

	/**
	 * Names the versioned, runtime-immutable candidate allowance schedule.
	 */
	public enum Schedule
	{
		HALVING_V1("halving-v1");

		private final String externalValue;

		/**
		 * Creates a schedule value.
		 *
		 * @param externalValue the persisted identifier
		 */
		Schedule(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Calculates the allowance for a one-based candidate ordinal.
		 *
		 * @param totalBudget the lineage-wide budget
		 * @param ordinal the one-based candidate ordinal
		 * @return the exact allowance
		 */
		public BigDecimal allowance(BigDecimal totalBudget, int ordinal)
		{
			validateBudget(totalBudget);
			if (ordinal < 1)
				throw new IllegalArgumentException("ordinal must be positive");
			return totalBudget.divide(BigDecimal.TWO.pow(ordinal));
		}

		/**
		 * Returns the persisted schedule identifier.
		 *
		 * @return the persisted identifier
		 */
		public String externalValue()
		{
			return externalValue;
		}

		/**
		 * Parses one exact schedule identifier at an untyped caller boundary.
		 *
		 * @param value the untyped identifier
		 * @return the supported schedule
		 */
		public static Schedule parse(String value)
		{
			if (HALVING_V1.externalValue.equals(value))
				return HALVING_V1;
			throw new IllegalArgumentException("Unsupported candidate allowance schedule: " + value);
		}

		/**
		 * Parses the exact persisted identifier.
		 *
		 * @param value the untyped persisted value
		 * @param source the source path used in diagnostics
		 * @return the supported schedule
		 * @throws IOException if the schedule is unsupported
		 */
		private static Schedule fromExternalValue(String value, Path source) throws IOException
		{
			if (HALVING_V1.externalValue.equals(value))
				return HALVING_V1;
			throw new IOException("Unsupported candidate allowance schedule " + value + ": " + source);
		}
	}

	/**
	 * Holds one immutable allowance assigned before formal evidence exists.
	 *
	 * @param candidateId the formal-candidate identifier
	 * @param ordinal the one-based assignment order
	 * @param allowance the candidate's false-certification probability allowance
	 * @param assignedAt the assignment time
	 * @param previousFingerprint the previous assignment's fingerprint
	 * @param entryFingerprint the fingerprint of this assignment identity
	 */
	public record Assignment(String candidateId, int ordinal, BigDecimal allowance, Instant assignedAt,
		String previousFingerprint, String entryFingerprint)
	{
		/**
		 * Validates one immutable assignment.
		 *
		 * @param candidateId the formal-candidate identifier
		 * @param ordinal the one-based assignment order
		 * @param allowance the candidate's false-certification probability allowance
		 * @param assignedAt the assignment time
		 * @param previousFingerprint the previous assignment's fingerprint
		 * @param entryFingerprint the fingerprint of this assignment identity
		 */
		public Assignment
		{
			validateIdentifier(candidateId, "candidateId");
			if (ordinal < 1)
				throw new IllegalArgumentException("ordinal must be positive");
			if (allowance == null)
				throw new NullPointerException("allowance must not be null");
			if (allowance.signum() <= 0)
				throw new IllegalArgumentException("allowance must be positive");
			if (assignedAt == null)
				throw new NullPointerException("assignedAt must not be null");
			validateIdentifier(previousFingerprint, "previousFingerprint");
			validateIdentifier(entryFingerprint, "entryFingerprint");
		}
	}

	/**
	 * Holds the bounded validated state of one lineage allowance ledger.
	 *
	 * @param lineageId the optimization-lineage identifier
	 * @param totalBudget the lineage-wide probability budget
	 * @param schedule the fixed assignment schedule
	 * @param assignmentCount the number of retained assignments
	 * @param assignedAllowance the sum of assigned allowances
	 * @param remainingAllowance the unassigned budget
	 * @param ledgerFingerprint the last hash-chain entry, or {@code ROOT} for an empty ledger
	 */
	public record Snapshot(String lineageId, BigDecimal totalBudget, Schedule schedule, int assignmentCount,
		BigDecimal assignedAllowance, BigDecimal remainingAllowance, String ledgerFingerprint)
	{
	}

	/**
	 * Holds the validated fixed manifest fields.
	 *
	 * @param lineageId the optimization-lineage identifier
	 * @param totalBudget the lineage-wide probability budget
	 * @param schedule the fixed assignment schedule
	 * @param assignmentLedger the append-only JSON Lines ledger
	 */
	private record Manifest(String lineageId, BigDecimal totalBudget, Schedule schedule, Path assignmentLedger)
	{
	}

	/**
	 * Holds a streamed ledger summary and optional requested assignment.
	 *
	 * @param snapshot the bounded ledger summary
	 * @param match the requested assignment, or {@code null}
	 */
	private record Scan(Snapshot snapshot, Assignment match)
	{
	}

	/**
	 * Accumulates a bounded ledger summary while assignments are streamed.
	 */
	private static final class ScanState
	{
		private final JsonMapper json;
		private final Manifest manifest;
		private final String requestedCandidateId;
		private final Set<String> candidateIds = new HashSet<>();
		private int assignmentCount;
		private BigDecimal assignedAllowance = BigDecimal.ZERO;
		private String previousFingerprint = ROOT_FINGERPRINT;
		private Assignment match;

		/**
		 * Creates one stream accumulator.
		 *
		 * @param json the ledger-owned JSON mapper
		 * @param manifest the fixed lineage manifest
		 * @param requestedCandidateId the candidate to find, or {@code null}
		 */
		private ScanState(JsonMapper json, Manifest manifest, String requestedCandidateId)
		{
			this.json = json;
			this.manifest = manifest;
			this.requestedCandidateId = requestedCandidateId;
		}

		/**
		 * Validates and accumulates one assignment.
		 *
		 * @param line the bounded JSON record
		 * @param lineNumber the one-based source line, retained for the reader contract
		 * @throws IOException if the assignment violates its schema, schedule, or chain
		 */
		private void accept(String line, int lineNumber) throws IOException
		{
			if (line.isBlank())
				throw new IOException("Candidate allowance ledger contains a blank record: " +
					manifest.assignmentLedger + " (line " + lineNumber + ")");
			ObjectNode encoded = parseAssignment(json, line, manifest.assignmentLedger);
			Assignment assignment = decodeAssignment(json, encoded, manifest, assignmentCount + 1,
				previousFingerprint);
			if (!candidateIds.add(assignment.candidateId()))
				throw new IOException("Candidate allowance ledger repeats candidate: " + assignment.candidateId());
			++assignmentCount;
			assignedAllowance = assignedAllowance.add(assignment.allowance());
			previousFingerprint = assignment.entryFingerprint();
			if (assignment.candidateId().equals(requestedCandidateId))
				match = assignment;
		}

		/**
		 * Returns the complete validated summary.
		 *
		 * @return the bounded summary and optional requested assignment
		 * @throws IOException if accumulated allowances exceed the lineage budget
		 */
		private Scan result() throws IOException
		{
			if (assignedAllowance.compareTo(manifest.totalBudget) > 0)
			{
				throw new IOException("Candidate allowance ledger exceeds its optimization-lineage budget: " +
					manifest.assignmentLedger);
			}
			Snapshot snapshot = new Snapshot(manifest.lineageId, manifest.totalBudget, manifest.schedule,
				assignmentCount, assignedAllowance, manifest.totalBudget.subtract(assignedAllowance),
				previousFingerprint);
			return new Scan(snapshot, match);
		}
	}
}
