package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.RecordStore;
import io.github.cowwoc.cat.common.optimization.ReproductionState;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns the durable, idempotent lifecycle of one typed harness evaluation.
 *
 * <p>
 * The operation record identifies the exact runner request without retaining its prompt-bearing bridge input. A retry
 * with the same input returns verified retained evidence. A retry with different input or a prior terminal failure
 * cannot invoke the runner again.
 */
public final class HarnessEvaluationOperation
{
	private static final int SCHEMA_VERSION = 1;
	private static final Set<String> BASE_FIELDS = Set.of("schema_version", "state", "input_sha256", "model", "effort");
	private static final Set<String> COMPLETED_FIELDS = withBaseFields("started_at_nanos", "completed_at_nanos",
		"owner_interrupted", "retained_project", "retained_project_sha256", "raw_result", "raw_result_sha256",
		"harness_version", "runner_version", "input_tokens", "cached_input_tokens", "output_tokens",
		"reproduction_state", "transcript_sha256");
	private static final Set<String> FAILED_FIELDS = withBaseFields("failure");

	/**
	 * Prevents construction of this static operation owner.
	 */
	private HarnessEvaluationOperation()
	{
	}

	/**
	 * Executes one typed request or returns its already completed retained result.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param request the complete typed runner request and evidence destinations
	 * @param runner the harness-neutral runner boundary
	 * @return the new or previously retained harness evidence and process timing
	 * @throws IOException if the request differs from retained identity, a prior attempt failed, an operation remains
	 * 	nonterminal, or evidence cannot be executed, retained, or verified
	 */
	public static HarnessEvaluationExecution execute(JsonMapper json, HarnessEvaluationRequest request,
		HarnessEvaluationRunner runner) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(request, "request").isNotNull();
		requireThat(runner, "runner").isNotNull();
		if (Thread.currentThread().isInterrupted())
			throw new IOException("Evaluation was interrupted before the next harness evaluation");
		Path manifest = manifestPath(request);
		String inputFingerprint = inputFingerprint(json, request);
		boolean[] claimed = new boolean[1];
		RecordStore.withLock(manifest, () ->
		{
			if (Files.exists(manifest))
				return;
			RecordStore.write(json, manifest, manifest(json, request, inputFingerprint, State.PENDING));
			RecordStore.write(json, manifest, manifest(json, request, inputFingerprint, State.RUNNING));
			claimed[0] = true;
		});
		if (!claimed[0])
			return retainedExecution(json, manifest, inputFingerprint);

		try
		{
			HarnessEvaluationExecution execution = runner.execute(request);
			ObjectNode completed = completedManifest(json, request, inputFingerprint, execution);
			RecordStore.withLock(manifest, () -> replaceRunning(json, manifest, inputFingerprint, completed));
			if (execution.interrupted())
				Thread.currentThread().interrupt();
			return execution;
		}
		catch (HarnessEvaluationFailure e)
		{
			retainFailure(json, request, inputFingerprint, manifest, e);
			if (e.interrupted())
				Thread.currentThread().interrupt();
			throw e;
		}
		catch (IOException e)
		{
			retainFailure(json, request, inputFingerprint, manifest, e);
			String diagnostic = e.getMessage();
			if (diagnostic == null || diagnostic.isBlank())
				diagnostic = e.getClass().getName();
			throw new HarnessEvaluationFailure(diagnostic, e, false);
		}
	}

	/**
	 * Returns a verified completed operation without permitting another harness invocation.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param request the complete retry request
	 * @return the retained harness evidence and original timing
	 * @throws IOException if the operation is missing, incompatible, failed, nonterminal, or irreproducible
	 */
	public static HarnessEvaluationExecution retained(JsonMapper json, HarnessEvaluationRequest request)
		throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(request, "request").isNotNull();
		return retainedExecution(json, manifestPath(request), inputFingerprint(json, request));
	}

	/**
	 * Retains a terminal runner failure without replacing its original diagnostic when record writing also fails.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param request the failed typed request
	 * @param inputFingerprint the request identity
	 * @param manifest the durable operation record
	 * @param failure the original runner or evidence failure
	 */
	private static void retainFailure(JsonMapper json, HarnessEvaluationRequest request, String inputFingerprint,
		Path manifest, IOException failure)
	{
		ObjectNode failed = manifest(json, request, inputFingerprint, State.FAILED);
		String diagnostic = failure.getMessage();
		if (diagnostic == null || diagnostic.isBlank())
			diagnostic = failure.getClass().getName();
		failed.put("failure", diagnostic);
		try
		{
			RecordStore.withLock(manifest, () -> replaceRunning(json, manifest, inputFingerprint, failed));
		}
		catch (IOException terminalFailure)
		{
			failure.addSuppressed(terminalFailure);
		}
	}

	/**
	 * Replaces a running record with exactly one terminal result.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param manifest the durable operation record
	 * @param inputFingerprint the expected request identity
	 * @param terminal the complete terminal record
	 * @throws IOException if the operation is no longer the expected running request or cannot be written
	 */
	private static void replaceRunning(JsonMapper json, Path manifest, String inputFingerprint, ObjectNode terminal)
		throws IOException
	{
		ObjectNode current = readManifest(json, manifest);
		verifyInput(current, inputFingerprint);
		if (state(current) != State.RUNNING)
			throw new IOException("Harness evaluation is not running: " + manifest);
		RecordStore.write(json, manifest, terminal);
	}

	/**
	 * Returns one completed operation without invoking its runner again.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param manifest the durable operation record
	 * @param inputFingerprint the retry request identity
	 * @return the verified retained result and original timing
	 * @throws IOException if the retained operation is incompatible, failed, nonterminal, or no longer reproducible
	 */
	private static HarnessEvaluationExecution retainedExecution(JsonMapper json, Path manifest, String inputFingerprint)
		throws IOException
	{
		ObjectNode retained = readManifest(json, manifest);
		verifyInput(retained, inputFingerprint);
		State state = state(retained);
		if (state == State.FAILED)
			throw new IOException("Retained harness evaluation failed: " + requiredText(retained, "failure", manifest));
		if (state != State.COMPLETED)
			throw new IOException("Harness evaluation has no terminal result: " + manifest + " (state: " +
				state.externalValue + ")");
		verifyFields(retained, COMPLETED_FIELDS, manifest);
		HarnessEvaluationResult result = HarnessEvaluationResult.retained(
			requiredPath(retained, "retained_project", manifest), requiredPath(retained, "raw_result", manifest));
		if (!Fingerprints.ofArtifact(result.retainedProject()).equals(
			requiredText(retained, "retained_project_sha256", manifest)) ||
			!Fingerprints.ofFile(result.rawResult()).equals(requiredText(retained, "raw_result_sha256", manifest)))
		{
			throw new IOException("Retained harness evaluation evidence changed: " + manifest);
		}
		ProviderUsage usage = ProviderUsage.of(requiredLong(retained, "input_tokens", manifest),
			requiredLong(retained, "cached_input_tokens", manifest), requiredLong(retained, "output_tokens", manifest));
		Map<String, String> fingerprints = Map.of("retained_project", requiredText(retained,
			"retained_project_sha256", manifest), "raw_result", requiredText(retained, "raw_result_sha256", manifest),
			"transcript", requiredText(retained, "transcript_sha256", manifest));
		return new HarnessEvaluationExecution(result, requiredLong(retained, "started_at_nanos", manifest),
			requiredLong(retained, "completed_at_nanos", manifest), requiredBoolean(retained, "owner_interrupted", manifest),
			requiredText(retained, "harness_version", manifest), requiredText(retained, "runner_version", manifest), usage,
			ReproductionState.fromExternalValue(requiredText(retained, "reproduction_state", manifest)), fingerprints);
	}

	/**
	 * Creates the terminal record for one successfully retained runner result.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param request the completed request
	 * @param inputFingerprint the request identity
	 * @param execution the runner's retained evidence and process timing
	 * @return the complete terminal record
	 * @throws IOException if retained evidence cannot be fingerprinted
	 */
	private static ObjectNode completedManifest(JsonMapper json, HarnessEvaluationRequest request,
		String inputFingerprint, HarnessEvaluationExecution execution) throws IOException
	{
		HarnessEvaluationResult result = execution.result();
		ObjectNode completed = manifest(json, request, inputFingerprint, State.COMPLETED);
		completed.put("started_at_nanos", execution.startedAtNanos());
		completed.put("completed_at_nanos", execution.completedAtNanos());
		completed.put("owner_interrupted", execution.interrupted());
		completed.put("retained_project", result.retainedProject().toString());
		completed.put("retained_project_sha256", Fingerprints.ofArtifact(result.retainedProject()));
		completed.put("raw_result", result.rawResult().toString());
		completed.put("raw_result_sha256", Fingerprints.ofFile(result.rawResult()));
		completed.put("harness_version", execution.harnessVersion());
		completed.put("runner_version", execution.runnerVersion());
		completed.put("input_tokens", execution.usage().inputTokens());
		completed.put("cached_input_tokens", execution.usage().cachedInputTokens());
		completed.put("output_tokens", execution.usage().outputTokens());
		completed.put("reproduction_state", execution.reproductionState().externalValue());
		String transcriptFingerprint = execution.evidenceFingerprints().get("transcript");
		if (transcriptFingerprint == null)
		{
			if (Files.isRegularFile(request.transcript()))
				transcriptFingerprint = Fingerprints.ofFile(request.transcript());
			else
				transcriptFingerprint = Fingerprints.ofUtf8("");
		}
		completed.put("transcript_sha256", transcriptFingerprint);
		return completed;
	}

	/**
	 * Creates one operation record without retaining the prompt-bearing runner input.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param request the typed request
	 * @param inputFingerprint the complete request identity
	 * @param state the operation state
	 * @return the operation record
	 */
	private static ObjectNode manifest(JsonMapper json, HarnessEvaluationRequest request, String inputFingerprint,
		State state)
	{
		ObjectNode result = json.createObjectNode();
		result.put("schema_version", SCHEMA_VERSION);
		result.put("state", state.externalValue);
		result.put("input_sha256", inputFingerprint);
		result.put("model", request.model());
		result.put("effort", request.effort());
		return result;
	}

	/**
	 * Hashes the complete request identity while excluding prompt-bearing input from the retained manifest.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param request the typed runner request
	 * @return the stable request fingerprint
	 * @throws IOException if the identity cannot be serialized or hashed
	 */
	private static String inputFingerprint(JsonMapper json, HarnessEvaluationRequest request) throws IOException
	{
		ObjectNode identity = json.createObjectNode();
		identity.put("launcher", request.launcher().toString());
		identity.put("artifact", request.artifact().toString());
		identity.put("project", request.project().toString());
		identity.put("model", request.model());
		identity.put("effort", request.effort());
		identity.put("input", request.input());
		identity.put("transcript", request.transcript().toString());
		identity.put("receipt", request.receipt().toString());
		identity.put("install_cat", request.installCat());
		ArrayNode evidence = identity.putArray("read_only_evidence");
		for (Path path : request.readOnlyEvidence())
		{
			ObjectNode entry = evidence.addObject();
			entry.put("path", path.toString());
			entry.put("sha256", Fingerprints.ofArtifact(path));
		}
		return Fingerprints.ofUtf8(json.writeValueAsString(identity));
	}

	/**
	 * Reads and validates the state-independent portion of one operation record.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param manifest the durable operation record
	 * @return the validated object
	 * @throws IOException if the record is malformed or uses another schema revision
	 */
	private static ObjectNode readManifest(JsonMapper json, Path manifest) throws IOException
	{
		JsonNode parsed = RecordStore.read(json, manifest);
		if (!(parsed instanceof ObjectNode result))
			throw new IOException("Harness evaluation manifest is not a JSON object: " + manifest);
		if (result.path("schema_version").asInt(-1) != SCHEMA_VERSION)
			throw new IOException("Unsupported harness evaluation manifest schema: " + manifest);
		State state = state(result);
		Set<String> expected = BASE_FIELDS;
		if (state == State.COMPLETED)
			expected = COMPLETED_FIELDS;
		else if (state == State.FAILED)
			expected = FAILED_FIELDS;
		verifyFields(result, expected, manifest);
		return result;
	}

	/**
	 * Verifies that a retry names the same original request.
	 *
	 * @param manifest the retained operation record
	 * @param inputFingerprint the retry request identity
	 * @throws IOException if the request identity changed
	 */
	private static void verifyInput(ObjectNode manifest, String inputFingerprint) throws IOException
	{
		if (!manifest.path("input_sha256").asString().equals(inputFingerprint))
			throw new IOException("Operation input differs from its retained manifest");
	}

	/**
	 * Converts the persisted state into the closed operation-state domain.
	 *
	 * @param manifest the operation record
	 * @return the supported operation state
	 * @throws IOException if the state is absent or unsupported
	 */
	private static State state(ObjectNode manifest) throws IOException
	{
		String value = manifest.path("state").asString();
		return switch (value)
		{
			case "pending" -> State.PENDING;
			case "running" -> State.RUNNING;
			case "completed" -> State.COMPLETED;
			case "failed" -> State.FAILED;
			default -> throw new IOException("Unsupported harness evaluation state: " + value);
		};
	}

	/**
	 * Verifies one record has exactly the fields owned by its state.
	 *
	 * @param manifest the operation record
	 * @param expectedFields the complete allowed field set
	 * @param source the source path used in diagnostics
	 * @throws IOException if a field is missing or unsupported
	 */
	private static void verifyFields(ObjectNode manifest, Set<String> expectedFields, Path source) throws IOException
	{
		Set<String> actualFields = new HashSet<>(manifest.propertyNames());
		if (!actualFields.equals(expectedFields))
			throw new IOException("Harness evaluation manifest has unsupported or missing fields: " + source);
	}

	/**
	 * Returns one required non-blank string field.
	 *
	 * @param manifest the operation record
	 * @param field the field to read
	 * @param source the source path used in diagnostics
	 * @return the field value
	 * @throws IOException if the field is blank
	 */
	private static String requiredText(ObjectNode manifest, String field, Path source) throws IOException
	{
		String value = manifest.path(field).asString();
		if (value.isBlank())
			throw new IOException("Harness evaluation manifest has no " + field + ": " + source);
		return value;
	}

	/**
	 * Returns one required absolute path field.
	 *
	 * @param manifest the operation record
	 * @param field the field to read
	 * @param source the source path used in diagnostics
	 * @return the absolute path
	 * @throws IOException if the field is blank, invalid, or relative
	 */
	private static Path requiredPath(ObjectNode manifest, String field, Path source) throws IOException
	{
		try
		{
			Path result = Path.of(requiredText(manifest, field, source));
			if (!result.isAbsolute())
				throw new IOException("Harness evaluation manifest has a relative " + field + ": " + source);
			return result;
		}
		catch (java.nio.file.InvalidPathException e)
		{
			throw new IOException("Harness evaluation manifest has an invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Returns one required non-negative long field.
	 *
	 * @param manifest the operation record
	 * @param field the field to read
	 * @param source the source path used in diagnostics
	 * @return the non-negative value
	 * @throws IOException if the field is absent, not integral, or negative
	 */
	private static long requiredLong(ObjectNode manifest, String field, Path source) throws IOException
	{
		JsonNode value = manifest.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
			throw new IOException("Harness evaluation manifest has an invalid " + field + ": " + source);
		return value.asLong();
	}

	/**
	 * Returns one required boolean field.
	 *
	 * @param manifest the operation record
	 * @param field the field to read
	 * @param source the source path
	 * @return the boolean value
	 * @throws IOException if the value is not boolean
	 */
	private static boolean requiredBoolean(ObjectNode manifest, String field, Path source) throws IOException
	{
		JsonNode value = manifest.path(field);
		if (!value.isBoolean())
			throw new IOException("Harness evaluation manifest has an invalid " + field + ": " + source);
		return value.asBoolean();
	}

	/**
	 * Derives the durable operation record beside the runner-owned receipt.
	 *
	 * @param request the typed runner request
	 * @return the operation manifest path
	 * @throws IOException if the receipt has no filename
	 */
	public static Path manifestPath(HarnessEvaluationRequest request) throws IOException
	{
		Path fileName = request.receipt().getFileName();
		if (fileName == null)
			throw new IOException("Harness receipt has no filename: " + request.receipt());
		return request.receipt().resolveSibling(fileName + ".operation.json");
	}

	/**
	 * Creates one exact field set by adding state-specific fields to the common fields.
	 *
	 * @param fields the state-specific fields
	 * @return the immutable complete field set
	 */
	private static Set<String> withBaseFields(String... fields)
	{
		Set<String> result = new HashSet<>(BASE_FIELDS);
		result.addAll(Set.of(fields));
		return Set.copyOf(result);
	}

	/**
	 * Names the durable lifecycle states of one atomic harness invocation.
	 */
	private enum State
	{
		PENDING("pending"),
		RUNNING("running"),
		COMPLETED("completed"),
		FAILED("failed");

		private final String externalValue;

		/**
		 * Associates one state with its persisted spelling.
		 *
		 * @param externalValue the persisted state value
		 */
		State(String externalValue)
		{
			this.externalValue = externalValue;
		}
	}
}
