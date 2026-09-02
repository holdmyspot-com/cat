package io.github.cowwoc.cat.common.optimization;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.WRITE;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.BoundedJsonLines;
import io.github.cowwoc.cat.common.RecordStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * Persists and replays the optimization state machine through a hash-chained append-only transition ledger.
 */
public final class CertificationProcessLedger
{
	private static final int SCHEMA_VERSION = 2;
	private static final int MAXIMUM_MANIFEST_BYTES = 64 * 1024;
	private static final int MAXIMUM_TRANSITION_CHARACTERS = 64 * 1024;
	private static final String ROOT_FINGERPRINT = "ROOT";
	private static final Set<String> MANIFEST_FIELDS = Set.of("schema_version", "certification_process_id",
		"contract_sha256", "environment_sha256", "initial_operation", "transition_ledger");
	private static final Set<String> ENTRY_FIELDS = Set.of("sequence", "attempt", "operation", "result", "successor",
		"evidence_sha256", "completed_at", "previous_sha256", "entry_sha256");

	/**
	 * Creates one empty durable certification process.
	 *
	 * @param json the JSON codec
	 * @param manifestPath the new certification process manifest
	 * @param certificationProcessIdentifier the stable certification process identifier
	 * @param contractFingerprint the immutable contract fingerprint
	 * @param environmentFingerprint the immutable environment fingerprint
	 * @throws IOException if the certification process already exists or cannot be published
	 */
	public static void initialize(JsonMapper json, Path manifestPath, String certificationProcessIdentifier,
		String contractFingerprint, String environmentFingerprint) throws IOException
	{
		initializeForOperation(json, manifestPath, certificationProcessIdentifier, contractFingerprint,
			environmentFingerprint, OperationKind.DISCOVER_PROMPT_SYSTEM);
	}

	/**
	 * Initializes a fixture at any registered operation.
	 *
	 * @param json the JSON codec
	 * @param manifestPath the new certification process manifest
	 * @param certificationProcessIdentifier the stable certification process identifier
	 * @param contractFingerprint the immutable contract fingerprint
	 * @param environmentFingerprint the immutable environment fingerprint
	 * @param initialOperation the fixture's initial operation
	 * @throws IOException if the certification process cannot be published
	 */
	static void initializeForFixture(JsonMapper json, Path manifestPath, String certificationProcessIdentifier,
		String contractFingerprint, String environmentFingerprint, OperationKind initialOperation) throws IOException
	{
		initializeForOperation(json, manifestPath, certificationProcessIdentifier, contractFingerprint,
			environmentFingerprint, initialOperation);
}

	/**
	 * Initializes a process at a validated operation.
	 *
	 * @param json the JSON codec
	 * @param manifestPath the manifest path
	 * @param certificationProcessIdentifier the process identifier
	 * @param contractFingerprint the contract fingerprint
	 * @param environmentFingerprint the environment fingerprint
	 * @param initialOperation the first operation
	 * @throws IOException if the process cannot be published
	 */
	private static void initializeForOperation(JsonMapper json, Path manifestPath, String certificationProcessIdentifier,
		String contractFingerprint, String environmentFingerprint, OperationKind initialOperation) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		Objects.requireNonNull(initialOperation, "initialOperation must not be null");
		if (!OptimizationOperationRegistry.definitions().containsKey(initialOperation))
			throw new IllegalArgumentException("Unregistered initial operation: " + initialOperation);
		certificationProcessIdentifier = requireText(certificationProcessIdentifier,
			"certificationProcessIdentifier");
		contractFingerprint = requireSha256(contractFingerprint, "contractFingerprint");
		environmentFingerprint = requireSha256(environmentFingerprint, "environmentFingerprint");
		Path manifest = manifestPath.toAbsolutePath().normalize();
		String retainedCertificationProcessIdentifier = certificationProcessIdentifier;
		String retainedContractFingerprint = contractFingerprint;
		String retainedEnvironmentFingerprint = environmentFingerprint;
		OperationKind retainedInitialOperation = initialOperation;
		RecordStore.withLock(manifest, () ->
		{
			if (Files.exists(manifest))
				throw new IOException("Certification process manifest already exists: " + manifest);
			Path ledger = expectedLedgerPath(manifest);
			if (Files.exists(ledger))
				throw new IOException("Certification process transition ledger already exists: " + ledger);
			RecordStore.writeText(ledger, "");
			try
			{
				ObjectNode document = json.createObjectNode();
				document.put("schema_version", SCHEMA_VERSION);
				document.put("certification_process_id", retainedCertificationProcessIdentifier);
				document.put("contract_sha256", retainedContractFingerprint);
				document.put("environment_sha256", retainedEnvironmentFingerprint);
				document.put("initial_operation", retainedInitialOperation.externalValue());
				document.put("transition_ledger", ledger.getFileName().toString());
				RecordStore.write(json, manifest, document);
			}
			catch (IOException e)
			{
				Files.deleteIfExists(ledger);
				throw e;
			}
		});
	}

	/**
	 * Records one transition or returns the identical retained transition after a retry.
	 *
	 * @param json the JSON codec
	 * @param manifestPath the certification process manifest
	 * @param expectedSequence the caller's last verified sequence
	 * @param result the current operation's terminal result
	 * @param successor the selected registered successor, or empty at certification process termination
	 * @param evidenceFingerprint the terminal evidence fingerprint
	 * @param completedAt the completion time used only for a new transition
	 * @return the retained or newly appended transition
	 * @throws IOException if the certification process is malformed, the writer is stale, or the transition is not
	 *                     permitted
	 */
	public static Entry record(JsonMapper json, Path manifestPath, long expectedSequence,
		OperationResultKind result,
		Optional<OperationKind> successor, String evidenceFingerprint, Instant completedAt)
		throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		if (expectedSequence < 0)
			throw new IllegalArgumentException("expectedSequence must not be negative");
		Objects.requireNonNull(result, "result must not be null");
		Objects.requireNonNull(successor, "successor must not be null");
		evidenceFingerprint = requireSha256(evidenceFingerprint, "evidenceFingerprint");
		Objects.requireNonNull(completedAt, "completedAt must not be null");
		Path manifest = manifestPath.toAbsolutePath().normalize();
		String retainedEvidenceFingerprint = evidenceFingerprint;
		Entry[] retained = new Entry[1];
		RecordStore.withLock(manifest, () ->
		{
			Manifest configuration = readManifest(json, manifest);
			Scan scan = scan(json, configuration, expectedSequence + 1);
			long actualSequence = scan.snapshot().state().sequence();
			if (expectedSequence < actualSequence)
			{
				Entry existing = scan.requestedEntry().orElseThrow(() ->
					new IOException("Certification process is missing its completed retry transition"));
				if (existing.result() != result || !existing.successor().equals(successor) ||
					!existing.evidenceFingerprint().equals(retainedEvidenceFingerprint))
				{
					throw new IOException("Completed certification process transition differs from its retry");
				}
				retained[0] = existing;
				return;
			}
			if (expectedSequence > actualSequence)
			{
				throw new IOException("Certification process writer expected sequence " + expectedSequence +
					" but the durable sequence is " + actualSequence);
			}
			OptimizationStateMachine.State current = scan.snapshot().state();
			if (current.terminal())
				throw new IOException("A terminal certification process cannot record another transition");
			try
			{
				OptimizationStateMachine.advance(current, result, successor);
			}
			catch (IllegalArgumentException | IllegalStateException e)
			{
				throw new IOException("Certification process transition is not permitted: " + e.getMessage(), e);
			}
			Entry entry = createEntry(json, actualSequence + 1, 1, current.currentOperation().orElseThrow(), result,
				successor, retainedEvidenceFingerprint, completedAt, scan.snapshot().ledgerFingerprint());
			append(json, configuration.transitionLedger(), entry);
			retained[0] = entry;
		});
		return retained[0];
	}

	/**
	 * Records the verified completed receipt owned by one operation directory.
	 *
	 * @param json the JSON codec
	 * @param manifestPath the certification process manifest
	 * @param expectedSequence the caller's last verified transition sequence
	 * @param operationDirectory the operation directory owning the receipt
	 * @return the retained or newly appended transition
	 * @throws IOException if the receipt or certification process is invalid or conflicts
	 */
	public static Entry recordReceipt(JsonMapper json, Path manifestPath, long expectedSequence,
		OperationDirectory operationDirectory) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		Objects.requireNonNull(operationDirectory, "operationDirectory must not be null");
		if (expectedSequence < 0)
			throw new IllegalArgumentException("expectedSequence must not be negative");
		Optional<OperationDirectory.Receipt> verified = operationDirectory.verifiedTerminalReceipt();
		OperationDirectory.Receipt receipt = verified.orElseThrow(() ->
			new IOException("Operation does not contain a completed terminal receipt"));
		if (receipt.state() != OperationState.COMPLETED || receipt.resultKind().isEmpty())
			throw new IOException("Operation receipt is not completed: " + operationDirectory.root());
		String receiptFingerprint = Fingerprints.ofFile(operationDirectory.receiptPath());
		Path manifest = manifestPath.toAbsolutePath().normalize();
		Entry[] retained = new Entry[1];
		RecordStore.withLock(manifest, () ->
		{
			Manifest configuration = readManifest(json, manifest);
			Scan scan = scan(json, configuration, expectedSequence + 1);
			long actualSequence = scan.snapshot().state().sequence();
			if (expectedSequence < actualSequence)
			{
				Entry existing = scan.requestedEntry().orElseThrow(() ->
					new IOException("Certification process is missing its completed retry transition"));
				if (existing.attempt() != receipt.attempt() || existing.operation() != receipt.operationKind() ||
					existing.result() != receipt.resultKind().orElseThrow() ||
					!existing.successor().equals(receipt.successorKind()) ||
					!existing.evidenceFingerprint().equals(receiptFingerprint))
					throw new IOException("Completed certification process transition differs from its retry");
				retained[0] = existing;
				return;
			}
			if (expectedSequence > actualSequence)
				throw new IOException("Certification process writer expected sequence " + expectedSequence +
					" but the durable sequence is " + actualSequence);
			if (receipt.transitionSequence() != expectedSequence + 1)
				throw new IOException("Operation receipt transition sequence does not match the expected sequence");
			OperationKind current = scan.snapshot().state().currentOperation().orElseThrow(() ->
				new IOException("Certification process is already terminal"));
			if (receipt.operationKind() != current)
				throw new IOException("Operation receipt is not for the current certification process operation");
			OperationResultKind result = receipt.resultKind().orElseThrow();
			Optional<OperationKind> successor = receipt.successorKind();
			try
				{
					OptimizationStateMachine.advance(scan.snapshot().state(), result, successor);
				}
			catch (IllegalArgumentException | IllegalStateException e)
			{
				throw new IOException("Certification process transition is not permitted: " + e.getMessage(), e);
			}
			Entry entry = createEntry(json, expectedSequence + 1, receipt.attempt(), receipt.operationKind(), result,
				successor, receiptFingerprint, receipt.completedAt(), scan.snapshot().ledgerFingerprint());
			append(json, configuration.transitionLedger(), entry);
			retained[0] = entry;
		});
		return retained[0];
	}

	/**
	 * Reads and replays one complete certification process into a bounded state snapshot.
	 *
	 * @param json the JSON codec
	 * @param manifestPath the certification process manifest
	 * @return the verified certification process snapshot
	 * @throws IOException if the manifest, hash chain, or state-machine transition is invalid
	 */
	public static Snapshot read(JsonMapper json, Path manifestPath) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		Manifest manifest = readManifest(json, manifestPath.toAbsolutePath().normalize());
		return scan(json, manifest, -1).snapshot();
	}

	/**
	 * Reads one verified transition retained by the certification-process ledger.
	 *
	 * @param json the JSON codec
	 * @param manifestPath the certification-process manifest
	 * @param sequence the positive transition sequence
	 * @return the verified transition entry
	 * @throws IOException if the manifest or requested transition is missing or malformed
	 */
	static Entry entry(JsonMapper json, Path manifestPath, long sequence) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		if (sequence < 1)
			throw new IllegalArgumentException("sequence must be positive");
		Manifest manifest = readManifest(json, manifestPath.toAbsolutePath().normalize());
		return scan(json, manifest, sequence).requestedEntry().orElseThrow(() ->
			new IOException("Certification process transition is missing: " + sequence));
	}

	/**
	 * Streams and verifies every transition.
	 *
	 * @param json the JSON codec
	 * @param manifest the fixed manifest
	 * @param requestedSequence a transition sequence to retain, or {@code -1}
	 * @return the bounded scan result
	 * @throws IOException if a transition is malformed
	 */
	private static Scan scan(JsonMapper json, Manifest manifest, long requestedSequence) throws IOException
	{
		ScanState state = new ScanState(json, manifest, requestedSequence);
		BoundedJsonLines.read(manifest.transitionLedger(), MAXIMUM_TRANSITION_CHARACTERS,
			"Certification process transition ledger", state::accept);
		return state.result();
	}

	/**
	 * Creates one hash-chained transition entry.
	 *
	 * @param json the JSON codec
	 * @param sequence the one-based transition sequence
	 * @param attempt the one-based attempt number for this transition
	 * @param operation the completed operation
	 * @param result the operation result
	 * @param successor the selected successor
	 * @param evidenceFingerprint the evidence fingerprint
	 * @param completedAt the completion time
	 * @param previousFingerprint the hash-chain predecessor
	 * @return the transition entry
	 * @throws IOException if canonical JSON encoding fails
	 */
	private static Entry createEntry(JsonMapper json, long sequence, int attempt,
		OperationKind operation, OperationResultKind result,
		Optional<OperationKind> successor, String evidenceFingerprint, Instant completedAt,
		String previousFingerprint) throws IOException
	{
		ObjectNode identity = entryIdentity(json, sequence, attempt, operation, result, successor, evidenceFingerprint,
			completedAt, previousFingerprint);
		String fingerprint = Fingerprints.ofUtf8(json.writeValueAsString(identity));
		return new Entry(sequence, attempt, operation, result, successor, evidenceFingerprint, completedAt,
			previousFingerprint, fingerprint);
	}

	/**
	 * Appends and forces one complete transition line.
	 *
	 * @param json the JSON codec
	 * @param ledger the transition ledger
	 * @param entry the entry
	 * @throws IOException if durable append fails
	 */
	private static void append(JsonMapper json, Path ledger, Entry entry) throws IOException
	{
		ObjectNode encoded = entryIdentity(json, entry.sequence(), entry.attempt(), entry.operation(), entry.result(),
			entry.successor(),
			entry.evidenceFingerprint(), entry.completedAt(), entry.previousFingerprint());
		encoded.put("entry_sha256", entry.entryFingerprint());
		ByteBuffer bytes = StandardCharsets.UTF_8.encode(json.writeValueAsString(encoded) + System.lineSeparator());
		try (FileChannel channel = FileChannel.open(ledger, APPEND, WRITE))
		{
			while (bytes.hasRemaining())
				channel.write(bytes);
			channel.force(true);
		}
	}

	/**
	 * Creates the canonical transition identity.
	 *
	 * @param json the JSON codec
	 * @param sequence the one-based transition sequence
	 * @param attempt the one-based attempt number for this transition
	 * @param operation the completed operation
	 * @param result the operation result
	 * @param successor the selected successor
	 * @param evidenceFingerprint the evidence fingerprint
	 * @param completedAt the completion time
	 * @param previousFingerprint the hash-chain predecessor
	 * @return the canonical identity object
	 */
	private static ObjectNode entryIdentity(JsonMapper json, long sequence, int attempt,
		OperationKind operation, OperationResultKind result,
		Optional<OperationKind> successor, String evidenceFingerprint, Instant completedAt,
		String previousFingerprint)
	{
		ObjectNode identity = json.createObjectNode();
		identity.put("sequence", sequence);
		identity.put("attempt", attempt);
		identity.put("operation", operation.externalValue());
		identity.put("result", result.externalValue());
		if (successor.isPresent())
			identity.put("successor", successor.orElseThrow().externalValue());
		else
			identity.putNull("successor");
		identity.put("evidence_sha256", evidenceFingerprint);
		identity.put("completed_at", completedAt.toString());
		identity.put("previous_sha256", previousFingerprint);
		return identity;
	}

	/**
	 * Reads and validates the bounded fixed manifest.
	 *
	 * @param json the JSON codec
	 * @param manifestPath the manifest path
	 * @return the validated manifest
	 * @throws IOException if the manifest is malformed or unsafe
	 */
	private static Manifest readManifest(JsonMapper json, Path manifestPath) throws IOException
	{
		String content = BoundedInput.readUtf8(manifestPath, MAXIMUM_MANIFEST_BYTES, "Certification process manifest");
		ObjectNode document = parseObject(strict(json), content, "Certification process manifest", manifestPath);
		if (!new HashSet<>(document.propertyNames()).equals(MANIFEST_FIELDS))
			throw new IOException("Certification process manifest has unsupported or missing fields: " + manifestPath);
		if (!document.path("schema_version").isIntegralNumber() ||
			document.path("schema_version").asInt() != SCHEMA_VERSION)
		{
			throw new IOException("Unsupported certification process schema: " + manifestPath);
		}
		String ledgerName = requiredText(document, "transition_ledger", manifestPath);
		Path ledger = manifestPath.resolveSibling(ledgerName).toAbsolutePath().normalize();
		Path expectedLedger = expectedLedgerPath(manifestPath);
		if (!ledger.equals(expectedLedger))
			throw new IOException("Certification process names an unexpected transition ledger: " + ledger);
		OperationKind initialOperation = operation(requiredText(document, "initial_operation", manifestPath), manifestPath);
		return new Manifest(requiredText(document, "certification_process_id", manifestPath),
			requireSha256(requiredText(document, "contract_sha256", manifestPath), "contractFingerprint"),
			requireSha256(requiredText(document, "environment_sha256", manifestPath), "environmentFingerprint"),
			initialOperation, ledger);
	}

	/**
	 * Returns the only supported transition-ledger path for one manifest.
	 *
	 * @param manifestPath the manifest path
	 * @return the adjacent transition-ledger path
	 */
	private static Path expectedLedgerPath(Path manifestPath)
	{
		return manifestPath.resolveSibling(manifestPath.getFileName() + ".transitions.jsonl").toAbsolutePath().normalize();
	}

	/**
	 * Creates a strict mapper for persisted JSON boundaries.
	 *
	 * @param json the caller's JSON codec
	 * @return the strict derivative
	 */
	private static JsonMapper strict(JsonMapper json)
	{
		return json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
			DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
	}

	/**
	 * Parses one strict JSON object.
	 *
	 * @param json the strict JSON codec
	 * @param content the JSON text
	 * @param description the document description
	 * @param source the source path
	 * @return the parsed object
	 * @throws IOException if the text is malformed or is not an object
	 */
	private static ObjectNode parseObject(JsonMapper json, String content, String description, Path source)
		throws IOException
	{
		try
		{
			JsonNode parsed = json.readTree(content);
			if (parsed instanceof ObjectNode object)
				return object;
			throw new IOException(description + " is not a JSON object: " + source);
		}
		catch (JacksonException e)
		{
			throw new IOException(description + " contains invalid JSON: " + source, e);
		}
	}

	/**
	 * Reads required nonblank text.
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
			throw new IOException("Certification process has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Parses one canonical persisted instant.
	 *
	 * @param document the source object
	 * @param field the timestamp field
	 * @param source the source path
	 * @return the instant
	 * @throws IOException if the timestamp is invalid or noncanonical
	 */
	private static Instant instant(ObjectNode document, String field, Path source) throws IOException
	{
		String value = requiredText(document, field, source);
		try
		{
			Instant result = Instant.parse(value);
			if (!result.toString().equals(value))
				throw new IOException("Certification process has noncanonical " + field + ": " + source);
			return result;
		}
		catch (DateTimeException e)
		{
			throw new IOException("Certification process has invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Parses one closed operation identifier.
	 *
	 * @param value the persisted value
	 * @param source the source path
	 * @return the operation identifier
	 * @throws IOException if the value is unsupported
	 */
	private static OperationKind operation(String value, Path source) throws IOException
	{
		try
		{
			return OperationKind.fromExternalValue(value);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Unsupported optimization operation " + value + ": " + source, e);
		}
	}

	/**
	 * Parses one closed result identifier.
	 *
	 * @param value the persisted value
	 * @param source the source path
	 * @return the result identifier
	 * @throws IOException if the value is unsupported
	 */
	private static OperationResultKind result(String value, Path source) throws IOException
	{
		try
		{
			return OperationResultKind.fromExternalValue(value);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Unsupported optimization result " + value + ": " + source, e);
		}
	}

	/**
	 * Validates open text.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireText(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Validates a lowercase SHA-256 value.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireSha256(String value, String name)
	{
		requireText(value, name);
		if (!value.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
		return value;
	}

	/**
	 * Holds one hash-chained certification process transition.
	 *
	 * @param sequence the one-based transition sequence
	 * @param attempt the one-based operation attempt
	 * @param operation the completed operation
	 * @param result the terminal operation result
	 * @param successor the selected successor
	 * @param evidenceFingerprint the terminal evidence fingerprint
	 * @param completedAt the original completion time
	 * @param previousFingerprint the hash-chain predecessor
	 * @param entryFingerprint the entry fingerprint
	 */
	public record Entry(long sequence, int attempt, OperationKind operation,
		OperationResultKind result, Optional<OperationKind> successor,
		String evidenceFingerprint, Instant completedAt, String previousFingerprint, String entryFingerprint)
	{
		/**
		 * Validates one transition entry.
		 *
		 * @param sequence the one-based transition sequence
		 * @param attempt the one-based attempt number for this transition
		 * @param operation the completed operation
		 * @param result the terminal operation result
		 * @param successor the selected successor
		 * @param evidenceFingerprint the terminal evidence fingerprint
		 * @param completedAt the original completion time
		 * @param previousFingerprint the hash-chain predecessor
		 * @param entryFingerprint the entry fingerprint
		 */
		public Entry
		{
			if (sequence < 1)
				throw new IllegalArgumentException("sequence must be positive");
			if (attempt < 1)
				throw new IllegalArgumentException("attempt must be positive");
			Objects.requireNonNull(operation, "operation must not be null");
			Objects.requireNonNull(result, "result must not be null");
			Objects.requireNonNull(successor, "successor must not be null");
			evidenceFingerprint = requireSha256(evidenceFingerprint, "evidenceFingerprint");
			Objects.requireNonNull(completedAt, "completedAt must not be null");
			previousFingerprint = requireText(previousFingerprint, "previousFingerprint");
			entryFingerprint = requireSha256(entryFingerprint, "entryFingerprint");
		}
	}

	/**
	 * Holds one bounded replayed certification process state.
	 *
	 * @param certificationProcessIdentifier the certification process identifier
	 * @param contractFingerprint the immutable contract fingerprint
	 * @param environmentFingerprint the immutable environment fingerprint
	 * @param state the replayed state machine
	 * @param ledgerFingerprint the last entry fingerprint or root marker
	 */
	public record Snapshot(String certificationProcessIdentifier, String contractFingerprint,
		String environmentFingerprint,
		OptimizationStateMachine.State state, String ledgerFingerprint)
	{
		/**
		 * Validates one certification process snapshot.
		 *
		 * @param certificationProcessIdentifier the certification process identifier
		 * @param contractFingerprint the immutable contract fingerprint
		 * @param environmentFingerprint the immutable environment fingerprint
		 * @param state the replayed state machine
		 * @param ledgerFingerprint the last entry fingerprint or root marker
		 */
		public Snapshot
		{
			certificationProcessIdentifier = requireText(certificationProcessIdentifier,
				"certificationProcessIdentifier");
			contractFingerprint = requireSha256(contractFingerprint, "contractFingerprint");
			environmentFingerprint = requireSha256(environmentFingerprint, "environmentFingerprint");
			Objects.requireNonNull(state, "state must not be null");
			ledgerFingerprint = requireText(ledgerFingerprint, "ledgerFingerprint");
		}
	}

	/**
	 * Holds the fixed manifest values.
	 *
	 * @param certificationProcessIdentifier the certification process identifier
	 * @param contractFingerprint the immutable contract fingerprint
	 * @param environmentFingerprint the immutable environment fingerprint
	 * @param initialOperation the first operation in the process
	 * @param transitionLedger the append-only transition ledger
	 */
	private record Manifest(String certificationProcessIdentifier, String contractFingerprint,
		String environmentFingerprint, OperationKind initialOperation, Path transitionLedger)
	{
	}

	/**
	 * Holds one scan result and an optional retained retry entry.
	 *
	 * @param snapshot the replayed snapshot
	 * @param requestedEntry the retained requested transition
	 */
	private record Scan(Snapshot snapshot, Optional<Entry> requestedEntry)
	{
	}

	/**
	 * Streams transition validation while retaining only the replayed state and one requested entry.
	 */
	private static final class ScanState
	{
		private final JsonMapper json;
		private final Manifest manifest;
		private final long requestedSequence;
		private OptimizationStateMachine.State state;
		private String previousFingerprint = ROOT_FINGERPRINT;
		private Entry requestedEntry;

		/**
		 * Creates one scan state.
		 *
		 * @param json the JSON codec
		 * @param manifest the fixed manifest
		 * @param requestedSequence the transition sequence to retain
		 */
		private ScanState(JsonMapper json, Manifest manifest, long requestedSequence)
		{
			this.json = strict(json);
			this.manifest = manifest;
			this.requestedSequence = requestedSequence;
			state = OptimizationStateMachine.initial(manifest.initialOperation());
		}

		/**
		 * Validates and applies one transition line.
		 *
		 * @param line the JSON line
		 * @param lineNumber the one-based line number
		 * @throws IOException if the transition is malformed or invalid
		 */
		private void accept(String line, int lineNumber) throws IOException
		{
			ObjectNode document = parseObject(json, line, "Certification process transition",
				manifest.transitionLedger());
			if (!new HashSet<>(document.propertyNames()).equals(ENTRY_FIELDS))
				throw new IOException("Certification process transition has unsupported or missing fields at line " +
					lineNumber);
			JsonNode sequenceNode = document.path("sequence");
			if (!sequenceNode.isIntegralNumber() || !sequenceNode.canConvertToLong())
				throw new IOException("Certification process transition has invalid sequence at line " + lineNumber);
			long sequence = sequenceNode.asLong();
			if (sequence != state.sequence() + 1)
				throw new IOException("Certification process transition sequence is not contiguous at line " + lineNumber);
			JsonNode attemptNode = document.path("attempt");
			if (!attemptNode.isIntegralNumber() || !attemptNode.canConvertToInt() || attemptNode.asInt() < 1)
				throw new IOException("Certification process transition has invalid attempt at line " + lineNumber);
			int attempt = attemptNode.asInt();
			OperationKind parsedOperation = operation(
				requiredText(document, "operation", manifest.transitionLedger()), manifest.transitionLedger());
			if (state.terminal() || parsedOperation != state.currentOperation().orElseThrow())
				throw new IOException("Certification process transition names the wrong operation at line " + lineNumber);
			OperationResultKind parsedResult = CertificationProcessLedger.result(
				requiredText(document, "result", manifest.transitionLedger()), manifest.transitionLedger());
			Optional<OperationKind> successor = Optional.empty();
			JsonNode successorNode = document.path("successor");
			if (!successorNode.isNull())
			{
				if (!successorNode.isString())
					throw new IOException("Certification process transition has invalid successor at line " + lineNumber);
				successor = Optional.of(operation(successorNode.asString(), manifest.transitionLedger()));
			}
			String evidence = requireSha256(requiredText(document, "evidence_sha256", manifest.transitionLedger()),
				"evidenceFingerprint");
			Instant completedAt = instant(document, "completed_at", manifest.transitionLedger());
			String declaredPrevious = requiredText(document, "previous_sha256", manifest.transitionLedger());
			if (!declaredPrevious.equals(previousFingerprint))
				throw new IOException("Certification process transition breaks its hash chain at line " + lineNumber);
			Entry expected = createEntry(json, sequence, attempt, parsedOperation, parsedResult, successor, evidence,
				completedAt, previousFingerprint);
			String declaredFingerprint = requiredText(document, "entry_sha256", manifest.transitionLedger());
			if (!declaredFingerprint.equals(expected.entryFingerprint()))
				throw new IOException("Certification process transition fingerprint is invalid at line " + lineNumber);
			try
			{
				state = OptimizationStateMachine.advance(state, parsedResult, successor);
			}
			catch (IllegalArgumentException | IllegalStateException e)
			{
				throw new IOException("Certification process transition is not permitted at line " + lineNumber +
					": " + e.getMessage(), e);
			}
			previousFingerprint = expected.entryFingerprint();
			if (sequence == requestedSequence)
				requestedEntry = expected;
		}

		/**
		 * Returns the bounded scan result.
		 *
		 * @return the scan result
		 */
		private Scan result()
		{
			Snapshot snapshot = new Snapshot(manifest.certificationProcessIdentifier(), manifest.contractFingerprint(),
				manifest.environmentFingerprint(), state, previousFingerprint);
			return new Scan(snapshot, Optional.ofNullable(requestedEntry));
		}
	}

	/**
	 * Prevents construction of this static ledger owner.
	 */
	private CertificationProcessLedger()
	{
	}
}
