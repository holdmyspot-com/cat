package io.github.cowwoc.cat.common.optimization;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.RecordStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link OperationDirectory} durable identity, lifecycle, and record validation.
 */
public final class OperationDirectoryTest
{
	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
	private static final String SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
	private static final JsonMapper JSON = new JsonMapper();
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);

	/**
	 * Creates the test fixture.
	 */
	public OperationDirectoryTest()
	{
	}

	/**
	 * Resolves the exact normalized operation path.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void resolvesOperationPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path process = cleanup.add(Files.createTempDirectory("operation-path-"));
			Path workingDirectory = Path.of("").toAbsolutePath().normalize();
			Path relative = workingDirectory.relativize(process);
			Path expected = process.resolve("operations/00000007-discover-prompt-system-a003");

			requireThat(OperationDirectory.path(relative, 7, OperationKind.DISCOVER_PROMPT_SYSTEM, 3), "path").
				isEqualTo(expected);
		}
	}

	/**
	 * Rejects null path inputs and nonpositive path coordinates.
	 */
	@Test
	public void rejectsInvalidOperationPath()
	{
		requireRuntime(NullPointerException.class,
			() -> OperationDirectory.path(null, 1, OperationKind.DISCOVER_PROMPT_SYSTEM, 1));
		requireRuntime(NullPointerException.class,
			() -> OperationDirectory.path(Path.of("process"), 1, null, 1));
		requireRuntime(IllegalArgumentException.class,
			() -> OperationDirectory.path(Path.of("process"), 0, OperationKind.DISCOVER_PROMPT_SYSTEM, 1));
		requireRuntime(IllegalArgumentException.class,
			() -> OperationDirectory.path(Path.of("process"), 1, OperationKind.DISCOVER_PROMPT_SYSTEM, 0));
	}

	/**
	 * Creates the exact immutable manifest, pending state, and empty transcript.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void createsExactPendingDocuments() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = create(cleanup, 7, 3, OperationKind.DISCOVER_PROMPT_SYSTEM);
			OperationDirectory directory = fixture.directory();
			JsonNode manifest = JSON.readTree(directory.manifestPath().toFile());
			JsonNode state = JSON.readTree(directory.statePath().toFile());

			requireThat(fieldNames(manifest), "manifestFields").containsExactly(List.of("schema_version",
				"operation_id", "transition_sequence", "attempt", "operation_kind", "input_sha256",
				"contract_sha256", "environment_sha256", "state_path", "receipt_path", "transcript_path",
				"claim_path"));
			requireThat(manifest.get("operation_id").stringValue(), "operationId").
				isEqualTo("00000007-discover-prompt-system-a003");
			requireThat(manifest.get("transition_sequence").longValue(), "transitionSequence").isEqualTo(7L);
			requireThat(manifest.get("attempt").intValue(), "attempt").isEqualTo(3);
			requireThat(manifest.get("state_path").stringValue(), "statePath").isEqualTo("state.json");
			requireThat(manifest.get("receipt_path").stringValue(), "receiptPath").isEqualTo("receipt.json");
			requireThat(manifest.get("transcript_path").stringValue(), "transcriptPath").
				isEqualTo("transcript.jsonl");
			requireThat(manifest.get("claim_path").stringValue(), "claimPath").isEqualTo("claim.json");
			requireThat(fieldNames(state), "stateFields").containsExactly(List.of("schema_version", "operation_id",
				"state", "input_sha256", "updated_at", "receipt_sha256"));
			requireThat(state.get("state").stringValue(), "state").isEqualTo("pending");
			requireThat(state.get("updated_at").stringValue(), "updatedAt").isEqualTo("2026-09-01T12:00:00Z");
			requireThat(state.get("receipt_sha256").isNull(), "receiptFingerprint").isTrue();
			requireThat(Files.readString(directory.transcriptPath()), "transcript").isEmpty();
			requireThat(Files.notExists(directory.receiptPath()), "receiptAbsent").isTrue();
			requireThat(Files.notExists(directory.claimPath()), "claimAbsent").isTrue();
		}
	}

	/**
	 * Rejects invalid creation identities before replacing existing operation data.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsInvalidCreation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path process = cleanup.add(Files.createTempDirectory("operation-create-invalid-"));
			Path expected = OperationDirectory.path(process, 1, OperationKind.DISCOVER_PROMPT_SYSTEM, 1);
			OptimizationContext wrongPath = context(process, process.resolve("operations/wrong"),
				OperationKind.DISCOVER_PROMPT_SYSTEM);
			OptimizationContext correct = context(process, expected, OperationKind.DISCOVER_PROMPT_SYSTEM);

			requireRuntime(IllegalArgumentException.class, () -> OperationDirectory.create(JSON, CLOCK, wrongPath, 1, 1,
				input(OperationKind.DISCOVER_PROMPT_SYSTEM)));
			requireRuntime(IllegalArgumentException.class, () -> OperationDirectory.create(JSON, CLOCK, correct, 1, 1,
				input(OperationKind.ANALYZE_PROMPT_SYSTEM)));
			OperationDirectory.create(JSON, CLOCK, correct, 1, 1, input(OperationKind.DISCOVER_PROMPT_SYSTEM));
			String manifest = Files.readString(expected.resolve("manifest.json"));
			requireIo(() -> OperationDirectory.create(JSON, CLOCK, correct, 1, 1,
				input(OperationKind.DISCOVER_PROMPT_SYSTEM)));
			requireThat(Files.readString(expected.resolve("manifest.json")), "retainedManifest").isEqualTo(manifest);
		}
	}

	/**
	 * Rejects a missing operation directory or manifest with a contextual diagnostic.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsMissingOpenRecords() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path process = cleanup.add(Files.createTempDirectory("operation-open-missing-"));
			Path operation = process.resolve("operations/missing").toAbsolutePath().normalize();

			IOException failure = requireIo(() -> OperationDirectory.open(JSON, CLOCK, operation));

			requireThat(failure.getMessage(), "message").contains(operation.toString());
		}
	}

	/**
	 * Rejects a symbolic-link operation directory instead of following it.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsUnsafeOpenPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = create(cleanup, 1, 1, OperationKind.DISCOVER_PROMPT_SYSTEM);
			Path link = fixture.process().resolve("linked-operation");
			Files.createSymbolicLink(link, fixture.directory().root());

			requireIo(() -> OperationDirectory.open(JSON, CLOCK, link));
		}
	}

	/**
	 * Rejects an unknown manifest field.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsUnknownPersistedField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = create(cleanup, 1, 1, OperationKind.DISCOVER_PROMPT_SYSTEM);
			ObjectNode manifest = (ObjectNode) JSON.readTree(fixture.directory().manifestPath().toFile());
			manifest.put("unknown", true);
			RecordStore.write(JSON, fixture.directory().manifestPath(), manifest);

			IOException failure = requireIo(() -> OperationDirectory.open(JSON, CLOCK, fixture.directory().root()));

			requireThat(failure.getMessage(), "message").contains(fixture.directory().manifestPath().toString());
		}
	}

	/**
	 * Rejects a missing required manifest field.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsMissingPersistedField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = create(cleanup, 1, 1, OperationKind.DISCOVER_PROMPT_SYSTEM);
			ObjectNode manifest = (ObjectNode) JSON.readTree(fixture.directory().manifestPath().toFile());
			manifest.remove("claim_path");
			RecordStore.write(JSON, fixture.directory().manifestPath(), manifest);

			requireIo(() -> OperationDirectory.open(JSON, CLOCK, fixture.directory().root()));
		}
	}

	/**
	 * Rejects a duplicated persisted field.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsDuplicatePersistedField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = create(cleanup, 1, 1, OperationKind.DISCOVER_PROMPT_SYSTEM);
			Path manifest = fixture.directory().manifestPath();
			String duplicated = Files.readString(manifest).replaceFirst("\\{", "{\\\"schema_version\\\":1,");
			Files.writeString(manifest, duplicated);

			requireIo(() -> OperationDirectory.open(JSON, CLOCK, fixture.directory().root()));
		}
	}

	/**
	 * Rejects trailing JSON content.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsTrailingPersistedContent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = create(cleanup, 1, 1, OperationKind.DISCOVER_PROMPT_SYSTEM);
			Path manifest = fixture.directory().manifestPath();
			Files.writeString(manifest, Files.readString(manifest) + "{}\n");

			requireIo(() -> OperationDirectory.open(JSON, CLOCK, fixture.directory().root()));
		}
	}

	/**
	 * Rejects a persisted field with the wrong JSON type.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsTypeInvalidPersistedField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = create(cleanup, 1, 1, OperationKind.DISCOVER_PROMPT_SYSTEM);
			ObjectNode state = (ObjectNode) JSON.readTree(fixture.directory().statePath().toFile());
			state.put("schema_version", "1");
			RecordStore.write(JSON, fixture.directory().statePath(), state);

			requireIo(() -> OperationDirectory.open(JSON, CLOCK, fixture.directory().root()));
		}
	}

	/**
	 * Rejects a semantically valid but noncanonical persisted instant.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsNoncanonicalPersistedValue() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = create(cleanup, 1, 1, OperationKind.DISCOVER_PROMPT_SYSTEM);
			ObjectNode state = (ObjectNode) JSON.readTree(fixture.directory().statePath().toFile());
			state.put("updated_at", "2026-09-01T12:00:00+00:00");
			RecordStore.write(JSON, fixture.directory().statePath(), state);

			requireIo(() -> OperationDirectory.open(JSON, CLOCK, fixture.directory().root()));
		}
	}

	/**
	 * Starts a pending operation and writes only the exact running-state fields.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void startsPendingOperation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = create(cleanup, 1, 1, OperationKind.DISCOVER_PROMPT_SYSTEM);

			fixture.directory().start(SHA_A);

			JsonNode state = JSON.readTree(fixture.directory().statePath().toFile());
			requireThat(fieldNames(state), "stateFields").containsExactly(List.of("schema_version", "operation_id",
				"state", "input_sha256", "updated_at", "receipt_sha256"));
			requireThat(state.get("state").stringValue(), "state").isEqualTo("running");
			requireThat(state.get("receipt_sha256").isNull(), "receiptFingerprint").isTrue();
		}
	}

	/**
	 * Rejects the wrong input identity, a second start, and changed durable state.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsInvalidStartTransitions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = create(cleanup, 1, 1, OperationKind.DISCOVER_PROMPT_SYSTEM);
			requireIo(() -> fixture.directory().start(SHA_B));
			fixture.directory().start(SHA_A);
			requireIo(() -> fixture.directory().start(SHA_A));

			Fixture changed = create(cleanup, 2, 1, OperationKind.DISCOVER_PROMPT_SYSTEM);
			ObjectNode manifest = (ObjectNode) JSON.readTree(changed.directory().manifestPath().toFile());
			manifest.put("contract_sha256", SHA_C);
			RecordStore.write(JSON, changed.directory().manifestPath(), manifest);
			requireIo(() -> changed.directory().start(SHA_A));
		}
	}

	/**
	 * Completes a running operation with the exact completed receipt schema.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void completesWithExactReceipt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = running(cleanup, OperationKind.DISCOVER_PROMPT_SYSTEM);
			OptimizationResult result = discoveredResult(fixture.process(), "evidence");

			OperationDirectory.Receipt receipt = fixture.directory().complete(result);

			JsonNode persisted = JSON.readTree(fixture.directory().receiptPath().toFile());
			requireThat(fieldNames(persisted), "receiptFields").containsExactly(List.of("schema_version", "operation_id",
				"operation_kind", "transition_sequence", "attempt", "input_sha256", "contract_sha256",
				"environment_sha256", "state", "started_at", "completed_at", "result_kind", "evidence_path",
				"evidence_sha256", "successor_kind"));
			requireThat(persisted.get("state").stringValue(), "state").isEqualTo("completed");
			requireThat(persisted.get("result_kind").stringValue(), "resultKind").isEqualTo("discovered");
			requireThat(persisted.get("successor_kind").stringValue(), "successorKind").
				isEqualTo("analyze-prompt-system");
			requireThat(fixture.directory().verifiedTerminalReceipt().orElseThrow(), "verifiedReceipt").
				isEqualTo(receipt);
			requireThat(fixture.directory().state().receiptFingerprint().orElseThrow(), "receiptFingerprint").
				isEqualTo(Fingerprints.ofFile(fixture.directory().receiptPath()));
		}
	}

	/**
	 * Returns an identical completed retry without rewriting receipt or state bytes.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void retainsExactCompletedRetry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = running(cleanup, OperationKind.DISCOVER_PROMPT_SYSTEM);
			OptimizationResult result = discoveredResult(fixture.process(), "evidence");
			OperationDirectory.Receipt first = fixture.directory().complete(result);
			byte[] receiptBytes = Files.readAllBytes(fixture.directory().receiptPath());
			byte[] stateBytes = Files.readAllBytes(fixture.directory().statePath());

			OperationDirectory.Receipt second = fixture.directory().complete(result);

			requireThat(second, "retry").isEqualTo(first);
			requireThat(Files.readAllBytes(fixture.directory().receiptPath()), "receiptBytes").
				containsExactly(receiptBytes);
			requireThat(Files.readAllBytes(fixture.directory().statePath()), "stateBytes").containsExactly(stateBytes);
		}
	}

	/**
	 * Rejects a completed retry that requests different retained evidence.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsConflictingCompletedRetry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = running(cleanup, OperationKind.DISCOVER_PROMPT_SYSTEM);
			fixture.directory().complete(discoveredResult(fixture.process(), "first"));
			OptimizationResult second = discoveredResult(fixture.process(), "second");

			requireIo(() -> fixture.directory().complete(second));
		}
	}

	/**
	 * Rejects invalid completed results before changing durable state.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsInvalidCompletion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = running(cleanup, OperationKind.DISCOVER_PROMPT_SYSTEM);
			Path evidence = Files.writeString(fixture.process().resolve("completion-evidence.json"), "evidence");
			String fingerprint = Fingerprints.ofFile(evidence);
			OptimizationResult wrongOperation = new OptimizationResult(OperationKind.ANALYZE_PROMPT_SYSTEM,
				OperationResultKind.ANALYZED, evidence, fingerprint, Optional.of(OperationKind.EXTRACT_CONTRACT));
			OptimizationResult wrongFingerprint = new OptimizationResult(OperationKind.DISCOVER_PROMPT_SYSTEM,
				OperationResultKind.DISCOVERED, evidence, SHA_C, Optional.of(OperationKind.ANALYZE_PROMPT_SYSTEM));
			OptimizationResult wrongSuccessor = new OptimizationResult(OperationKind.DISCOVER_PROMPT_SYSTEM,
				OperationResultKind.DISCOVERED, evidence, fingerprint, Optional.of(OperationKind.EXTRACT_CONTRACT));

			requireIo(() -> fixture.directory().complete(wrongOperation));
			requireIo(() -> fixture.directory().complete(wrongFingerprint));
			requireIo(() -> fixture.directory().complete(wrongSuccessor));
			requireThat(fixture.directory().state().state(), "state").isEqualTo(OperationState.RUNNING);
			requireThat(Files.notExists(fixture.directory().receiptPath()), "receiptAbsent").isTrue();
		}
	}

	/**
	 * Publishes the exact failed receipt schema.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void failsWithExactReceipt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = running(cleanup, OperationKind.DISCOVER_PROMPT_SYSTEM);

			OperationDirectory.Receipt receipt = fixture.directory().fail(
				OperationDirectory.FailureKind.PROTOCOL_FAILURE, "invalid response", true);

			JsonNode persisted = JSON.readTree(fixture.directory().receiptPath().toFile());
			requireThat(fieldNames(persisted), "receiptFields").containsExactly(List.of("schema_version", "operation_id",
				"operation_kind", "transition_sequence", "attempt", "input_sha256", "contract_sha256",
				"environment_sha256", "state", "started_at", "completed_at", "failure_kind", "diagnostic",
				"retryable"));
			requireThat(persisted.get("failure_kind").stringValue(), "failureKind").isEqualTo("protocol_failure");
			requireThat(persisted.get("diagnostic").stringValue(), "diagnostic").isEqualTo("invalid response");
			requireThat(persisted.get("retryable").booleanValue(), "retryable").isTrue();
			requireThat(receipt.state(), "state").isEqualTo(OperationState.FAILED);
		}
	}

	/**
	 * Returns an identical failed retry without rewriting receipt or state bytes.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void retainsExactFailedRetry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = running(cleanup, OperationKind.DISCOVER_PROMPT_SYSTEM);
			OperationDirectory.Receipt first = fixture.directory().fail(
				OperationDirectory.FailureKind.INFRASTRUCTURE_FAILURE, "offline", true);
			byte[] receiptBytes = Files.readAllBytes(fixture.directory().receiptPath());
			byte[] stateBytes = Files.readAllBytes(fixture.directory().statePath());

			OperationDirectory.Receipt second = fixture.directory().fail(
				OperationDirectory.FailureKind.INFRASTRUCTURE_FAILURE, "offline", true);

			requireThat(second, "retry").isEqualTo(first);
			requireThat(Files.readAllBytes(fixture.directory().receiptPath()), "receiptBytes").
				containsExactly(receiptBytes);
			requireThat(Files.readAllBytes(fixture.directory().statePath()), "stateBytes").containsExactly(stateBytes);
		}
	}

	/**
	 * Rejects failed retries whose requested failure identity differs.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsConflictingFailedRetry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = running(cleanup, OperationKind.DISCOVER_PROMPT_SYSTEM);
			fixture.directory().fail(OperationDirectory.FailureKind.PROTOCOL_FAILURE, "invalid response", true);

			requireIo(() -> fixture.directory().fail(OperationDirectory.FailureKind.PROTOCOL_FAILURE,
				"different response", true));
			requireIo(() -> fixture.directory().fail(OperationDirectory.FailureKind.PROTOCOL_FAILURE,
				"invalid response", false));
			requireIo(() -> fixture.directory().fail(OperationDirectory.FailureKind.INVALID_INPUT,
				"invalid response", true));
		}
	}

	/**
	 * Rejects a terminal receipt whose exact published bytes changed.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsChangedReceiptBytes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = running(cleanup, OperationKind.DISCOVER_PROMPT_SYSTEM);
			fixture.directory().fail(OperationDirectory.FailureKind.PROTOCOL_FAILURE, "invalid response", false);
			Files.writeString(fixture.directory().receiptPath(), Files.readString(fixture.directory().receiptPath()) + "\n");

			requireIo(fixture.directory()::verifiedTerminalReceipt);
		}
	}

	/**
	 * Rejects fields from the completed receipt variant in a failed receipt.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsVariantIncompatibleReceipt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = running(cleanup, OperationKind.DISCOVER_PROMPT_SYSTEM);
			fixture.directory().fail(OperationDirectory.FailureKind.PROTOCOL_FAILURE, "invalid response", false);
			ObjectNode receipt = (ObjectNode) JSON.readTree(fixture.directory().receiptPath().toFile());
			receipt.put("result_kind", "discovered");
			RecordStore.write(JSON, fixture.directory().receiptPath(), receipt);
			bindStateToReceipt(fixture.directory());

			requireIo(() -> OperationDirectory.open(JSON, CLOCK, fixture.directory().root()));
		}
	}

	/**
	 * Recovers a retained completed receipt without replacing its bytes.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void recoversReceiptBeforeTerminalState() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = running(cleanup, OperationKind.DISCOVER_PROMPT_SYSTEM);
			OptimizationResult result = discoveredResult(fixture.process(), "evidence");
			OperationDirectory.Receipt expected = fixture.directory().complete(result);
			byte[] receiptBytes = Files.readAllBytes(fixture.directory().receiptPath());
			writeRunningState(fixture.directory());
			OperationDirectory recovered = OperationDirectory.open(JSON, CLOCK, fixture.directory().root());

			OperationDirectory.Receipt actual = recovered.complete(result);

			requireThat(actual, "receipt").isEqualTo(expected);
			requireThat(Files.readAllBytes(recovered.receiptPath()), "receiptBytes").containsExactly(receiptBytes);
			requireThat(recovered.state().state(), "state").isEqualTo(OperationState.COMPLETED);
		}
	}

	/**
	 * Enforces manifest identity, fingerprints, and fixed relative filenames.
	 */
	@Test
	public void enforcesManifestInvariants()
	{
		requireRuntime(IllegalArgumentException.class, () -> new OperationDirectory.Manifest(1, "wrong", 1, 1,
			OperationKind.DISCOVER_PROMPT_SYSTEM, SHA_A, SHA_B, SHA_C, "state.json", "receipt.json",
			"transcript.jsonl", "claim.json"));
		requireRuntime(IllegalArgumentException.class, () -> new OperationDirectory.Manifest(1,
			"00000001-discover-prompt-system-a001", 1, 1, OperationKind.DISCOVER_PROMPT_SYSTEM, SHA_A, SHA_B,
			SHA_C, "other.json", "receipt.json", "transcript.jsonl", "claim.json"));
	}

	/**
	 * Enforces state fingerprint variants and required identity.
	 */
	@Test
	public void enforcesStateInvariants()
	{
		requireRuntime(IllegalArgumentException.class, () -> new OperationDirectory.StateSnapshot(1, "operation",
			OperationState.PENDING, SHA_A, Instant.EPOCH, Optional.of(SHA_B)));
		requireRuntime(IllegalArgumentException.class, () -> new OperationDirectory.StateSnapshot(1, "operation",
			OperationState.COMPLETED, SHA_A, Instant.EPOCH, Optional.empty()));
		requireRuntime(IllegalArgumentException.class, () -> new OperationDirectory.StateSnapshot(1, "operation",
			OperationState.FAILED, SHA_A, Instant.EPOCH, Optional.of("not-a-sha")));
	}

	/**
	 * Enforces completed and failed receipt variants and registry successor rules.
	 */
	@Test
	public void enforcesReceiptInvariants()
	{
		Path evidence = Path.of("/evidence.json");
		requireRuntime(IllegalArgumentException.class, () -> completedReceipt(OperationKind.DISCOVER_PROMPT_SYSTEM,
			OperationResultKind.DISCOVERED, evidence, Optional.empty()));
		requireRuntime(IllegalArgumentException.class, () -> completedReceipt(OperationKind.DISCOVER_PROMPT_SYSTEM,
			OperationResultKind.DISCOVERED, evidence, Optional.of(OperationKind.EXTRACT_CONTRACT)));
		requireRuntime(IllegalArgumentException.class, () -> new OperationDirectory.Receipt(1,
			"00000001-discover-prompt-system-a001", OperationKind.DISCOVER_PROMPT_SYSTEM, 1, 1, SHA_A, SHA_B,
			SHA_C, OperationState.FAILED, Instant.EPOCH, Instant.EPOCH.minusSeconds(1), Optional.empty(),
			Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(
				OperationDirectory.FailureKind.INVALID_INPUT), Optional.of("invalid"), Optional.of(false)));
		OperationDirectory.Receipt terminal = completedReceipt(OperationKind.FINISH_CERTIFICATION_PROCESS,
			OperationResultKind.CERTIFIED, evidence, Optional.empty());
		requireThat(terminal.successorKind(), "successor").isEmpty();
	}

	/**
	 * Converts only the four exact lowercase failure-kind values.
	 */
	@Test
	public void parsesFailureKinds()
	{
		Map<String, OperationDirectory.FailureKind> expected = Map.of(
			"invalid_input", OperationDirectory.FailureKind.INVALID_INPUT,
			"protocol_failure", OperationDirectory.FailureKind.PROTOCOL_FAILURE,
			"persistence_failure", OperationDirectory.FailureKind.PERSISTENCE_FAILURE,
			"infrastructure_failure", OperationDirectory.FailureKind.INFRASTRUCTURE_FAILURE);
		for (Map.Entry<String, OperationDirectory.FailureKind> entry : expected.entrySet())
		{
			requireThat(OperationDirectory.FailureKind.fromExternalValue(entry.getKey()), "parsed").
				isEqualTo(entry.getValue());
			requireThat(entry.getValue().externalValue(), "externalValue").isEqualTo(entry.getKey());
		}
		requireRuntime(IllegalArgumentException.class,
			() -> OperationDirectory.FailureKind.fromExternalValue("PROTOCOL_FAILURE"));
	}

	/**
	 * Creates one pending operation fixture.
	 *
	 * @param cleanup the scope that removes the fixture directory
	 * @param sequence the operation transition sequence
	 * @param attempt the operation attempt
	 * @param kind the operation kind
	 * @return the pending operation fixture
	 * @throws IOException if the fixture cannot be created
	 */
	private static Fixture create(TemporaryDirectories cleanup, long sequence, int attempt, OperationKind kind)
		throws IOException
	{
		Path process = cleanup.add(Files.createTempDirectory("operation-directory-"));
		Path operation = OperationDirectory.path(process, sequence, kind, attempt);
		OptimizationContext context = context(process, operation, kind);
		OperationDirectory directory = OperationDirectory.create(JSON, CLOCK, context, sequence, attempt, input(kind));
		return new Fixture(process, directory);
	}

	/**
	 * Creates one running operation fixture.
	 *
	 * @param cleanup the scope that removes the fixture directory
	 * @param kind the operation kind
	 * @return the running operation fixture
	 * @throws IOException if the fixture cannot be created or started
	 */
	private static Fixture running(TemporaryDirectories cleanup, OperationKind kind) throws IOException
	{
		Fixture fixture = create(cleanup, 1, 1, kind);
		fixture.directory().start(SHA_A);
		return fixture;
	}

	/**
	 * Creates the fixed operation context used by this test.
	 *
	 * @param process the certification-process directory
	 * @param operation the operation directory
	 * @param kind the operation kind
	 * @return the operation context
	 */
	private static OptimizationContext context(Path process, Path operation, OperationKind kind)
	{
		return new OptimizationContext(JSON, CLOCK, process, operation, kind, SHA_B, SHA_C);
	}

	/**
	 * Creates a fixed-fingerprint input for one operation kind.
	 *
	 * @param kind the operation kind
	 * @return the operation input
	 */
	private static OptimizationInput input(OperationKind kind)
	{
		return new OptimizationInput()
		{
			@Override
			public OperationKind operationKind()
			{
				return kind;
			}

			@Override
			public String inputFingerprint()
			{
				return SHA_A;
			}
		};
	}

	/**
	 * Creates retained discovery evidence and its matching result.
	 *
	 * @param process the directory that receives evidence
	 * @param filename the evidence filename without its extension
	 * @return the discovery result
	 * @throws IOException if evidence cannot be written or fingerprinted
	 */
	private static OptimizationResult discoveredResult(Path process, String filename) throws IOException
	{
		Path evidence = Files.writeString(process.resolve(filename + ".json"), filename);
		return new OptimizationResult(OperationKind.DISCOVER_PROMPT_SYSTEM, OperationResultKind.DISCOVERED,
			evidence, Fingerprints.ofFile(evidence), Optional.of(OperationKind.ANALYZE_PROMPT_SYSTEM));
	}

	/**
	 * Creates a completed receipt for constructor-invariant tests.
	 *
	 * @param operation the completed operation
	 * @param result the operation result
	 * @param evidence the canonical evidence path
	 * @param successor the selected successor
	 * @return the completed receipt
	 */
	private static OperationDirectory.Receipt completedReceipt(OperationKind operation,
		OperationResultKind result, Path evidence, Optional<OperationKind> successor)
	{
		String operationId = "%08d-%s-a%03d".formatted(1, operation.externalValue(), 1);
		return new OperationDirectory.Receipt(1, operationId, operation, 1, 1, SHA_A, SHA_B, SHA_C,
			OperationState.COMPLETED, Instant.EPOCH, Instant.EPOCH, Optional.of(result), Optional.of(evidence),
			Optional.of(SHA_A), successor, Optional.empty(), Optional.empty(), Optional.empty());
	}

	/**
	 * Rebinds a terminal state to deliberately changed receipt bytes.
	 *
	 * @param directory the operation directory
	 * @throws IOException if the state or receipt cannot be read or written
	 */
	private static void bindStateToReceipt(OperationDirectory directory) throws IOException
	{
		ObjectNode state = (ObjectNode) JSON.readTree(directory.statePath().toFile());
		state.put("receipt_sha256", Fingerprints.ofFile(directory.receiptPath()));
		RecordStore.write(JSON, directory.statePath(), state);
	}

	/**
	 * Restores the running state to model a crash after receipt publication.
	 *
	 * @param directory the operation directory
	 * @throws IOException if the state cannot be written
	 */
	private static void writeRunningState(OperationDirectory directory) throws IOException
	{
		ObjectNode state = JSON.createObjectNode();
		state.put("schema_version", 1);
		state.put("operation_id", directory.manifest().operationId());
		state.put("state", "running");
		state.put("input_sha256", SHA_A);
		state.put("updated_at", CLOCK.instant().toString());
		state.putNull("receipt_sha256");
		RecordStore.write(JSON, directory.statePath(), state);
	}

	/**
	 * Returns JSON object field names in insertion order.
	 *
	 * @param node the JSON object
	 * @return its ordered field names
	 */
	private static List<String> fieldNames(JsonNode node)
	{
		return node.properties().stream().map(Map.Entry::getKey).toList();
	}

	/**
	 * Requires an action to throw {@link IOException}.
	 *
	 * @param action the action to run
	 * @return the thrown exception
	 */
	private static IOException requireIo(IoAction action)
	{
		try
		{
			action.run();
			throw new AssertionError("Expected IOException");
		}
		catch (IOException e)
		{
			return e;
		}
	}

	/**
	 * Requires an action to throw one runtime-exception type.
	 *
	 * @param expected the expected type
	 * @param action the action to run
	 */
	private static void requireRuntime(Class<? extends RuntimeException> expected, RuntimeAction action)
	{
		try
		{
			action.run();
			throw new AssertionError("Expected " + expected.getSimpleName());
		}
		catch (RuntimeException e)
		{
			requireThat(expected.isInstance(e), "exceptionType").isTrue();
		}
		catch (IOException e)
		{
			throw new AssertionError("Expected " + expected.getSimpleName(), e);
		}
	}

	/**
	 * Holds one operation fixture.
	 *
	 * @param process the certification-process directory
	 * @param directory the operation directory
	 */
	private record Fixture(Path process, OperationDirectory directory)
	{
	}

	@FunctionalInterface
	private interface IoAction
	{
		/**
		 * Runs fixture I/O.
		 *
		 * @throws IOException if the modeled operation fails
		 */
		void run() throws IOException;
	}

	@FunctionalInterface
	private interface RuntimeAction
	{
		/**
		 * Runs code that may fail before or during I/O.
		 *
		 * @throws IOException if an unexpected I/O failure occurs
		 */
		void run() throws IOException;
	}
}
