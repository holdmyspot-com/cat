package io.github.cowwoc.cat.common.optimization;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.RecordStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns the durable records for one optimization operation.
 */
public final class OperationDirectory
{
	private static final int SCHEMA_VERSION = 1;
	private static final int MAX_RECORD_BYTES = 64 * 1024;
	private static final List<String> MANIFEST_FIELDS = List.of("schema_version", "operation_id",
		"transition_sequence", "attempt", "operation_kind", "input_sha256", "contract_sha256",
		"environment_sha256", "state_path", "receipt_path", "transcript_path", "claim_path");
	private static final List<String> STATE_FIELDS = List.of("schema_version", "operation_id", "state",
		"input_sha256", "updated_at", "receipt_sha256");
	private static final List<String> RECEIPT_FIELDS = List.of("schema_version", "operation_id", "operation_kind",
		"transition_sequence", "attempt", "input_sha256", "contract_sha256", "environment_sha256", "state",
		"started_at", "completed_at");

	private final JsonMapper json;
	private final Clock clock;
	private final Path root;
	private final Path manifestPath;
	private final Path statePath;
	private final Path receiptPath;
	private final Path transcriptPath;
	private final Path claimPath;
	private final Manifest manifest;
	private StateSnapshot state;

	/**
	 * Creates an operation-directory view over validated records.
	 *
	 * @param json the JSON codec used to read and write records
	 * @param clock the clock used to timestamp transitions
	 * @param root the normalized absolute operation directory
	 * @param manifest the immutable operation manifest
	 * @param state the current durable state
	 */
	private OperationDirectory(JsonMapper json, Clock clock, Path root, Manifest manifest, StateSnapshot state)
	{
		this.json = json;
		this.clock = clock;
		this.root = root;
		manifestPath = root.resolve("manifest.json");
		statePath = root.resolve("state.json");
		receiptPath = root.resolve("receipt.json");
		transcriptPath = root.resolve("transcript.jsonl");
		claimPath = root.resolve("claim.json");
		this.manifest = manifest;
		this.state = state;
	}

	/**
	 * Resolves the canonical directory for one operation attempt.
	 *
	 * @param certificationProcessDirectory the certification-process directory that owns the operations directory
	 * @param transitionSequence the positive one-based transition sequence
	 * @param kind the operation kind
	 * @param attempt the positive one-based attempt number
	 * @return the normalized absolute operation directory
	 * @throws NullPointerException if {@code certificationProcessDirectory} or {@code kind} are null
	 * @throws IllegalArgumentException if {@code transitionSequence} or {@code attempt} are not positive
	 */
	public static Path path(Path certificationProcessDirectory, long transitionSequence, OperationKind kind, int attempt)
	{
		Objects.requireNonNull(certificationProcessDirectory,
			"certificationProcessDirectory must not be null");
		Objects.requireNonNull(kind, "kind must not be null");
		requirePositive(transitionSequence, "transitionSequence");
		requirePositive(attempt, "attempt");

		String identifier = operationId(transitionSequence, kind, attempt);
		return certificationProcessDirectory.toAbsolutePath().normalize().resolve("operations").resolve(identifier);
	}

	/**
	 * Creates the immutable manifest, pending state, and empty transcript for one operation.
	 *
	 * @param json the JSON codec used to publish records
	 * @param clock the clock used to timestamp the pending state
	 * @param context the operation paths and frozen contract identity
	 * @param transitionSequence the positive one-based transition sequence
	 * @param attempt the positive one-based attempt number
	 * @param input the complete typed operation input
	 * @return the newly created operation directory
	 * @throws NullPointerException if any required reference is null
	 * @throws IllegalArgumentException if the coordinates, path, kind, or input fingerprint is invalid
	 * @throws IOException if the directory already exists or its records cannot be published
	 */
	public static OperationDirectory create(JsonMapper json, Clock clock, OptimizationContext context,
		long transitionSequence, int attempt, OptimizationInput input) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(clock, "clock must not be null");
		Objects.requireNonNull(context, "context must not be null");
		Objects.requireNonNull(input, "input must not be null");
		requirePositive(transitionSequence, "transitionSequence");
		requirePositive(attempt, "attempt");

		Path root = path(context.certificationProcessDirectory(), transitionSequence, context.operationKind(), attempt);
		if (!root.equals(context.operationDirectory()))
		{
			throw new IllegalArgumentException("context operation directory does not match its operation identity: " +
				context.operationDirectory());
		}
		if (input.operationKind() != context.operationKind())
		{
			throw new IllegalArgumentException("input operation kind does not match the context operation kind");
		}
		requireSha256(input.inputFingerprint(), "inputFingerprint");
		if (Files.exists(root, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Operation directory already exists: " + root);

		Files.createDirectories(root.getParent());
		Files.createDirectory(root);
		String identifier = root.getFileName().toString();
		Manifest manifest = new Manifest(SCHEMA_VERSION, identifier, transitionSequence, attempt,
			context.operationKind(), input.inputFingerprint(), context.contractFingerprint(),
			context.environmentFingerprint(), "state.json", "receipt.json", "transcript.jsonl", "claim.json");
		StateSnapshot state = new StateSnapshot(SCHEMA_VERSION, identifier, OperationState.PENDING,
			input.inputFingerprint(), clock.instant(), Optional.empty());
		OperationDirectory result = new OperationDirectory(json, clock, root, manifest, state);

		RecordStore.write(json, result.manifestPath, result.manifestNode());
		RecordStore.write(json, result.statePath, result.stateNode(state));
		RecordStore.writeText(result.transcriptPath, "");
		return result;
	}

	/**
	 * Opens and validates an existing operation directory without following a directory symbolic link.
	 *
	 * @param json the JSON codec used to parse records
	 * @param clock the clock used for later state transitions
	 * @param operationDirectory the existing operation directory
	 * @return the validated operation directory
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the path or any required record is missing, unsafe, malformed, or inconsistent
	 */
	public static OperationDirectory open(JsonMapper json, Clock clock, Path operationDirectory) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(clock, "clock must not be null");
		Objects.requireNonNull(operationDirectory, "operationDirectory must not be null");

		Path root = operationDirectory.toAbsolutePath().normalize();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Expected regular operation directory: " + root);
		Path manifestPath = root.resolve("manifest.json");
		if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Expected regular operation manifest: " + manifestPath);
		if (!Files.isRegularFile(root.resolve("state.json"), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Expected regular operation state: " + root.resolve("state.json"));
		if (!Files.isRegularFile(root.resolve("transcript.jsonl"), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Expected regular operation transcript: " + root.resolve("transcript.jsonl"));

		Manifest manifest = parseManifest(json, manifestPath);
		StateSnapshot state = parseState(json, root.resolve("state.json"));
		if (!manifest.operationId().equals(root.getFileName().toString()))
			throw new IOException("Manifest operation identity does not match its directory: " + root);
		if (!manifest.operationId().equals(state.operationId()))
			throw new IOException("State operation identity does not match its manifest: " + root.resolve("state.json"));
		if (!manifest.inputFingerprint().equals(state.inputFingerprint()))
			throw new IOException("State input fingerprint does not match its manifest: " + root.resolve("state.json"));
		if (state.state() == OperationState.PENDING && Files.exists(root.resolve("receipt.json"),
			LinkOption.NOFOLLOW_LINKS))
		{
			throw new IOException("Pending operation contains a terminal receipt: " + root.resolve("receipt.json"));
		}

		OperationDirectory result = new OperationDirectory(json, clock, root, manifest, state);
		if (isTerminal(state.state()))
			result.verifiedTerminalReceipt();
		return result;
	}

	/**
	 * Returns the immutable operation manifest.
	 *
	 * @return the operation manifest
	 */
	public Manifest manifest()
	{
		return manifest;
	}

	/**
	 * Returns the state last read or written by this instance.
	 *
	 * @return the current state snapshot
	 */
	public StateSnapshot state()
	{
		return state;
	}

	/**
	 * Returns the normalized absolute operation directory.
	 *
	 * @return the operation directory
	 */
	public Path root()
	{
		return root;
	}

	/**
	 * Returns the immutable manifest path.
	 *
	 * @return the manifest path
	 */
	public Path manifestPath()
	{
		return manifestPath;
	}

	/**
	 * Returns the mutable state-document path.
	 *
	 * @return the state path
	 */
	public Path statePath()
	{
		return statePath;
	}

	/**
	 * Returns the terminal receipt path.
	 *
	 * @return the receipt path
	 */
	public Path receiptPath()
	{
		return receiptPath;
	}

	/**
	 * Returns the native-harness transcript path.
	 *
	 * @return the transcript path
	 */
	public Path transcriptPath()
	{
		return transcriptPath;
	}

	/**
	 * Returns the operation-claim path.
	 *
	 * @return the claim path
	 */
	public Path claimPath()
	{
		return claimPath;
	}

	/**
	 * Changes a pending operation to running after verifying its immutable identity.
	 *
	 * @param expectedInputFingerprint the lowercase SHA-256 fingerprint expected by the caller
	 * @throws IOException if the fingerprint is invalid or mismatched, the records changed, or the state is not pending
	 */
	public void start(String expectedInputFingerprint) throws IOException
	{
		if (!isSha256(expectedInputFingerprint))
			throw new IOException("Expected input fingerprint is not a lowercase SHA-256 value");

		RecordStore.withLock(statePath, () ->
		{
			reload();
			if (!state.inputFingerprint().equals(expectedInputFingerprint))
				throw new IOException("Expected input fingerprint does not match the operation manifest");
			if (state.state() != OperationState.PENDING)
				throw new IOException("Operation is not pending: " + state.state().externalValue());

			StateSnapshot running = new StateSnapshot(SCHEMA_VERSION, manifest.operationId(),
				OperationState.RUNNING, state.inputFingerprint(), clock.instant(), Optional.empty());
			RecordStore.write(json, statePath, stateNode(running));
			state = running;
		});
	}

	/**
	 * Publishes a completed receipt or returns the identical receipt retained by an earlier attempt.
	 *
	 * @param result the registered operation result and its retained evidence
	 * @return the immutable completed receipt
	 * @throws NullPointerException if {@code result} is null
	 * @throws IOException if the state, result, evidence, or retained receipt is invalid or conflicts with the request
	 */
	public Receipt complete(OptimizationResult result) throws IOException
	{
		Objects.requireNonNull(result, "result must not be null");
		Receipt[] answer = new Receipt[1];

		RecordStore.withLock(statePath, () ->
		{
			reload();
			if (isTerminal(state.state()))
			{
				Receipt retained = requireTerminalReceipt();
				requireSameResult(retained, result);
				answer[0] = retained;
				return;
			}
			if (state.state() != OperationState.RUNNING)
				throw new IOException("Operation is not running: " + state.state().externalValue());

			validateResult(result);
			if (Files.exists(receiptPath, LinkOption.NOFOLLOW_LINKS))
			{
				Receipt retained = parseReceipt(json, receiptPath, manifest);
				requireSameResult(retained, result);
				publishTerminalState(retained);
				answer[0] = retained;
				return;
			}

			Receipt receipt = Receipt.completed(manifest, state.updatedAt(), clock.instant(), result);
			publishNewReceipt(receipt);
			answer[0] = receipt;
		});
		return answer[0];
	}

	/**
	 * Publishes a failed receipt or returns the identical receipt retained by an earlier attempt.
	 *
	 * @param kind the stable failure category
	 * @param diagnostic the nonblank diagnostic that identifies the failed condition
	 * @param retryable whether the registered recovery boundary may retry this operation
	 * @return the immutable failed receipt
	 * @throws NullPointerException if {@code kind} or {@code diagnostic} are null
	 * @throws IllegalArgumentException if {@code diagnostic} is blank
	 * @throws IOException if the state or retained receipt is invalid or conflicts with the request
	 */
	public Receipt fail(FailureKind kind, String diagnostic, boolean retryable) throws IOException
	{
		Objects.requireNonNull(kind, "kind must not be null");
		Objects.requireNonNull(diagnostic, "diagnostic must not be null");
		if (diagnostic.isBlank())
			throw new IllegalArgumentException("diagnostic must not be blank");
		Receipt[] answer = new Receipt[1];

		RecordStore.withLock(statePath, () ->
		{
			reload();
			if (isTerminal(state.state()))
			{
				Receipt retained = requireTerminalReceipt();
				requireSameFailure(retained, kind, diagnostic, retryable);
				answer[0] = retained;
				return;
			}
			if (state.state() != OperationState.RUNNING)
				throw new IOException("Operation is not running: " + state.state().externalValue());

			if (Files.exists(receiptPath, LinkOption.NOFOLLOW_LINKS))
			{
				Receipt retained = parseReceipt(json, receiptPath, manifest);
				requireSameFailure(retained, kind, diagnostic, retryable);
				publishTerminalState(retained);
				answer[0] = retained;
				return;
			}

			Receipt receipt = Receipt.failed(manifest, state.updatedAt(), clock.instant(), kind, diagnostic, retryable);
			publishNewReceipt(receipt);
			answer[0] = receipt;
		});
		return answer[0];
	}

	/**
	 * Verifies and returns the receipt for a terminal state.
	 *
	 * @return the verified receipt, or empty when the current durable state is pending or running
	 * @throws IOException if the manifest, state, receipt bytes, or receipt binding is invalid
	 */
	public Optional<Receipt> verifiedTerminalReceipt() throws IOException
	{
		verifyManifest();
		state = parseState(json, statePath);
		verifyStateBinding(state);
		if (!isTerminal(state.state()))
			return Optional.empty();
		return Optional.of(requireTerminalReceipt());
	}

	/**
	 * Reloads the immutable manifest and current state and verifies their shared identity.
	 *
	 * @throws IOException if either record changed or is malformed
	 */
	private void reload() throws IOException
	{
		verifyManifest();
		state = parseState(json, statePath);
		verifyStateBinding(state);
	}

	/**
	 * Verifies that the persisted manifest still equals the manifest opened by this instance.
	 *
	 * @throws IOException if the manifest changed or is malformed
	 */
	private void verifyManifest() throws IOException
	{
		if (!manifest.equals(parseManifest(json, manifestPath)))
			throw new IOException("Operation manifest changed: " + manifestPath);
	}

	/**
	 * Verifies the state's operation and input identity against the immutable manifest.
	 *
	 * @param candidate the state to verify
	 * @throws IOException if the state does not belong to this manifest
	 */
	private void verifyStateBinding(StateSnapshot candidate) throws IOException
	{
		if (!candidate.operationId().equals(manifest.operationId()))
			throw new IOException("State operation identity does not match the manifest: " + statePath);
		if (!candidate.inputFingerprint().equals(manifest.inputFingerprint()))
			throw new IOException("State input fingerprint does not match the manifest: " + statePath);
	}

	/**
	 * Publishes one immutable receipt, then changes the state to its matching terminal value.
	 *
	 * @param receipt the terminal receipt to publish
	 * @throws IOException if a receipt already exists or either record cannot be written
	 */
	private void publishNewReceipt(Receipt receipt) throws IOException
	{
		if (Files.exists(receiptPath, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Operation receipt already exists: " + receiptPath);
		RecordStore.write(json, receiptPath, receipt.node(json));
		publishTerminalState(receipt);
	}

	/**
	 * Changes the durable state to match an already published and validated terminal receipt.
	 *
	 * @param receipt the retained terminal receipt
	 * @throws IOException if the state cannot be published
	 */
	private void publishTerminalState(Receipt receipt) throws IOException
	{
		StateSnapshot terminalState = new StateSnapshot(SCHEMA_VERSION, manifest.operationId(), receipt.state(),
			manifest.inputFingerprint(), receipt.completedAt(), Optional.of(Fingerprints.ofFile(receiptPath)));
		RecordStore.write(json, statePath, stateNode(terminalState));
		state = terminalState;
	}

	/**
	 * Verifies a completed result against the immutable operation and its retained evidence.
	 *
	 * @param result the proposed completed result
	 * @throws IOException if its identity, evidence, result, or successor is invalid
	 */
	private void validateResult(OptimizationResult result) throws IOException
	{
		if (result.operationKind() != manifest.operationKind())
			throw new IOException("Result operation kind does not match the manifest");
		Path evidencePath = result.evidencePath();
		if (!isCanonicalAbsolute(evidencePath) || !Files.isRegularFile(evidencePath, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Expected canonical regular evidence file: " + evidencePath);
		if (!Fingerprints.ofFile(evidencePath).equals(result.evidenceFingerprint()))
			throw new IOException("Evidence fingerprint does not match the published file: " + evidencePath);
		validateSuccessor(manifest.operationKind(), result.resultKind(), result.successorKind());
	}

	/**
	 * Returns the terminal receipt after checking its exact published bytes and state binding.
	 *
	 * @return the verified terminal receipt
	 * @throws IOException if the receipt is absent, changed, malformed, or inconsistent with state
	 */
	private Receipt requireTerminalReceipt() throws IOException
	{
		if (!Files.isRegularFile(receiptPath, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Expected regular terminal receipt: " + receiptPath);
		if (state.receiptFingerprint().isEmpty())
			throw new IOException("Terminal state is missing its receipt fingerprint: " + statePath);

		String actualFingerprint = Fingerprints.ofFile(receiptPath);
		if (!actualFingerprint.equals(state.receiptFingerprint().orElseThrow()))
			throw new IOException("Receipt fingerprint does not match the terminal state: " + receiptPath);
		Receipt receipt = parseReceipt(json, receiptPath, manifest);
		if (receipt.state() != state.state())
			throw new IOException("Receipt state does not match the terminal state: " + receiptPath);
		return receipt;
	}

	/**
	 * Requires a retained receipt to describe the requested completed result exactly.
	 *
	 * @param receipt the retained receipt
	 * @param result the requested completed result
	 * @throws IOException if any retained result value differs
	 */
	private static void requireSameResult(Receipt receipt, OptimizationResult result) throws IOException
	{
		if (receipt.state() != OperationState.COMPLETED || receipt.operationKind() != result.operationKind() ||
			receipt.resultKind().orElse(null) != result.resultKind() ||
			!receipt.evidencePath().equals(Optional.of(result.evidencePath())) ||
			!receipt.evidenceFingerprint().equals(Optional.of(result.evidenceFingerprint())) ||
			!receipt.successorKind().equals(result.successorKind()))
		{
			throw new IOException("Retained completed receipt conflicts with the requested result");
		}
	}

	/**
	 * Requires a retained receipt to describe the requested failure exactly.
	 *
	 * @param receipt the retained receipt
	 * @param kind the requested failure category
	 * @param diagnostic the requested diagnostic
	 * @param retryable the requested retry permission
	 * @throws IOException if any retained failure value differs
	 */
	private static void requireSameFailure(Receipt receipt, FailureKind kind, String diagnostic, boolean retryable)
		throws IOException
	{
		if (receipt.state() != OperationState.FAILED || receipt.failureKind().orElse(null) != kind ||
			!receipt.diagnostic().equals(Optional.of(diagnostic)) ||
			!receipt.retryable().equals(Optional.of(retryable)))
		{
			throw new IOException("Retained failed receipt conflicts with the requested failure");
		}
	}

	/**
	 * Returns the immutable manifest as an insertion-ordered JSON object.
	 *
	 * @return the manifest document
	 */
	private ObjectNode manifestNode()
	{
		ObjectNode result = json.createObjectNode();
		result.put("schema_version", manifest.schemaVersion());
		result.put("operation_id", manifest.operationId());
		result.put("transition_sequence", manifest.transitionSequence());
		result.put("attempt", manifest.attempt());
		result.put("operation_kind", manifest.operationKind().externalValue());
		result.put("input_sha256", manifest.inputFingerprint());
		result.put("contract_sha256", manifest.contractFingerprint());
		result.put("environment_sha256", manifest.environmentFingerprint());
		result.put("state_path", manifest.stateFile());
		result.put("receipt_path", manifest.receiptFile());
		result.put("transcript_path", manifest.transcriptFile());
		result.put("claim_path", manifest.claimFile());
		return result;
	}

	/**
	 * Returns one state snapshot as an insertion-ordered JSON object.
	 *
	 * @param snapshot the state to serialize
	 * @return the state document
	 */
	private ObjectNode stateNode(StateSnapshot snapshot)
	{
		ObjectNode result = json.createObjectNode();
		result.put("schema_version", snapshot.schemaVersion());
		result.put("operation_id", snapshot.operationId());
		result.put("state", snapshot.state().externalValue());
		result.put("input_sha256", snapshot.inputFingerprint());
		result.put("updated_at", snapshot.updatedAt().toString());
		if (snapshot.receiptFingerprint().isPresent())
			result.put("receipt_sha256", snapshot.receiptFingerprint().orElseThrow());
		else
			result.putNull("receipt_sha256");
		return result;
	}

	/**
	 * Parses an exact manifest document.
	 *
	 * @param json the JSON codec
	 * @param path the manifest path
	 * @return the validated manifest
	 * @throws IOException if the document is malformed or violates the manifest contract
	 */
	private static Manifest parseManifest(JsonMapper json, Path path) throws IOException
	{
		try
		{
			JsonNode document = readDocument(json, path);
			requireFields(document, MANIFEST_FIELDS, path);
			return new Manifest(integer(document, "schema_version", path), text(document, "operation_id", path),
				longValue(document, "transition_sequence", path), integer(document, "attempt", path),
				OperationKind.fromExternalValue(text(document, "operation_kind", path)),
				text(document, "input_sha256", path), text(document, "contract_sha256", path),
				text(document, "environment_sha256", path), text(document, "state_path", path),
				text(document, "receipt_path", path), text(document, "transcript_path", path),
				text(document, "claim_path", path));
		}
		catch (IOException e)
		{
			throw e;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Invalid operation manifest: " + path, e);
		}
	}

	/**
	 * Parses an exact state document.
	 *
	 * @param json the JSON codec
	 * @param path the state path
	 * @return the validated state snapshot
	 * @throws IOException if the document is malformed or violates the state contract
	 */
	private static StateSnapshot parseState(JsonMapper json, Path path) throws IOException
	{
		try
		{
			JsonNode document = readDocument(json, path);
			requireFields(document, STATE_FIELDS, path);
			JsonNode receiptFingerprint = document.get("receipt_sha256");
			if (!receiptFingerprint.isNull() && !receiptFingerprint.isString())
				throw new IOException("Expected null or text for receipt_sha256: " + path);
			Optional<String> receipt = Optional.empty();
			if (!receiptFingerprint.isNull())
				receipt = Optional.of(receiptFingerprint.stringValue());
			return new StateSnapshot(integer(document, "schema_version", path),
				text(document, "operation_id", path),
				OperationState.fromExternalValue(text(document, "state", path)),
				text(document, "input_sha256", path),
				canonicalInstant(text(document, "updated_at", path), path), receipt);
		}
		catch (IOException e)
		{
			throw e;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Invalid operation state: " + path, e);
		}
	}

	/**
	 * Parses an exact completed or failed receipt and verifies its immutable manifest binding.
	 *
	 * @param json the JSON codec
	 * @param path the receipt path
	 * @param manifest the immutable manifest that must own the receipt
	 * @return the validated receipt
	 * @throws IOException if the document is malformed, variant-incompatible, or bound to another operation
	 */
	private static Receipt parseReceipt(JsonMapper json, Path path, Manifest manifest) throws IOException
	{
		try
		{
			JsonNode document = readDocument(json, path);
			OperationState state = OperationState.fromExternalValue(text(document, "state", path));
			List<String> expectedFields = new ArrayList<>(RECEIPT_FIELDS);
			if (state == OperationState.COMPLETED)
			{
				expectedFields.addAll(List.of("result_kind", "evidence_path", "evidence_sha256",
					"successor_kind"));
			}
			else if (state == OperationState.FAILED)
				expectedFields.addAll(List.of("failure_kind", "diagnostic", "retryable"));
			else
				throw new IOException("Operation receipt is not terminal: " + path);
			requireFields(document, expectedFields, path);

			Optional<OperationResultKind> resultKind = Optional.empty();
			Optional<Path> evidencePath = Optional.empty();
			Optional<String> evidenceFingerprint = Optional.empty();
			Optional<OperationKind> successorKind = Optional.empty();
			Optional<FailureKind> failureKind = Optional.empty();
			Optional<String> diagnostic = Optional.empty();
			Optional<Boolean> retryable = Optional.empty();
			if (state == OperationState.COMPLETED)
			{
				resultKind = Optional.of(OperationResultKind.fromExternalValue(
					text(document, "result_kind", path)));
				evidencePath = Optional.of(canonicalPath(text(document, "evidence_path", path), path));
				evidenceFingerprint = Optional.of(text(document, "evidence_sha256", path));
				JsonNode successor = document.get("successor_kind");
				if (!successor.isNull())
				{
					successorKind = Optional.of(OperationKind.fromExternalValue(
						text(document, "successor_kind", path)));
				}
			}
			else
			{
				failureKind = Optional.of(FailureKind.fromExternalValue(text(document, "failure_kind", path)));
				diagnostic = Optional.of(text(document, "diagnostic", path));
				retryable = Optional.of(booleanValue(document, "retryable", path));
			}

			Receipt receipt = new Receipt(integer(document, "schema_version", path),
				text(document, "operation_id", path),
				OperationKind.fromExternalValue(text(document, "operation_kind", path)),
				longValue(document, "transition_sequence", path), integer(document, "attempt", path),
				text(document, "input_sha256", path), text(document, "contract_sha256", path),
				text(document, "environment_sha256", path), state,
				canonicalInstant(text(document, "started_at", path), path),
				canonicalInstant(text(document, "completed_at", path), path), resultKind, evidencePath,
				evidenceFingerprint, successorKind, failureKind, diagnostic, retryable);
			verifyReceiptBinding(receipt, manifest, path);
			return receipt;
		}
		catch (IOException e)
		{
			throw e;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Invalid operation receipt: " + path, e);
		}
	}

	/**
	 * Verifies that a receipt belongs to its immutable manifest.
	 *
	 * @param receipt the receipt to verify
	 * @param manifest the owning manifest
	 * @param path the receipt path used in diagnostics
	 * @throws IOException if any immutable identity value differs
	 */
	private static void verifyReceiptBinding(Receipt receipt, Manifest manifest, Path path) throws IOException
	{
		if (!receipt.operationId().equals(manifest.operationId()) ||
			receipt.operationKind() != manifest.operationKind() ||
			receipt.transitionSequence() != manifest.transitionSequence() ||
			receipt.attempt() != manifest.attempt() ||
			!receipt.inputFingerprint().equals(manifest.inputFingerprint()) ||
			!receipt.contractFingerprint().equals(manifest.contractFingerprint()) ||
			!receipt.environmentFingerprint().equals(manifest.environmentFingerprint()))
		{
			throw new IOException("Receipt identity does not match the operation manifest: " + path);
		}
	}

	/**
	 * Verifies a completed result's registered successor.
	 *
	 * @param operationKind the completed operation
	 * @param resultKind the registered result
	 * @param successorKind the proposed successor, empty only for a terminal result
	 * @throws IOException if the result or successor is not registered for the operation
	 */
	private static void validateSuccessor(OperationKind operationKind, OperationResultKind resultKind,
		Optional<OperationKind> successorKind) throws IOException
	{
		try
		{
			requireRegisteredSuccessor(operationKind, resultKind, successorKind);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Invalid result or successor for operation " + operationKind.externalValue(), e);
		}
	}

	/**
	 * Requires a completed result to select exactly one registered successor or a terminal transition.
	 *
	 * @param operationKind the completed operation
	 * @param resultKind the registered result
	 * @param successorKind the proposed successor
	 * @throws IllegalArgumentException if the result or successor is not registered
	 */
	private static void requireRegisteredSuccessor(OperationKind operationKind, OperationResultKind resultKind,
		Optional<OperationKind> successorKind)
	{
		Set<OperationKind> allowed = OptimizationOperationRegistry.successors(operationKind, resultKind);
		if (allowed.isEmpty())
		{
			if (successorKind.isPresent())
				throw new IllegalArgumentException("Terminal result must not name a successor");
			return;
		}
		if (successorKind.isEmpty() || !allowed.contains(successorKind.orElseThrow()))
			throw new IllegalArgumentException("Result does not name a registered successor");
	}

	/**
	 * Reads a bounded strict JSON document.
	 *
	 * @param json the base JSON codec
	 * @param path the document path
	 * @return the parsed document
	 * @throws IOException if the file is missing, oversized, duplicated, malformed, or contains trailing content
	 */
	private static JsonNode readDocument(JsonMapper json, Path path) throws IOException
	{
		JsonMapper strict = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
			DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
		return strict.readTree(BoundedInput.readUtf8(path, MAX_RECORD_BYTES, "operation record"));
	}

	/**
	 * Requires an object to contain exactly the named fields in insertion order.
	 *
	 * @param document the parsed JSON document
	 * @param expectedFields the complete ordered field names
	 * @param path the document path used in diagnostics
	 * @throws IOException if the document shape or field sequence differs
	 */
	private static void requireFields(JsonNode document, List<String> expectedFields, Path path) throws IOException
	{
		if (!document.isObject())
			throw new IOException("Expected JSON object: " + path);
		List<String> actualFields = new ArrayList<>();
		document.properties().forEach(entry -> actualFields.add(entry.getKey()));
		if (!actualFields.equals(expectedFields))
		{
			throw new IOException("Expected fields " + expectedFields + " but found " + actualFields + ": " +
				path);
		}
	}

	/**
	 * Reads one required textual field.
	 *
	 * @param document the owning object
	 * @param name the field name
	 * @param path the document path used in diagnostics
	 * @return the text value
	 * @throws IOException if the field is absent or not textual
	 */
	private static String text(JsonNode document, String name, Path path) throws IOException
	{
		JsonNode value = document.get(name);
		if (value == null || !value.isString())
			throw new IOException("Expected text field " + name + ": " + path);
		return value.stringValue();
	}

	/**
	 * Reads one required 32-bit integer field.
	 *
	 * @param document the owning object
	 * @param name the field name
	 * @param path the document path used in diagnostics
	 * @return the integer value
	 * @throws IOException if the field is absent, not integral, or outside the 32-bit range
	 */
	private static int integer(JsonNode document, String name, Path path) throws IOException
	{
		JsonNode value = document.get(name);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
			throw new IOException("Expected 32-bit integer field " + name + ": " + path);
		return value.intValue();
	}

	/**
	 * Reads one required 64-bit integer field.
	 *
	 * @param document the owning object
	 * @param name the field name
	 * @param path the document path used in diagnostics
	 * @return the integer value
	 * @throws IOException if the field is absent or not integral
	 */
	private static long longValue(JsonNode document, String name, Path path) throws IOException
	{
		JsonNode value = document.get(name);
		if (value == null || !value.isIntegralNumber())
			throw new IOException("Expected integer field " + name + ": " + path);
		return value.longValue();
	}

	/**
	 * Reads one required boolean field.
	 *
	 * @param document the owning object
	 * @param name the field name
	 * @param path the document path used in diagnostics
	 * @return the boolean value
	 * @throws IOException if the field is absent or not boolean
	 */
	private static boolean booleanValue(JsonNode document, String name, Path path) throws IOException
	{
		JsonNode value = document.get(name);
		if (value == null || !value.isBoolean())
			throw new IOException("Expected boolean field " + name + ": " + path);
		return value.booleanValue();
	}

	/**
	 * Parses an instant and requires its canonical UTC spelling.
	 *
	 * @param value the persisted instant
	 * @param path the document path used in diagnostics
	 * @return the parsed instant
	 * @throws IOException if the value is invalid or not canonical
	 */
	private static Instant canonicalInstant(String value, Path path) throws IOException
	{
		try
		{
			Instant result = Instant.parse(value);
			if (!result.toString().equals(value))
				throw new IOException("Expected canonical UTC instant: " + path);
			return result;
		}
		catch (DateTimeException e)
		{
			throw new IOException("Invalid instant: " + path, e);
		}
	}

	/**
	 * Parses a canonical absolute evidence path.
	 *
	 * @param value the persisted path text
	 * @param documentPath the document path used in diagnostics
	 * @return the canonical absolute path
	 * @throws IOException if the value is not a valid canonical absolute path
	 */
	private static Path canonicalPath(String value, Path documentPath) throws IOException
	{
		try
		{
			Path result = Path.of(value);
			if (!isCanonicalAbsolute(result))
				throw new IOException("Expected canonical absolute evidence path: " + documentPath);
			return result;
		}
		catch (InvalidPathException e)
		{
			throw new IOException("Invalid evidence path: " + documentPath, e);
		}
	}

	/**
	 * Indicates whether a path is absolute and already normalized.
	 *
	 * @param value the path to inspect
	 * @return {@code true} if the path is a canonical absolute path
	 */
	private static boolean isCanonicalAbsolute(Path value)
	{
		return value.isAbsolute() && value.equals(value.toAbsolutePath().normalize());
	}

	/**
	 * Indicates whether a value is a lowercase SHA-256 fingerprint.
	 *
	 * @param value the value to inspect
	 * @return {@code true} if the value contains exactly 64 lowercase hexadecimal digits
	 */
	private static boolean isSha256(String value)
	{
		return value != null && value.matches("[0-9a-f]{64}");
	}

	/**
	 * Requires a lowercase SHA-256 fingerprint.
	 *
	 * @param value the value to validate
	 * @param name the input name used in diagnostics
	 * @return the validated value
	 * @throws NullPointerException if {@code value} or {@code name} are null
	 * @throws IllegalArgumentException if {@code value} is not a lowercase SHA-256 fingerprint
	 */
	private static String requireSha256(String value, String name)
	{
		Objects.requireNonNull(name, "name must not be null");
		Objects.requireNonNull(value, name + " must not be null");
		if (!isSha256(value))
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 fingerprint");
		return value;
	}

	/**
	 * Indicates whether an operation state owns a terminal receipt.
	 *
	 * @param state the state to inspect
	 * @return {@code true} for completed or failed
	 */
	private static boolean isTerminal(OperationState state)
	{
		return state == OperationState.COMPLETED || state == OperationState.FAILED;
	}

	/**
	 * Requires a positive operation coordinate.
	 *
	 * @param value the coordinate to validate
	 * @param name the input name used in diagnostics
	 * @throws IllegalArgumentException if {@code value} is not positive
	 */
	private static void requirePositive(long value, String name)
	{
		if (value <= 0)
			throw new IllegalArgumentException(name + " must be positive");
	}

	/**
	 * Formats the stable operation identity.
	 *
	 * @param transitionSequence the positive one-based transition sequence
	 * @param kind the operation kind
	 * @param attempt the positive one-based attempt number
	 * @return the stable operation identifier
	 */
	private static String operationId(long transitionSequence, OperationKind kind, int attempt)
	{
		return "%08d-%s-a%03d".formatted(transitionSequence, kind.externalValue(), attempt);
	}

	/**
	 * Immutable operation identity and record-path contract.
	 *
	 * @param schemaVersion the exact operation-directory schema version
	 * @param operationId the formatted operation identity
	 * @param transitionSequence the positive one-based transition sequence
	 * @param attempt the positive one-based attempt number
	 * @param operationKind the operation kind
	 * @param inputFingerprint the lowercase SHA-256 fingerprint of the canonical input
	 * @param contractFingerprint the lowercase SHA-256 fingerprint of the frozen contract
	 * @param environmentFingerprint the lowercase SHA-256 fingerprint of the frozen environment
	 * @param stateFile the fixed relative state filename
	 * @param receiptFile the fixed relative receipt filename
	 * @param transcriptFile the fixed relative transcript filename
	 * @param claimFile the fixed relative claim filename
	 */
	public record Manifest(int schemaVersion, String operationId, long transitionSequence, int attempt,
		OperationKind operationKind, String inputFingerprint, String contractFingerprint,
		String environmentFingerprint, String stateFile, String receiptFile, String transcriptFile, String claimFile)
	{
		/**
		 * Validates the complete immutable operation identity.
		 *
		 * @param schemaVersion the exact operation-directory schema version
		 * @param operationId the formatted operation identity
		 * @param transitionSequence the positive one-based transition sequence
		 * @param attempt the positive one-based attempt number
		 * @param operationKind the operation kind
		 * @param inputFingerprint the lowercase SHA-256 fingerprint of the canonical input
		 * @param contractFingerprint the lowercase SHA-256 fingerprint of the frozen contract
		 * @param environmentFingerprint the lowercase SHA-256 fingerprint of the frozen environment
		 * @param stateFile the fixed relative state filename
		 * @param receiptFile the fixed relative receipt filename
		 * @param transcriptFile the fixed relative transcript filename
		 * @param claimFile the fixed relative claim filename
		 */
		public Manifest
		{
			Objects.requireNonNull(operationId, "operationId must not be null");
			Objects.requireNonNull(operationKind, "operationKind must not be null");
			Objects.requireNonNull(stateFile, "stateFile must not be null");
			Objects.requireNonNull(receiptFile, "receiptFile must not be null");
			Objects.requireNonNull(transcriptFile, "transcriptFile must not be null");
			Objects.requireNonNull(claimFile, "claimFile must not be null");
			if (schemaVersion != SCHEMA_VERSION)
				throw new IllegalArgumentException("Unsupported operation manifest schema: " + schemaVersion);
			requirePositive(transitionSequence, "transitionSequence");
			requirePositive(attempt, "attempt");
			if (!operationId.equals(OperationDirectory.operationId(transitionSequence, operationKind, attempt)))
				throw new IllegalArgumentException("operationId does not match its operation coordinates");
			requireSha256(inputFingerprint, "inputFingerprint");
			requireSha256(contractFingerprint, "contractFingerprint");
			requireSha256(environmentFingerprint, "environmentFingerprint");
			if (!stateFile.equals("state.json") || !receiptFile.equals("receipt.json") ||
				!transcriptFile.equals("transcript.jsonl") || !claimFile.equals("claim.json"))
			{
				throw new IllegalArgumentException("Operation manifest contains an unsupported record path");
			}
		}
	}

	/**
	 * Immutable durable operation state.
	 *
	 * @param schemaVersion the exact operation-directory schema version
	 * @param operationId the operation identity
	 * @param state the lifecycle state
	 * @param inputFingerprint the lowercase SHA-256 fingerprint of the canonical input
	 * @param updatedAt the canonical transition time
	 * @param receiptFingerprint the terminal receipt fingerprint, empty only before termination
	 */
	public record StateSnapshot(int schemaVersion, String operationId, OperationState state, String inputFingerprint,
		Instant updatedAt, Optional<String> receiptFingerprint)
	{
		/**
		 * Validates the lifecycle state and receipt-fingerprint variant.
		 *
		 * @param schemaVersion the exact operation-directory schema version
		 * @param operationId the operation identity
		 * @param state the lifecycle state
		 * @param inputFingerprint the lowercase SHA-256 fingerprint of the canonical input
		 * @param updatedAt the canonical transition time
		 * @param receiptFingerprint the terminal receipt fingerprint, empty only before termination
		 */
		public StateSnapshot
		{
			Objects.requireNonNull(operationId, "operationId must not be null");
			Objects.requireNonNull(state, "state must not be null");
			Objects.requireNonNull(updatedAt, "updatedAt must not be null");
			Objects.requireNonNull(receiptFingerprint, "receiptFingerprint must not be null");
			if (schemaVersion != SCHEMA_VERSION)
				throw new IllegalArgumentException("Unsupported operation state schema: " + schemaVersion);
			if (operationId.isBlank())
				throw new IllegalArgumentException("operationId must not be blank");
			requireSha256(inputFingerprint, "inputFingerprint");
			if (isTerminal(state) != receiptFingerprint.isPresent())
				throw new IllegalArgumentException("receiptFingerprint must be present only for terminal state");
			if (receiptFingerprint.isPresent())
				requireSha256(receiptFingerprint.orElseThrow(), "receiptFingerprint");
		}
	}

	/**
	 * Immutable completed or failed operation receipt.
	 *
	 * @param schemaVersion the exact operation-directory schema version
	 * @param operationId the formatted operation identity
	 * @param operationKind the operation kind
	 * @param transitionSequence the positive one-based transition sequence
	 * @param attempt the positive one-based attempt number
	 * @param inputFingerprint the lowercase SHA-256 fingerprint of the canonical input
	 * @param contractFingerprint the lowercase SHA-256 fingerprint of the frozen contract
	 * @param environmentFingerprint the lowercase SHA-256 fingerprint of the frozen environment
	 * @param state the completed or failed state
	 * @param startedAt the running-state timestamp
	 * @param completedAt the terminal timestamp, not earlier than {@code startedAt}
	 * @param resultKind the registered completed result, absent for failure
	 * @param evidencePath the canonical absolute completed-evidence path, absent for failure
	 * @param evidenceFingerprint the completed-evidence fingerprint, absent for failure
	 * @param successorKind the registered successor, empty for failure or a terminal completed result
	 * @param failureKind the failure category, present only for failure
	 * @param diagnostic the nonblank diagnostic, present only for failure
	 * @param retryable the retry permission, present only for failure
	 */
	public record Receipt(int schemaVersion, String operationId, OperationKind operationKind,
		long transitionSequence, int attempt, String inputFingerprint, String contractFingerprint,
		String environmentFingerprint, OperationState state, Instant startedAt, Instant completedAt,
		Optional<OperationResultKind> resultKind, Optional<Path> evidencePath,
		Optional<String> evidenceFingerprint, Optional<OperationKind> successorKind,
		Optional<FailureKind> failureKind, Optional<String> diagnostic, Optional<Boolean> retryable)
	{
		/**
		 * Validates the immutable identity and completed-or-failed variant.
		 *
		 * @param schemaVersion the exact operation-directory schema version
		 * @param operationId the formatted operation identity
		 * @param operationKind the operation kind
		 * @param transitionSequence the positive one-based transition sequence
		 * @param attempt the positive one-based attempt number
		 * @param inputFingerprint the lowercase SHA-256 fingerprint of the canonical input
		 * @param contractFingerprint the lowercase SHA-256 fingerprint of the frozen contract
		 * @param environmentFingerprint the lowercase SHA-256 fingerprint of the frozen environment
		 * @param state the completed or failed state
		 * @param startedAt the running-state timestamp
		 * @param completedAt the terminal timestamp, not earlier than {@code startedAt}
		 * @param resultKind the registered completed result, absent for failure
		 * @param evidencePath the canonical absolute completed-evidence path, absent for failure
		 * @param evidenceFingerprint the completed-evidence fingerprint, absent for failure
		 * @param successorKind the registered successor, empty for failure or a terminal completed result
		 * @param failureKind the failure category, present only for failure
		 * @param diagnostic the nonblank diagnostic, present only for failure
		 * @param retryable the retry permission, present only for failure
		 */
		public Receipt
		{
			Objects.requireNonNull(operationId, "operationId must not be null");
			Objects.requireNonNull(operationKind, "operationKind must not be null");
			Objects.requireNonNull(state, "state must not be null");
			Objects.requireNonNull(startedAt, "startedAt must not be null");
			Objects.requireNonNull(completedAt, "completedAt must not be null");
			Objects.requireNonNull(resultKind, "resultKind must not be null");
			Objects.requireNonNull(evidencePath, "evidencePath must not be null");
			Objects.requireNonNull(evidenceFingerprint, "evidenceFingerprint must not be null");
			Objects.requireNonNull(successorKind, "successorKind must not be null");
			Objects.requireNonNull(failureKind, "failureKind must not be null");
			Objects.requireNonNull(diagnostic, "diagnostic must not be null");
			Objects.requireNonNull(retryable, "retryable must not be null");
			if (schemaVersion != SCHEMA_VERSION)
				throw new IllegalArgumentException("Unsupported operation receipt schema: " + schemaVersion);
			requirePositive(transitionSequence, "transitionSequence");
			requirePositive(attempt, "attempt");
			if (!operationId.equals(OperationDirectory.operationId(transitionSequence, operationKind, attempt)))
				throw new IllegalArgumentException("operationId does not match its operation coordinates");
			requireSha256(inputFingerprint, "inputFingerprint");
			requireSha256(contractFingerprint, "contractFingerprint");
			requireSha256(environmentFingerprint, "environmentFingerprint");
			if (completedAt.isBefore(startedAt))
				throw new IllegalArgumentException("completedAt must not be before startedAt");

			if (state == OperationState.COMPLETED)
			{
				requireCompletedReceipt(resultKind, evidencePath, evidenceFingerprint, successorKind,
					failureKind, diagnostic, retryable, operationKind);
			}
			else if (state == OperationState.FAILED)
			{
				requireFailedReceipt(resultKind, evidencePath, evidenceFingerprint, successorKind,
					failureKind, diagnostic, retryable);
			}
			else
				throw new IllegalArgumentException("Operation receipt state must be completed or failed");
		}

		/**
		 * Creates a completed receipt from a validated operation result.
		 *
		 * @param manifest the owning operation manifest
		 * @param startedAt the running-state timestamp
		 * @param completedAt the terminal timestamp
		 * @param result the validated completed result
		 * @return the completed receipt
		 */
		private static Receipt completed(Manifest manifest, Instant startedAt, Instant completedAt,
			OptimizationResult result)
		{
			return new Receipt(SCHEMA_VERSION, manifest.operationId(), manifest.operationKind(),
				manifest.transitionSequence(), manifest.attempt(), manifest.inputFingerprint(),
				manifest.contractFingerprint(), manifest.environmentFingerprint(), OperationState.COMPLETED,
				startedAt, completedAt, Optional.of(result.resultKind()), Optional.of(result.evidencePath()),
				Optional.of(result.evidenceFingerprint()), result.successorKind(), Optional.empty(), Optional.empty(),
				Optional.empty());
		}

		/**
		 * Creates a failed receipt from a validated diagnostic.
		 *
		 * @param manifest the owning operation manifest
		 * @param startedAt the running-state timestamp
		 * @param completedAt the terminal timestamp
		 * @param kind the failure category
		 * @param diagnostic the nonblank failure diagnostic
		 * @param retryable whether the operation may be retried
		 * @return the failed receipt
		 */
		private static Receipt failed(Manifest manifest, Instant startedAt, Instant completedAt, FailureKind kind,
			String diagnostic, boolean retryable)
		{
			return new Receipt(SCHEMA_VERSION, manifest.operationId(), manifest.operationKind(),
				manifest.transitionSequence(), manifest.attempt(), manifest.inputFingerprint(),
				manifest.contractFingerprint(), manifest.environmentFingerprint(), OperationState.FAILED,
				startedAt, completedAt, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.of(kind), Optional.of(diagnostic), Optional.of(retryable));
		}

		/**
		 * Returns this receipt as an insertion-ordered JSON object.
		 *
		 * @param json the JSON codec that creates the object
		 * @return the exact receipt document
		 */
		private ObjectNode node(JsonMapper json)
		{
			ObjectNode result = json.createObjectNode();
			result.put("schema_version", schemaVersion);
			result.put("operation_id", operationId);
			result.put("operation_kind", operationKind.externalValue());
			result.put("transition_sequence", transitionSequence);
			result.put("attempt", attempt);
			result.put("input_sha256", inputFingerprint);
			result.put("contract_sha256", contractFingerprint);
			result.put("environment_sha256", environmentFingerprint);
			result.put("state", state.externalValue());
			result.put("started_at", startedAt.toString());
			result.put("completed_at", completedAt.toString());
			if (state == OperationState.COMPLETED)
			{
				result.put("result_kind", resultKind.orElseThrow().externalValue());
				result.put("evidence_path", evidencePath.orElseThrow().toString());
				result.put("evidence_sha256", evidenceFingerprint.orElseThrow());
				if (successorKind.isPresent())
					result.put("successor_kind", successorKind.orElseThrow().externalValue());
				else
					result.putNull("successor_kind");
			}
			else
			{
				result.put("failure_kind", failureKind.orElseThrow().externalValue());
				result.put("diagnostic", diagnostic.orElseThrow());
				result.put("retryable", retryable.orElseThrow());
			}
			return result;
		}

		/**
		 * Validates a completed receipt variant.
		 *
		 * @param resultKind the required result kind
		 * @param evidencePath the required canonical evidence path
		 * @param evidenceFingerprint the required evidence fingerprint
		 * @param successorKind the registered successor or empty terminal value
		 * @param failureKind the forbidden failure kind
		 * @param diagnostic the forbidden diagnostic
		 * @param retryable the forbidden retry permission
		 * @param operationKind the completed operation used to validate the successor
		 */
		private static void requireCompletedReceipt(Optional<OperationResultKind> resultKind,
			Optional<Path> evidencePath, Optional<String> evidenceFingerprint,
			Optional<OperationKind> successorKind, Optional<FailureKind> failureKind,
			Optional<String> diagnostic, Optional<Boolean> retryable, OperationKind operationKind)
		{
			if (resultKind.isEmpty() || evidencePath.isEmpty() || evidenceFingerprint.isEmpty())
				throw new IllegalArgumentException("Completed receipt requires result and evidence");
			if (!isCanonicalAbsolute(evidencePath.orElseThrow()))
				throw new IllegalArgumentException("Completed receipt evidence path must be canonical and absolute");
			requireSha256(evidenceFingerprint.orElseThrow(), "evidenceFingerprint");
			if (failureKind.isPresent() || diagnostic.isPresent() || retryable.isPresent())
				throw new IllegalArgumentException("Completed receipt must not contain failure fields");
			requireRegisteredSuccessor(operationKind, resultKind.orElseThrow(), successorKind);
		}

		/**
		 * Validates a failed receipt variant.
		 *
		 * @param resultKind the forbidden completed result
		 * @param evidencePath the forbidden completed-evidence path
		 * @param evidenceFingerprint the forbidden completed-evidence fingerprint
		 * @param successorKind the forbidden successor
		 * @param failureKind the required failure kind
		 * @param diagnostic the required nonblank diagnostic
		 * @param retryable the required retry permission
		 */
		private static void requireFailedReceipt(Optional<OperationResultKind> resultKind,
			Optional<Path> evidencePath, Optional<String> evidenceFingerprint,
			Optional<OperationKind> successorKind, Optional<FailureKind> failureKind,
			Optional<String> diagnostic, Optional<Boolean> retryable)
		{
			if (resultKind.isPresent() || evidencePath.isPresent() || evidenceFingerprint.isPresent() ||
				successorKind.isPresent())
			{
				throw new IllegalArgumentException("Failed receipt must not contain completed-result fields");
			}
			if (failureKind.isEmpty() || diagnostic.isEmpty() || retryable.isEmpty())
				throw new IllegalArgumentException("Failed receipt requires failure details");
			if (diagnostic.orElseThrow().isBlank())
				throw new IllegalArgumentException("Failed receipt diagnostic must not be blank");
		}
	}

	/**
	 * Stable categories for failures that occur before a registered domain result is retained.
	 */
	public enum FailureKind
	{
		INVALID_INPUT("invalid_input"),
		PROTOCOL_FAILURE("protocol_failure"),
		PERSISTENCE_FAILURE("persistence_failure"),
		INFRASTRUCTURE_FAILURE("infrastructure_failure");

		private final String externalValue;

		/**
		 * Creates one stable failure category.
		 *
		 * @param externalValue the exact persisted lowercase value
		 */
		FailureKind(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Returns the exact persisted lowercase value.
		 *
		 * @return the persisted value
		 */
		public String externalValue()
		{
			return externalValue;
		}

		/**
		 * Parses one exact persisted lowercase value.
		 *
		 * @param value the persisted value
		 * @return the matching failure category
		 * @throws NullPointerException if {@code value} is null
		 * @throws IllegalArgumentException if the value is unsupported
		 */
		public static FailureKind fromExternalValue(String value)
		{
			Objects.requireNonNull(value, "value must not be null");
			for (FailureKind kind : values())
			{
				if (kind.externalValue.equals(value))
					return kind;
			}
			throw new IllegalArgumentException("Unsupported failure kind: " + value);
		}
	}
}
