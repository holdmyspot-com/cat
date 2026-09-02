package io.github.cowwoc.cat.common.optimization.certification;
import io.github.cowwoc.cat.common.RecordStore;

import io.github.cowwoc.cat.common.BoundedJsonLines;
import io.github.cowwoc.cat.common.Fingerprints;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Owns one append-only, hash-chained formal trial ledger for a frozen candidate and contract.
 */
public final class FormalTrialLedger
{
	private static final int SCHEMA_VERSION = 1;
	private static final int MAXIMUM_MANIFEST_BYTES = 1024 * 1024;
	private static final int MAXIMUM_OUTCOME_CHARACTERS = 32 * 1024;
	private static final int MAXIMUM_IDENTIFIER_CHARACTERS = 512;
	private static final int MAXIMUM_FAMILIES = 10_000;
	private static final String ROOT_FINGERPRINT = "ROOT";
	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
	private static final Set<String> MANIFEST_FIELDS = Set.of("schema_version", "candidate_id", "candidate_sha256",
		"contract_sha256", "environment_sha256", "evaluator_policy_sha256", "statistical_policy_sha256",
		"allowance_entry_sha256", "family_ids", "outcome_ledger");
	private static final Set<String> OUTCOME_FIELDS = Set.of("operation_id", "family_id", "prompt_test_id", "verdict",
		"executor_evidence_sha256", "evaluator_evidence_sha256", "completed_at", "ordinal", "previous_sha256",
		"entry_sha256");

	/**
	 * Prevents construction of this static ledger owner.
	 */
	private FormalTrialLedger()
	{
	}

	/**
	 * Creates an empty formal trial ledger for one frozen identity and ordered leaf-family set.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the new manifest path
	 * @param identity the frozen formal-evaluation identity
	 * @param familyIds every leaf family in stable contract order
	 * @throws IOException if a path already exists or the records cannot be created
	 */
	public static void initialize(JsonMapper json, Path manifestPath, Identity identity, List<String> familyIds)
		throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		Objects.requireNonNull(identity, "identity must not be null");
		List<String> families = validateFamilies(familyIds);
		Path normalizedManifest = manifestPath.toAbsolutePath().normalize();
		RecordStore.withLock(normalizedManifest, () ->
		{
			if (Files.exists(normalizedManifest))
				throw new IOException("Formal trial manifest already exists: " + normalizedManifest);
			Path outcomeLedger = outcomeLedgerPath(normalizedManifest);
			if (Files.exists(outcomeLedger))
				throw new IOException("Formal trial outcome ledger already exists: " + outcomeLedger);
			RecordStore.writeText(outcomeLedger, "");
			try
			{
				ObjectNode manifest = json.createObjectNode();
				manifest.put("schema_version", SCHEMA_VERSION);
				manifest.put("candidate_id", identity.candidateId());
				manifest.put("candidate_sha256", identity.candidateFingerprint());
				manifest.put("contract_sha256", identity.contractFingerprint());
				manifest.put("environment_sha256", identity.environmentFingerprint());
				manifest.put("evaluator_policy_sha256", identity.evaluatorPolicyFingerprint());
				manifest.put("statistical_policy_sha256", identity.statisticalPolicyFingerprint());
				manifest.put("allowance_entry_sha256", identity.allowanceEntryFingerprint());
				ArrayNode encodedFamilies = manifest.putArray("family_ids");
				for (String family : families)
					encodedFamilies.add(family);
				manifest.put("outcome_ledger", outcomeLedger.toString());
				RecordStore.write(json, normalizedManifest, manifest);
			}
			catch (IOException e)
			{
				Files.deleteIfExists(outcomeLedger);
				throw e;
			}
		});
	}

	/**
	 * Records one terminal atomic operation outcome or returns its identical retained outcome.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the formal trial manifest
	 * @param outcome the terminal operation outcome
	 * @return the retained outcome and its ledger identity
	 * @throws IOException if the operation changed, the promptTest is not fresh, or evidence cannot be retained
	 */
	public static RetainedOutcome record(JsonMapper json, Path manifestPath, Outcome outcome) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		Objects.requireNonNull(outcome, "outcome must not be null");
		Path normalizedManifest = manifestPath.toAbsolutePath().normalize();
		RetainedOutcome[] result = new RetainedOutcome[1];
		RecordStore.withLock(normalizedManifest, () ->
		{
			Manifest manifest = readManifest(json, normalizedManifest);
			if (!manifest.familyIds.contains(outcome.familyId()))
				throw new IOException("Formal trial names an undeclared family: " + outcome.familyId());
			ScanState state = scan(json, manifest, outcome);
			if (state.operationMatch != null)
			{
				if (!sameOperationEvidence(state.operationMatch.outcome(), outcome))
					throw new IOException("Operation identity already records different formal trial input: " +
						outcome.operationId());
				result[0] = state.operationMatch;
				return;
			}
			if (state.promptTestSeen)
				throw new IOException("Formal promptTest was already attempted: " + outcome.promptTestId());

			RetainedOutcome retained = createRetainedOutcome(json, state.entryCount + 1, outcome,
				state.previousFingerprint);
			append(json, manifest.outcomeLedger, retained);
			result[0] = retained;
		});
		return result[0];
	}

	/**
	 * Compares the determining operation evidence while retaining the original completion time as an output.
	 *
	 * @param retained the retained operation outcome
	 * @param requested the retry outcome
	 * @return whether the retry names the same formal evidence
	 */
	private static boolean sameOperationEvidence(Outcome retained, Outcome requested)
	{
		return retained.operationId().equals(requested.operationId()) &&
			retained.familyId().equals(requested.familyId()) && retained.promptTestId().equals(requested.promptTestId()) &&
			retained.verdict() == requested.verdict() &&
			retained.executorEvidenceFingerprint().equals(requested.executorEvidenceFingerprint()) &&
			retained.evaluatorEvidenceFingerprint().equals(requested.evaluatorEvidenceFingerprint());
	}

	/**
	 * Streams and verifies the formal ledger into a family-bounded decision summary.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the formal trial manifest
	 * @return the immutable family evidence and integrity summary
	 * @throws IOException if the manifest or any outcome is invalid
	 */
	public static Summary read(JsonMapper json, Path manifestPath) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		Manifest manifest = readManifest(json, manifestPath.toAbsolutePath().normalize());
		ScanState state = scan(json, manifest, null);
		return state.summary();
	}

	/**
	 * Finds one retained formal outcome by its idempotent operation identifier.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the formal trial manifest
	 * @param operationId the operation identifier
	 * @return the retained outcome, or empty when the operation has not completed
	 * @throws IOException if the manifest or outcome ledger is invalid
	 */
	public static Optional<RetainedOutcome> find(JsonMapper json, Path manifestPath, String operationId)
		throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		validateIdentifier(operationId, "operationId");
		Manifest manifest = readManifest(json, manifestPath.toAbsolutePath().normalize());
		return Optional.ofNullable(scan(json, manifest, operationId, null).operationMatch);
	}

	/**
	 * Streams the outcome ledger for validation and optional idempotency lookup.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifest the frozen formal manifest
	 * @param requestedOutcome the outcome being recorded, or {@code null} for a read-only summary
	 * @return the completed scan state
	 * @throws IOException if an outcome violates the schema, identity, or hash chain
	 */
	private static ScanState scan(JsonMapper json, Manifest manifest, Outcome requestedOutcome) throws IOException
	{
		String requestedOperationId = null;
		String requestedPromptTestId = null;
		if (requestedOutcome != null)
		{
			requestedOperationId = requestedOutcome.operationId();
			requestedPromptTestId = requestedOutcome.promptTestId();
		}
		return scan(json, manifest, requestedOperationId, requestedPromptTestId);
	}

	/**
	 * Streams the outcome ledger for one optional operation and promptTest lookup.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifest the frozen formal manifest
	 * @param requestedOperationId the operation to find, or {@code null}
	 * @param requestedPromptTestId the promptTest freshness query, or {@code null}
	 * @return the completed scan state
	 * @throws IOException if an outcome violates its schema, identity, or hash chain
	 */
	private static ScanState scan(JsonMapper json, Manifest manifest, String requestedOperationId,
		String requestedPromptTestId) throws IOException
	{
		ScanState state = new ScanState(json, manifest, requestedOperationId, requestedPromptTestId);
		BoundedJsonLines.read(manifest.outcomeLedger, MAXIMUM_OUTCOME_CHARACTERS, "Formal trial ledger", state::accept);
		return state;
	}

	/**
	 * Creates one retained hash-chained outcome.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param ordinal the next one-based ledger ordinal
	 * @param outcome the typed formal outcome
	 * @param previousFingerprint the previous ledger fingerprint
	 * @return the complete retained outcome
	 * @throws IOException if the identity cannot be encoded
	 */
	private static RetainedOutcome createRetainedOutcome(JsonMapper json, int ordinal, Outcome outcome,
		String previousFingerprint) throws IOException
	{
		ObjectNode identity = outcomeIdentity(json, ordinal, outcome, previousFingerprint);
		String entryFingerprint = Fingerprints.ofUtf8(json.writeValueAsString(identity));
		return new RetainedOutcome(ordinal, outcome, previousFingerprint, entryFingerprint);
	}

	/**
	 * Appends and forces one complete formal outcome record.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param outcomeLedger the append-only ledger path
	 * @param retained the retained outcome
	 * @throws IOException if the complete record cannot be written
	 */
	private static void append(JsonMapper json, Path outcomeLedger, RetainedOutcome retained) throws IOException
	{
		ObjectNode encoded = outcomeIdentity(json, retained.ordinal(), retained.outcome(),
			retained.previousFingerprint());
		encoded.put("entry_sha256", retained.entryFingerprint());
		ByteBuffer bytes = StandardCharsets.UTF_8.encode(json.writeValueAsString(encoded) + System.lineSeparator());
		try (FileChannel channel = FileChannel.open(outcomeLedger, APPEND, WRITE))
		{
			while (bytes.hasRemaining())
				channel.write(bytes);
			channel.force(true);
		}
	}

	/**
	 * Builds the canonical identity for one formal outcome.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param ordinal the one-based ledger ordinal
	 * @param outcome the typed formal outcome
	 * @param previousFingerprint the previous ledger fingerprint
	 * @return the canonical JSON identity
	 */
	private static ObjectNode outcomeIdentity(JsonMapper json, int ordinal, Outcome outcome,
		String previousFingerprint)
	{
		ObjectNode result = json.createObjectNode();
		result.put("operation_id", outcome.operationId());
		result.put("family_id", outcome.familyId());
		result.put("prompt_test_id", outcome.promptTestId());
		result.put("verdict", outcome.verdict().externalValue);
		result.put("executor_evidence_sha256", outcome.executorEvidenceFingerprint());
		if (outcome.evaluatorEvidenceFingerprint().isPresent())
			result.put("evaluator_evidence_sha256", outcome.evaluatorEvidenceFingerprint().orElseThrow());
		else
			result.putNull("evaluator_evidence_sha256");
		result.put("completed_at", outcome.completedAt().toString());
		result.put("ordinal", ordinal);
		result.put("previous_sha256", previousFingerprint);
		return result;
	}

	/**
	 * Parses and validates the fixed formal manifest.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param manifestPath the normalized manifest path
	 * @return the validated manifest
	 * @throws IOException if the manifest violates its bounded schema
	 */
	private static Manifest readManifest(JsonMapper json, Path manifestPath) throws IOException
	{
		if (Files.size(manifestPath) > MAXIMUM_MANIFEST_BYTES)
			throw new IOException("Formal trial manifest exceeds its schema size limit: " + manifestPath);
		JsonNode document = RecordStore.read(json, manifestPath);
		if (!(document instanceof ObjectNode manifest))
			throw new IOException("Formal trial manifest is not a JSON object: " + manifestPath);
		if (!new HashSet<>(manifest.propertyNames()).equals(MANIFEST_FIELDS))
			throw new IOException("Formal trial manifest has unsupported or missing fields: " + manifestPath);
		if (!manifest.path("schema_version").isIntegralNumber() ||
			manifest.path("schema_version").asInt(-1) != SCHEMA_VERSION)
		{
			throw new IOException("Unsupported formal trial manifest schema: " + manifestPath);
		}

		Identity identity = new Identity(requiredIdentifier(manifest, "candidate_id", manifestPath),
			requiredFingerprint(manifest, "candidate_sha256", manifestPath),
			requiredFingerprint(manifest, "contract_sha256", manifestPath),
			requiredFingerprint(manifest, "environment_sha256", manifestPath),
			requiredFingerprint(manifest, "evaluator_policy_sha256", manifestPath),
			requiredFingerprint(manifest, "statistical_policy_sha256", manifestPath),
			requiredFingerprint(manifest, "allowance_entry_sha256", manifestPath));
		JsonNode familyNode = manifest.path("family_ids");
		if (!(familyNode instanceof ArrayNode familyArray))
			throw new IOException("Formal trial manifest family_ids is not an array: " + manifestPath);
		List<String> families = new ArrayList<>(familyArray.size());
		for (JsonNode family : familyArray)
		{
			if (!family.isString())
				throw new IOException("Formal trial manifest contains a non-string family: " + manifestPath);
			families.add(family.asString());
		}
		try
		{
			families = validateFamilies(families);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Formal trial manifest contains invalid families: " + manifestPath, e);
		}
		Path outcomeLedger = requiredAbsoluteFile(manifest, "outcome_ledger", manifestPath);
		return new Manifest(identity, families, outcomeLedger);
	}

	/**
	 * Parses one outcome record.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param line the bounded JSON record
	 * @param source the ledger path
	 * @return the parsed outcome object
	 * @throws IOException if the record is malformed
	 */
	private static ObjectNode parseOutcome(JsonMapper json, String line, Path source) throws IOException
	{
		try
		{
			JsonNode parsed = json.readTree(line);
			if (parsed instanceof ObjectNode outcome)
				return outcome;
			throw new IOException("Formal trial outcome is not a JSON object: " + source);
		}
		catch (JacksonException e)
		{
			throw new IOException("Formal trial ledger contains invalid JSON: " + source, e);
		}
	}

	/**
	 * Decodes and verifies one retained outcome.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param encoded the parsed record
	 * @param manifest the frozen formal manifest
	 * @param expectedOrdinal the next required ordinal
	 * @param previousFingerprint the required hash-chain predecessor
	 * @return the validated retained outcome
	 * @throws IOException if the record violates its schema or chain
	 */
	private static RetainedOutcome decodeOutcome(JsonMapper json, ObjectNode encoded, Manifest manifest,
		int expectedOrdinal, String previousFingerprint) throws IOException
	{
		if (!new HashSet<>(encoded.propertyNames()).equals(OUTCOME_FIELDS))
			throw new IOException("Formal trial outcome has unsupported or missing fields: " + manifest.outcomeLedger);
		int ordinal = requiredPositiveInteger(encoded, "ordinal", manifest.outcomeLedger);
		if (ordinal != expectedOrdinal)
			throw new IOException("Formal trial outcome has a nonsequential ordinal: " + manifest.outcomeLedger);
		String familyId = requiredIdentifier(encoded, "family_id", manifest.outcomeLedger);
		if (!manifest.familyIds.contains(familyId))
			throw new IOException("Formal trial names an undeclared family: " + familyId);
		Verdict verdict = Verdict.fromExternalValue(requiredIdentifier(encoded, "verdict", manifest.outcomeLedger),
			manifest.outcomeLedger);
		Optional<String> evaluatorFingerprint = optionalFingerprint(encoded, "evaluator_evidence_sha256",
			manifest.outcomeLedger);
		Outcome outcome = new Outcome(requiredIdentifier(encoded, "operation_id", manifest.outcomeLedger), familyId,
			requiredIdentifier(encoded, "prompt_test_id", manifest.outcomeLedger), verdict,
			requiredFingerprint(encoded, "executor_evidence_sha256", manifest.outcomeLedger), evaluatorFingerprint,
			requiredInstant(encoded, "completed_at", manifest.outcomeLedger));
		String declaredPrevious = requiredIdentifier(encoded, "previous_sha256", manifest.outcomeLedger);
		if (!declaredPrevious.equals(previousFingerprint))
			throw new IOException("Formal trial outcome breaks the ledger hash chain: " + manifest.outcomeLedger);
		String entryFingerprint = requiredFingerprint(encoded, "entry_sha256", manifest.outcomeLedger);
		RetainedOutcome expected = createRetainedOutcome(json, ordinal, outcome, previousFingerprint);
		if (!entryFingerprint.equals(expected.entryFingerprint()))
			throw new IOException("Formal trial outcome fingerprint is invalid: " + manifest.outcomeLedger);
		return expected;
	}

	/**
	 * Validates and snapshots the ordered family identifiers.
	 *
	 * @param familyIds the caller-supplied family identifiers
	 * @return the immutable validated list
	 */
	private static List<String> validateFamilies(List<String> familyIds)
	{
		Objects.requireNonNull(familyIds, "familyIds must not be null");
		if (familyIds.isEmpty())
			throw new IllegalArgumentException("familyIds must not be empty");
		if (familyIds.size() > MAXIMUM_FAMILIES)
			throw new IllegalArgumentException("familyIds exceeds the supported contract limit: " + familyIds.size());
		Set<String> unique = new HashSet<>();
		for (String family : familyIds)
		{
			validateIdentifier(family, "familyId");
			if (!unique.add(family))
				throw new IllegalArgumentException("Duplicate family identifier: " + family);
		}
		return List.copyOf(familyIds);
	}

	/**
	 * Returns one required bounded identifier.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path used in diagnostics
	 * @return the validated value
	 * @throws IOException if the value is absent or invalid
	 */
	private static String requiredIdentifier(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString())
			throw new IOException("Formal trial record has no " + field + ": " + source);
		try
		{
			validateIdentifier(value.asString(), field);
			return value.asString();
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Formal trial record has an invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Returns one required lowercase SHA-256 fingerprint.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path used in diagnostics
	 * @return the fingerprint
	 * @throws IOException if the value is invalid
	 */
	private static String requiredFingerprint(ObjectNode object, String field, Path source) throws IOException
	{
		String result = requiredIdentifier(object, field, source);
		if (!SHA_256.matcher(result).matches())
			throw new IOException("Formal trial record has an invalid " + field + ": " + source);
		return result;
	}

	/**
	 * Returns an optional lowercase SHA-256 fingerprint.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path used in diagnostics
	 * @return the optional fingerprint
	 * @throws IOException if a present value is invalid
	 */
	private static Optional<String> optionalFingerprint(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (value.isNull())
			return Optional.empty();
		return Optional.of(requiredFingerprint(object, field, source));
	}

	/**
	 * Returns one required positive integer.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path used in diagnostics
	 * @return the positive value
	 * @throws IOException if the value is invalid
	 */
	private static int requiredPositiveInteger(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1)
			throw new IOException("Formal trial record has an invalid " + field + ": " + source);
		return value.asInt();
	}

	/**
	 * Returns one required ISO-8601 instant.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path used in diagnostics
	 * @return the parsed instant
	 * @throws IOException if the value is invalid
	 */
	private static Instant requiredInstant(ObjectNode object, String field, Path source) throws IOException
	{
		String value = requiredIdentifier(object, field, source);
		try
		{
			return Instant.parse(value);
		}
		catch (DateTimeException e)
		{
			throw new IOException("Formal trial record has an invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Returns one required absolute regular-file path.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path used in diagnostics
	 * @return the normalized path
	 * @throws IOException if the path is invalid or unavailable
	 */
	private static Path requiredAbsoluteFile(ObjectNode object, String field, Path source) throws IOException
	{
		try
		{
			Path result = Path.of(requiredIdentifier(object, field, source)).normalize();
			if (!result.isAbsolute() || !Files.isRegularFile(result))
				throw new IOException("Formal trial record names an unavailable " + field + ": " + source);
			return result;
		}
		catch (java.nio.file.InvalidPathException e)
		{
			throw new IOException("Formal trial record has an invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Returns the outcome-ledger path beside its manifest.
	 *
	 * @param manifestPath the manifest path
	 * @return the sibling outcome-ledger path
	 * @throws IOException if the manifest has no filename
	 */
	private static Path outcomeLedgerPath(Path manifestPath) throws IOException
	{
		Path filename = manifestPath.getFileName();
		if (filename == null)
			throw new IOException("Formal trial manifest has no filename: " + manifestPath);
		return manifestPath.resolveSibling(filename + ".outcomes.jsonl");
	}

	/**
	 * Validates one bounded identifier without changing its identity.
	 *
	 * @param value the identifier
	 * @param name the parameter name
	 */
	private static void validateIdentifier(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank() || value.length() > MAXIMUM_IDENTIFIER_CHARACTERS)
			throw new IllegalArgumentException(name + " must contain 1 to " + MAXIMUM_IDENTIFIER_CHARACTERS +
				" characters");
	}

	/**
	 * Validates one lowercase SHA-256 fingerprint.
	 *
	 * @param value the fingerprint
	 * @param name the parameter name
	 */
	private static void validateFingerprint(String value, String name)
	{
		validateIdentifier(value, name);
		if (!SHA_256.matcher(value).matches())
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 fingerprint");
	}

	/**
	 * Names whether a formal operation produced a semantic pass, semantic failure, or invalid infrastructure evidence.
	 */
	public enum Verdict
	{
		PASS("pass"),
		FAIL("fail"),
		INFRASTRUCTURE_FAILURE("infrastructure-failure");

		private final String externalValue;

		/**
		 * Creates one persisted verdict.
		 *
		 * @param externalValue the persisted value
		 */
		Verdict(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Parses one exact persisted verdict.
		 *
		 * @param value the untyped persisted value
		 * @param source the source path used in diagnostics
		 * @return the closed verdict
		 * @throws IOException if the value is unsupported
		 */
		private static Verdict fromExternalValue(String value, Path source) throws IOException
		{
			return switch (value)
			{
				case "pass" -> PASS;
				case "fail" -> FAIL;
				case "infrastructure-failure" -> INFRASTRUCTURE_FAILURE;
				default -> throw new IOException("Unsupported formal trial verdict " + value + ": " + source);
			};
		}
	}

	/**
	 * Holds the frozen formal-evaluation identity.
	 *
	 * @param candidateId the immutable candidate identifier
	 * @param candidateFingerprint the immutable prompt candidate fingerprint
	 * @param contractFingerprint the certification-contract fingerprint
	 * @param environmentFingerprint the recorded environment fingerprint
	 * @param evaluatorPolicyFingerprint the evaluator-policy fingerprint
	 * @param statisticalPolicyFingerprint the family-sequential policy fingerprint
	 * @param allowanceEntryFingerprint the candidate allowance entry fingerprint
	 */
	public record Identity(String candidateId, String candidateFingerprint, String contractFingerprint,
		String environmentFingerprint,
		String evaluatorPolicyFingerprint, String statisticalPolicyFingerprint, String allowanceEntryFingerprint)
	{
		/**
		 * Validates one frozen identity.
		 *
		 * @param candidateId the immutable candidate identifier
		 * @param candidateFingerprint the immutable prompt candidate fingerprint
		 * @param contractFingerprint the certification-contract fingerprint
		 * @param environmentFingerprint the recorded environment fingerprint
		 * @param evaluatorPolicyFingerprint the evaluator-policy fingerprint
		 * @param statisticalPolicyFingerprint the family-sequential policy fingerprint
		 * @param allowanceEntryFingerprint the candidate allowance entry fingerprint
		 */
		public Identity
		{
			validateIdentifier(candidateId, "candidateId");
			validateFingerprint(candidateFingerprint, "candidateFingerprint");
			validateFingerprint(contractFingerprint, "contractFingerprint");
			validateFingerprint(environmentFingerprint, "environmentFingerprint");
			validateFingerprint(evaluatorPolicyFingerprint, "evaluatorPolicyFingerprint");
			validateFingerprint(statisticalPolicyFingerprint, "statisticalPolicyFingerprint");
			validateFingerprint(allowanceEntryFingerprint, "allowanceEntryFingerprint");
		}
	}

	/**
	 * Holds one terminal atomic formal operation outcome.
	 *
	 * @param operationId the idempotent operation identifier
	 * @param familyId the predeclared leaf family
	 * @param promptTestId the fresh formal promptTest identifier
	 * @param verdict the typed terminal verdict
	 * @param executorEvidenceFingerprint the retained executor evidence fingerprint
	 * @param evaluatorEvidenceFingerprint the evaluator evidence, absent only for infrastructure failure
	 * @param completedAt the terminal operation time
	 */
	public record Outcome(String operationId, String familyId, String promptTestId, Verdict verdict,
		String executorEvidenceFingerprint, Optional<String> evaluatorEvidenceFingerprint, Instant completedAt)
	{
		/**
		 * Validates one formal outcome.
		 *
		 * @param operationId the idempotent operation identifier
		 * @param familyId the predeclared leaf family
		 * @param promptTestId the fresh formal promptTest identifier
		 * @param verdict the typed terminal verdict
		 * @param executorEvidenceFingerprint the retained executor evidence fingerprint
		 * @param evaluatorEvidenceFingerprint the evaluator evidence, absent only for infrastructure failure
		 * @param completedAt the terminal operation time
		 */
		public Outcome
		{
			validateIdentifier(operationId, "operationId");
			validateIdentifier(familyId, "familyId");
			validateIdentifier(promptTestId, "promptTestId");
			Objects.requireNonNull(verdict, "verdict must not be null");
			validateFingerprint(executorEvidenceFingerprint, "executorEvidenceFingerprint");
			Objects.requireNonNull(evaluatorEvidenceFingerprint, "evaluatorEvidenceFingerprint must not be null");
			evaluatorEvidenceFingerprint.ifPresent(value -> validateFingerprint(value,
				"evaluatorEvidenceFingerprint"));
			boolean evaluatorRequired = verdict != Verdict.INFRASTRUCTURE_FAILURE;
			if (evaluatorRequired != evaluatorEvidenceFingerprint.isPresent())
				throw new IllegalArgumentException("Evaluator evidence presence does not match verdict: " + verdict);
			Objects.requireNonNull(completedAt, "completedAt must not be null");
		}
	}

	/**
	 * Holds one outcome and its append-only ledger identity.
	 *
	 * @param ordinal the one-based ledger position
	 * @param outcome the typed formal outcome
	 * @param previousFingerprint the previous ledger fingerprint
	 * @param entryFingerprint this outcome's fingerprint
	 */
	public record RetainedOutcome(int ordinal, Outcome outcome, String previousFingerprint, String entryFingerprint)
	{
	}

	/**
	 * Holds the bounded formal evidence projection used by the decision engine.
	 *
	 * @param identity the frozen formal identity
	 * @param evidence every family outcome count in contract order
	 * @param entryCount the number of retained atomic operations
	 * @param infrastructureFailureCount the number of operations excluded from Bernoulli evidence
	 * @param ledgerFingerprint the last hash-chain entry, or {@code ROOT} for an empty ledger
	 * @param evidenceFingerprints every retained executor and evaluator evidence fingerprint
	 * @param promptTestIdentifiers every attempted formal promptTest identifier
	 */
	public record Summary(Identity identity, List<FamilyCertification.Evidence> evidence, int entryCount,
		int infrastructureFailureCount, String ledgerFingerprint, List<String> evidenceFingerprints,
		List<String> promptTestIdentifiers)
	{
		/**
		 * Snapshots the family evidence.
		 *
		 * @param identity the frozen formal identity
		 * @param evidence every family outcome count in contract order
		 * @param entryCount the number of retained atomic operations
		 * @param infrastructureFailureCount the number of operations excluded from Bernoulli evidence
		 * @param ledgerFingerprint the last hash-chain entry, or {@code ROOT} for an empty ledger
		 * @param evidenceFingerprints every retained executor and evaluator evidence fingerprint
		 * @param promptTestIdentifiers every attempted formal promptTest identifier
		 */
		public Summary
		{
			evidence = List.copyOf(evidence);
			evidenceFingerprints = List.copyOf(evidenceFingerprints);
			promptTestIdentifiers = List.copyOf(promptTestIdentifiers);
		}
	}

	/**
	 * Holds the validated fixed manifest.
	 *
	 * @param identity the frozen formal identity
	 * @param familyIds the ordered leaf families
	 * @param outcomeLedger the append-only outcome ledger
	 */
	private record Manifest(Identity identity, List<String> familyIds, Path outcomeLedger)
	{
	}

	/**
	 * Accumulates one bounded summary while the outcome ledger is streamed.
	 */
	private static final class ScanState
	{
		private final JsonMapper json;
		private final Manifest manifest;
		private final String requestedOperationId;
		private final String requestedPromptTestId;
		private final Map<String, int[]> counts = new LinkedHashMap<>();
		private final Set<String> operationIds = new HashSet<>();
		private final Set<String> promptTestIds = new TreeSet<>();
		private final Set<String> evidenceFingerprints = new TreeSet<>();
		private int entryCount;
		private int infrastructureFailureCount;
		private String previousFingerprint = ROOT_FINGERPRINT;
		private RetainedOutcome operationMatch;
		private boolean promptTestSeen;

		/**
		 * Creates a stream accumulator.
		 *
		 * @param json the ledger-owned JSON mapper
		 * @param manifest the frozen formal manifest
		 * @param requestedOperationId the operation being found, or {@code null}
		 * @param requestedPromptTestId the promptTest freshness query, or {@code null}
		 */
		private ScanState(JsonMapper json, Manifest manifest, String requestedOperationId, String requestedPromptTestId)
		{
			this.json = json;
			this.manifest = manifest;
			this.requestedOperationId = requestedOperationId;
			this.requestedPromptTestId = requestedPromptTestId;
			for (String family : manifest.familyIds)
				counts.put(family, new int[2]);
		}

		/**
		 * Validates and accumulates one outcome record.
		 *
		 * @param line the bounded JSON record
		 * @param lineNumber the one-based source line
		 * @throws IOException if the record violates its schema or chain
		 */
		private void accept(String line, int lineNumber) throws IOException
		{
			if (line.isBlank())
				throw new IOException("Formal trial ledger contains a blank record: " + manifest.outcomeLedger +
					" (line " + lineNumber + ")");
			ObjectNode encoded = parseOutcome(json, line, manifest.outcomeLedger);
			RetainedOutcome retained = decodeOutcome(json, encoded, manifest, entryCount + 1, previousFingerprint);
			++entryCount;
			previousFingerprint = retained.entryFingerprint();
			Outcome outcome = retained.outcome();
			evidenceFingerprints.add(outcome.executorEvidenceFingerprint());
			outcome.evaluatorEvidenceFingerprint().ifPresent(evidenceFingerprints::add);
			if (!operationIds.add(outcome.operationId()))
				throw new IOException("Formal trial ledger repeats operation identity: " + outcome.operationId());
			if (!promptTestIds.add(outcome.promptTestId()))
				throw new IOException("Formal trial ledger repeats promptTest identity: " + outcome.promptTestId());
			if (outcome.verdict() == Verdict.INFRASTRUCTURE_FAILURE)
				++infrastructureFailureCount;
			else
			{
				int[] familyCounts = counts.get(outcome.familyId());
				++familyCounts[1];
				if (outcome.verdict() == Verdict.PASS)
					++familyCounts[0];
			}
			if (outcome.operationId().equals(requestedOperationId))
				operationMatch = retained;
			if (outcome.promptTestId().equals(requestedPromptTestId))
				promptTestSeen = true;
		}

		/**
		 * Returns the immutable family evidence summary.
		 *
		 * @return the bounded summary
		 */
		private Summary summary()
		{
			List<FamilyCertification.Evidence> evidence = new ArrayList<>(counts.size());
			for (Map.Entry<String, int[]> family : counts.entrySet())
			{
				int[] familyCounts = family.getValue();
				evidence.add(new FamilyCertification.Evidence(family.getKey(), familyCounts[0], familyCounts[1]));
			}
			return new Summary(manifest.identity, evidence, entryCount, infrastructureFailureCount,
				previousFingerprint, List.copyOf(evidenceFingerprints), List.copyOf(promptTestIds));
		}
	}
}
