package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.optimization.ReproductionState;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Writes the retained evidence locations that an independent evaluator needs after a successful harness run.
 */
public final class HarnessRunReceipt
{
	private static final Set<String> COMPLETE_FIELDS = Set.of("schema_version", "retained_project",
		"retained_project_sha256", "raw_result", "raw_result_sha256", "transcript", "transcript_sha256",
		"harness_version", "runner_version", "usage", "reproduction_state");
	/**
	 * Writes one receipt after verifying that both referenced artifacts are retained.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param destination the receipt destination
	 * @param run the retained harness run
	 * @param rawResult the durable harness response
	 * @throws IOException if retained evidence cannot be verified or written
	 */
	public static void write(JsonMapper json, Path destination, HarnessRun run, Path rawResult) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(destination, "destination").isNotNull();
		requireThat(run, "run").isNotNull();
		requireThat(rawResult, "rawResult").isNotNull();
		HarnessEvaluationResult result = HarnessEvaluationResult.retained(run.directory().resolve("project"), rawResult);
		Path parent = destination.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Receipt has no parent directory: " + destination);
		Files.createDirectories(parent);
		ObjectNode receipt = json.createObjectNode();
		receipt.put("retained_project", result.retainedProject().toString());
		receipt.put("raw_result", result.rawResult().toString());
		Path temporary = Files.createTempFile(parent, ".harness-receipt-", ".json");
		try
		{
			json.writeValue(temporary.toFile(), receipt);
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			Files.deleteIfExists(temporary);
			throw e;
		}
	}

	/**
	 * Writes the complete receipt used by certification and returns its validated value.
	 *
	 * @param json the JSON codec
	 * @param destination the receipt destination
	 * @param run the retained harness run
	 * @param rawResult the retained raw result
	 * @param transcript the retained harness transcript
	 * @param harnessVersion the nonblank harness version
	 * @param runnerVersion the nonblank CAT runner version
	 * @param usage provider token usage
	 * @param reproductionState retained-evidence reproduction state
	 * @return the published and revalidated receipt
	 * @throws IOException if the evidence or receipt cannot be read or written
	 */
	public static Receipt writeComplete(JsonMapper json, Path destination, HarnessRun run, Path rawResult,
		Path transcript, String harnessVersion, String runnerVersion, ProviderUsage usage,
		ReproductionState reproductionState) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(destination, "destination must not be null");
		Objects.requireNonNull(run, "run must not be null");
		Objects.requireNonNull(rawResult, "rawResult must not be null");
		Objects.requireNonNull(transcript, "transcript must not be null");
		Objects.requireNonNull(harnessVersion, "harnessVersion must not be null");
		Objects.requireNonNull(runnerVersion, "runnerVersion must not be null");
		Objects.requireNonNull(usage, "usage must not be null");
		Objects.requireNonNull(reproductionState, "reproductionState must not be null");
		Path project = run.directory().resolve("project").toAbsolutePath().normalize();
		Path normalizedRaw = rawResult.toAbsolutePath().normalize();
		Path normalizedTranscript = transcript.toAbsolutePath().normalize();
		if (!Files.isDirectory(project) || !Files.isRegularFile(normalizedRaw) ||
			!Files.isRegularFile(normalizedTranscript))
			throw new IOException("Complete harness receipt requires retained project, result, and transcript");
		Receipt result = new Receipt(HarnessEvaluationResult.retained(project, normalizedRaw), normalizedTranscript,
			harnessVersion, runnerVersion, usage, reproductionState,
			Map.of("retained_project", Fingerprints.ofArtifact(project), "raw_result",
				Fingerprints.ofFile(normalizedRaw), "transcript", Fingerprints.ofFile(normalizedTranscript)));
		ObjectNode encoded = json.createObjectNode();
		encoded.put("schema_version", 1);
		encoded.put("retained_project", result.result().retainedProject().toString());
		encoded.put("retained_project_sha256", result.evidenceFingerprints().get("retained_project"));
		encoded.put("raw_result", result.result().rawResult().toString());
		encoded.put("raw_result_sha256", result.evidenceFingerprints().get("raw_result"));
		encoded.put("transcript", result.transcript().toString());
		encoded.put("transcript_sha256", result.evidenceFingerprints().get("transcript"));
		encoded.put("harness_version", result.harnessVersion());
		encoded.put("runner_version", result.runnerVersion());
		encoded.set("usage", usageNode(json, usage));
		encoded.put("reproduction_state", reproductionState.externalValue());
		writeAtomically(json, destination, encoded);
		return readComplete(json, destination);
	}

	/**
	 * Reads and validates one complete receipt.
	 *
	 * @param json the JSON codec
	 * @param source the receipt source
	 * @return the validated receipt
	 * @throws IOException if the receipt is malformed or its evidence has changed
	 */
	public static Receipt readComplete(JsonMapper json, Path source) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		return fromCompleteReceipt(json.readTree(source.toFile()), source);
	}

	/**
	 * Converts a parsed complete receipt to its validated typed form.
	 *
	 * @param value the parsed receipt value
	 * @param source the receipt source
	 * @return the validated receipt
	 * @throws IOException if the receipt is malformed or its evidence has changed
	 */
	public static Receipt fromCompleteReceipt(JsonNode value, Path source) throws IOException
	{
		if (!(value instanceof ObjectNode receipt) || !new HashSet<>(receipt.propertyNames()).equals(COMPLETE_FIELDS))
			throw new IOException("Complete harness receipt has unsupported or missing fields: " + source);
		if (!receipt.path("schema_version").isIntegralNumber() || receipt.path("schema_version").asInt(-1) != 1)
			throw new IOException("Unsupported complete harness receipt schema: " + source);
		Path project = absolute(receipt, "retained_project", source);
		Path raw = absolute(receipt, "raw_result", source);
		Path transcript = absolute(receipt, "transcript", source);
		String projectFingerprint = requiredFingerprint(receipt, "retained_project_sha256", source);
		String rawFingerprint = requiredFingerprint(receipt, "raw_result_sha256", source);
		String transcriptFingerprint = requiredFingerprint(receipt, "transcript_sha256", source);
		if (!Files.isDirectory(project) || !Files.isRegularFile(raw) || !Files.isRegularFile(transcript) ||
			!Fingerprints.ofArtifact(project).equals(projectFingerprint) ||
			!Fingerprints.ofFile(raw).equals(rawFingerprint) ||
			!Fingerprints.ofFile(transcript).equals(transcriptFingerprint))
			throw new IOException("Complete harness receipt evidence changed or is unavailable: " + source);
		ProviderUsage usage = usage(receipt, source);
		return new Receipt(HarnessEvaluationResult.retained(project, raw), transcript,
			requiredText(receipt, "harness_version", source), requiredText(receipt, "runner_version", source), usage,
			ReproductionState.fromExternalValue(requiredText(receipt, "reproduction_state", source)),
			Map.of("retained_project", projectFingerprint, "raw_result", rawFingerprint, "transcript",
				transcriptFingerprint));
	}

	/**
	 * Complete retained runner receipt.
	 *
	 * @param result the retained project and raw result
	 * @param transcript the retained harness transcript
	 * @param harnessVersion the harness version
	 * @param runnerVersion the CAT runner version
	 * @param usage provider token usage
	 * @param reproductionState retained-evidence reproduction state
	 * @param evidenceFingerprints retained artifact fingerprints
	 */
	public record Receipt(HarnessEvaluationResult result, Path transcript, String harnessVersion, String runnerVersion,
		ProviderUsage usage, ReproductionState reproductionState, Map<String, String> evidenceFingerprints)
	{
		/**
		 * Validates and snapshots a complete receipt.
		 *
		 * @param result the retained project and raw result
		 * @param transcript the retained harness transcript
		 * @param harnessVersion the harness version
		 * @param runnerVersion the CAT runner version
		 * @param usage provider token usage
		 * @param reproductionState retained-evidence reproduction state
		 * @param evidenceFingerprints retained artifact fingerprints
		 */
		public Receipt
		{
			Objects.requireNonNull(result, "result must not be null");
			Objects.requireNonNull(transcript, "transcript must not be null");
			Objects.requireNonNull(harnessVersion, "harnessVersion must not be null");
			Objects.requireNonNull(runnerVersion, "runnerVersion must not be null");
			Objects.requireNonNull(usage, "usage must not be null");
			Objects.requireNonNull(reproductionState, "reproductionState must not be null");
			Objects.requireNonNull(evidenceFingerprints, "evidenceFingerprints must not be null");
			if (harnessVersion.isBlank() || runnerVersion.isBlank())
				throw new IllegalArgumentException("Version values must not be blank");
			transcript = transcript.toAbsolutePath().normalize();
			evidenceFingerprints = Map.copyOf(evidenceFingerprints);
		}
	}

	/**
	 * Reads the retained evidence locations from one completed harness execution.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param source the receipt path
	 * @return the validated retained evidence result
	 * @throws IOException if the receipt is malformed or names unavailable evidence
	 */
	public static HarnessEvaluationResult read(JsonMapper json, Path source) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(source, "source").isNotNull();
		if (!Files.isRegularFile(source))
			throw new IOException("Harness receipt is not a regular file: " + source);
		return fromReceipt(json.readTree(source.toFile()), source);
	}

	/**
	 * Converts one already-parsed harness receipt to its typed retained evidence.
	 *
	 * <p>
	 * Callers that already own the JSON parsing boundary use this method so the receipt schema remains authoritative in
	 * this class rather than being reimplemented by an evaluator.
	 *
	 * @param value the parsed receipt value
	 * @param source the receipt source used in diagnostics
	 * @return the validated retained evidence result
	 * @throws IOException if the receipt is malformed or names unavailable evidence
	 */
	public static HarnessEvaluationResult fromReceipt(JsonNode value, Path source) throws IOException
	{
		requireThat(value, "value").isNotNull();
		requireThat(source, "source").isNotNull();
		if (!(value instanceof ObjectNode receipt))
			throw new IOException("Harness receipt is not a JSON object: " + source);
		Set<String> names = new HashSet<>(receipt.propertyNames());
		if (!names.equals(Set.of("retained_project", "raw_result")))
			throw new IOException("Harness receipt has unsupported fields: " + source);
		return HarnessEvaluationResult.retained(path(receipt, "retained_project", source),
			path(receipt, "raw_result", source));
	}

	/**
	 * Atomically publishes one encoded receipt.
	 *
	 * @param json the JSON codec
	 * @param destination the receipt destination
	 * @param value the encoded receipt
	 * @throws IOException if publication fails
	 */
	private static void writeAtomically(JsonMapper json, Path destination, ObjectNode value) throws IOException
	{
		Path normalized = destination.toAbsolutePath().normalize();
		Path parent = normalized.getParent();
		if (parent == null)
			throw new IOException("Receipt has no parent directory: " + destination);
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".harness-receipt-", ".json");
		try
		{
			json.writeValue(temporary.toFile(), value);
			Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			Files.deleteIfExists(temporary);
			throw e;
		}
	}

	/**
	 * Reads a required canonical absolute path.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path
	 * @return the normalized absolute path
	 * @throws IOException if the field is not an absolute path
	 */
	private static Path absolute(ObjectNode object, String field, Path source) throws IOException
	{
		String value = requiredText(object, field, source);
		try
		{
			Path path = Path.of(value).toAbsolutePath().normalize();
			if (!Path.of(value).isAbsolute())
				throw new IOException("Complete harness receipt has a relative " + field + ": " + source);
			return path;
		}
		catch (java.nio.file.InvalidPathException e)
		{
			throw new IOException("Complete harness receipt has an invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Reads required nonblank text.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path
	 * @return the text value
	 * @throws IOException if the field is invalid
	 */
	private static String requiredText(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Complete harness receipt has no " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Reads one lowercase SHA-256 fingerprint.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path
	 * @return the fingerprint
	 * @throws IOException if the field is invalid
	 */
	private static String requiredFingerprint(ObjectNode object, String field, Path source) throws IOException
	{
		String value = requiredText(object, field, source);
		if (!value.matches("[0-9a-f]{64}"))
			throw new IOException("Complete harness receipt has an invalid " + field + ": " + source);
		return value;
	}

	/**
	 * Reads one nonnegative integer.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path
	 * @return the integer value
	 * @throws IOException if the field is invalid
	 */
	private static long requiredNonnegative(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isIntegralNumber() || value.asLong() < 0)
			throw new IOException("Complete harness receipt has an invalid " + field + ": " + source);
		return value.asLong();
	}

	/**
	 * Encodes provider usage as the complete receipt's nested usage object.
	 *
	 * @param json the JSON codec
	 * @param usage provider usage
	 * @return the encoded usage object
	 */
	private static ObjectNode usageNode(JsonMapper json, ProviderUsage usage)
	{
		ObjectNode result = json.createObjectNode();
		result.put("available", usage.available());
		result.put("input_tokens", usage.inputTokens());
		result.put("cached_input_tokens", usage.cachedInputTokens());
		result.put("output_tokens", usage.outputTokens());
		return result;
	}

	/**
	 * Reads the complete receipt's nested provider usage object.
	 *
	 * @param object the complete receipt
	 * @param source the receipt source
	 * @return provider usage
	 * @throws IOException if the usage object is invalid
	 */
	private static ProviderUsage usage(ObjectNode object, Path source) throws IOException
	{
		JsonNode value = object.path("usage");
		if (!(value instanceof ObjectNode usage) || !new HashSet<>(usage.propertyNames()).equals(
			Set.of("available", "input_tokens", "cached_input_tokens", "output_tokens")))
		{
			throw new IOException("Complete harness receipt has invalid usage: " + source);
		}
		JsonNode available = usage.path("available");
		if (!available.isBoolean())
			throw new IOException("Complete harness receipt has invalid usage availability: " + source);
		if (!available.asBoolean())
		{
			if (requiredNonnegative(usage, "input_tokens", source) != 0 ||
				requiredNonnegative(usage, "cached_input_tokens", source) != 0 ||
				requiredNonnegative(usage, "output_tokens", source) != 0)
			{
				throw new IOException("Unavailable provider usage must have zero token counts: " + source);
			}
			return ProviderUsage.unavailable();
		}
		return ProviderUsage.of(requiredNonnegative(usage, "input_tokens", source),
			requiredNonnegative(usage, "cached_input_tokens", source),
			requiredNonnegative(usage, "output_tokens", source));
	}

	/**
	 * Reads one required absolute artifact path from a receipt.
	 *
	 * @param receipt the parsed receipt
	 * @param field the required field name
	 * @param source the receipt source used in diagnostics
	 * @return the parsed path
	 * @throws IOException if the field is blank or invalid
	 */
	private static Path path(ObjectNode receipt, String field, Path source) throws IOException
	{
		String value = receipt.path(field).asString();
		if (value.isBlank())
			throw new IOException("Harness receipt has no " + field + ": " + source);
		try
		{
			Path result = Path.of(value);
			if (!result.isAbsolute())
				throw new IOException("Harness receipt has a relative " + field + ": " + source);
			return result;
		}
		catch (java.nio.file.InvalidPathException e)
		{
			throw new IOException("Harness receipt has an invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Prevents utility-class construction.
	 */
	private HarnessRunReceipt()
	{
	}
}
