package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.BoundedJsonLines;
import io.github.cowwoc.cat.common.RecordStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Owns the durable evidence-pool identity partition for one certification contract.
 *
 * <p>A promptTest is reserved before model inference. Exact retries reuse the retained reservation, while an identity
 * collision across development, readiness, and formal pools is rejected without modifying the ledger.</p>
 */
public final class PromptTestIdentityLedger
{
	private static final int SCHEMA_VERSION = 1;
	private static final int MAXIMUM_RECORD_CHARACTERS = 32 * 1024;
	private static final int MAXIMUM_IDENTITIES = 1_000_000;
	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
	private static final Set<String> HEADER_FIELDS = Set.of("schema_version", "contract_sha256");
	private static final Set<String> ENTRY_FIELDS = Set.of("pool", "instance_id", "seed", "content_sha256",
		"execution_id", "ordinal", "previous_sha256", "entry_sha256");

	/**
	 * Creates one empty contract-bound identity ledger.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param ledgerPath the new append-only ledger
	 * @param contractFingerprint the frozen certification-contract fingerprint
	 * @throws IOException if the path exists or the ledger cannot be created
	 */
	public static void initialize(JsonMapper json, Path ledgerPath, String contractFingerprint) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(ledgerPath, "ledgerPath must not be null");
		contractFingerprint = requireFingerprint(contractFingerprint, "contractFingerprint");
		Path normalized = ledgerPath.toAbsolutePath().normalize();
		String retainedContractFingerprint = contractFingerprint;
		RecordStore.withLock(normalized, () ->
		{
			if (Files.exists(normalized))
				throw new IOException("PromptTest identity ledger already exists: " + normalized);
			ObjectNode header = json.createObjectNode();
			header.put("schema_version", SCHEMA_VERSION);
			header.put("contract_sha256", retainedContractFingerprint);
			RecordStore.writeText(normalized, json.writeValueAsString(header) + System.lineSeparator());
		});
	}

	/**
	 * Reserves one identity before inference or returns its exact retained reservation.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param ledgerPath the contract identity ledger
	 * @param promptTest the proposed prompt-test identity
	 * @return the retained, newly reserved, or blocked result
	 * @throws IOException if retained evidence is malformed or an operation identifier changes input
	 */
	public static Reservation reserve(JsonMapper json, Path ledgerPath, PromptTestPoolPartition.Identity promptTest)
		throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(ledgerPath, "ledgerPath must not be null");
		Objects.requireNonNull(promptTest, "promptTest must not be null");
		Path normalized = ledgerPath.toAbsolutePath().normalize();
		Reservation[] result = new Reservation[1];
		RecordStore.withLock(normalized, () ->
		{
			ScanState state = scan(json, normalized);
			PromptTestPoolPartition.Identity operationMatch = state.byExecutionIdentifier.get(
				promptTest.executionIdentifier());
			if (operationMatch != null)
			{
				if (!operationMatch.equals(promptTest))
				{
					throw new IOException("PromptTest operation identifier already reserves different prompt-test input: " +
						promptTest.executionIdentifier());
				}
				result[0] = new Reservation(Status.RETAINED, List.of());
				return;
			}

			List<PromptTestPoolPartition.Identity> proposed = new ArrayList<>(state.identities);
			proposed.add(promptTest);
			PromptTestPoolPartition.Result partition = PromptTestPoolPartition.validate(proposed);
			if (partition.status() == PromptTestPoolPartition.Status.BLOCKED)
			{
				result[0] = new Reservation(Status.BLOCKED, partition.collisions());
				return;
			}

			String fingerprint = append(json, normalized, state.identities.size() + 1, promptTest,
				state.lastFingerprint);
			state.lastFingerprint = fingerprint;
			result[0] = new Reservation(Status.RESERVED, List.of());
		});
		return result[0];
	}

	/**
	 * Reads and verifies the complete durable identity partition.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param ledgerPath the contract identity ledger
	 * @return the contract identity, ordered promptTests, and final hash-chain identity
	 * @throws IOException if the ledger is absent, incomplete, malformed, or internally inconsistent
	 */
	public static Summary read(JsonMapper json, Path ledgerPath) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(ledgerPath, "ledgerPath must not be null");
		ScanState state = scan(json, ledgerPath.toAbsolutePath().normalize());
		return new Summary(state.contractFingerprint, state.identities, state.lastFingerprint);
	}

	/**
	 * Streams and validates one complete identity ledger.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param ledgerPath the normalized ledger path
	 * @return the validated scan state
	 * @throws IOException if any retained record violates the ledger contract
	 */
	private static ScanState scan(JsonMapper json, Path ledgerPath) throws IOException
	{
		ScanState state = new ScanState(json, ledgerPath);
		BoundedJsonLines.read(ledgerPath, MAXIMUM_RECORD_CHARACTERS, "PromptTest identity ledger", state::accept);
		if (!state.headerRead)
			throw new IOException("PromptTest identity ledger is empty: " + ledgerPath);
		return state;
	}

	/**
	 * Appends and forces one complete hash-chained identity record.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param ledgerPath the ledger path
	 * @param ordinal the next one-based identity ordinal
	 * @param promptTest the reserved promptTest
	 * @param previousFingerprint the preceding chain fingerprint
	 * @return the appended entry fingerprint
	 * @throws IOException if the identity cannot be encoded or forced to storage
	 */
	private static String append(JsonMapper json, Path ledgerPath, int ordinal,
		PromptTestPoolPartition.Identity promptTest, String previousFingerprint) throws IOException
	{
		ObjectNode identity = entryIdentity(json, ordinal, promptTest, previousFingerprint);
		String fingerprint = Fingerprints.ofUtf8(json.writeValueAsString(identity));
		identity.put("entry_sha256", fingerprint);
		ByteBuffer bytes = StandardCharsets.UTF_8.encode(json.writeValueAsString(identity) + System.lineSeparator());
		try (FileChannel channel = FileChannel.open(ledgerPath, APPEND, WRITE))
		{
			while (bytes.hasRemaining())
				channel.write(bytes);
			channel.force(true);
		}
		return fingerprint;
	}

	/**
	 * Builds the canonical hash identity for one entry.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param ordinal the one-based entry ordinal
	 * @param promptTest the prompt-test identity
	 * @param previousFingerprint the preceding chain fingerprint
	 * @return the canonical entry identity
	 */
	private static ObjectNode entryIdentity(JsonMapper json, int ordinal, PromptTestPoolPartition.Identity promptTest,
		String previousFingerprint)
	{
		ObjectNode result = json.createObjectNode();
		result.put("pool", promptTest.pool().name());
		result.put("instance_id", promptTest.instanceIdentifier());
		result.put("seed", promptTest.seed());
		result.put("content_sha256", promptTest.generatedContentFingerprint());
		result.put("execution_id", promptTest.executionIdentifier());
		result.put("ordinal", ordinal);
		result.put("previous_sha256", previousFingerprint);
		return result;
	}

	/**
	 * Parses one strict JSON object record.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param record the bounded record
	 * @param ledgerPath the source ledger
	 * @param lineNumber the one-based source line
	 * @return the parsed object
	 * @throws IOException if the record is not a JSON object
	 */
	private static ObjectNode parseObject(JsonMapper json, String record, Path ledgerPath, int lineNumber)
		throws IOException
	{
		try
		{
			JsonNode parsed = json.readTree(record);
			if (parsed instanceof ObjectNode object)
				return object;
		}
		catch (JacksonException e)
		{
			throw new IOException("Invalid prompt_test identity JSON at " + ledgerPath + ":" + lineNumber, e);
		}
		throw new IOException("PromptTest identity record is not an object at " + ledgerPath + ":" + lineNumber);
	}

	/**
	 * Reads one required nonblank string field.
	 *
	 * @param object the containing object
	 * @param field the field name
	 * @param source the source description
	 * @return the text value
	 * @throws IOException if the field is missing or invalid
	 */
	private static String requiredText(ObjectNode object, String field, String source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("PromptTest identity ledger has invalid " + field + " at " + source);
		return value.asString();
	}

	/**
	 * Validates one SHA-256 fingerprint.
	 *
	 * @param value the proposed fingerprint
	 * @param name the value name
	 * @return the unchanged fingerprint
	 */
	private static String requireFingerprint(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (!SHA_256.matcher(value).matches())
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
		return value;
	}

	/**
	 * Names one reservation result.
	 */
	public enum Status
	{
		RESERVED,
		RETAINED,
		BLOCKED
	}

	/**
	 * Holds one reservation result.
	 *
	 * @param status the reservation status
	 * @param collisions cross-pool collisions when blocked
	 */
	public record Reservation(Status status, List<PromptTestPoolPartition.Collision> collisions)
	{
		/**
		 * Validates one reservation result.
		 *
		 * @param status the reservation status
		 * @param collisions cross-pool collisions when blocked
		 */
		public Reservation
		{
			Objects.requireNonNull(status, "status must not be null");
			collisions = List.copyOf(collisions);
			if ((status == Status.BLOCKED) != !collisions.isEmpty())
				throw new IllegalArgumentException("Only a blocked reservation must contain collisions");
		}
	}

	/**
	 * Holds one verified identity partition snapshot.
	 *
	 * @param contractFingerprint the frozen certification-contract fingerprint
	 * @param identities every retained identity in append order
	 * @param ledgerFingerprint the final hash-chain fingerprint
	 */
	public record Summary(String contractFingerprint, List<PromptTestPoolPartition.Identity> identities,
		String ledgerFingerprint)
	{
		/**
		 * Validates one verified snapshot.
		 *
		 * @param contractFingerprint the frozen certification-contract fingerprint
		 * @param identities every retained identity in append order
		 * @param ledgerFingerprint the final hash-chain fingerprint
		 */
		public Summary
		{
			contractFingerprint = requireFingerprint(contractFingerprint, "contractFingerprint");
			identities = List.copyOf(identities);
			ledgerFingerprint = requireFingerprint(ledgerFingerprint, "ledgerFingerprint");
		}
	}

	/**
	 * Accumulates one streaming ledger verification.
	 */
	private static final class ScanState
	{
		private final JsonMapper json;
		private final Path ledgerPath;
		private final List<PromptTestPoolPartition.Identity> identities = new ArrayList<>();
		private final Map<String, PromptTestPoolPartition.Identity> byExecutionIdentifier = new HashMap<>();
		private boolean headerRead;
		private String contractFingerprint;
		private String lastFingerprint;

		/**
		 * Creates one empty scan.
		 *
		 * @param json the ledger-owned JSON mapper
		 * @param ledgerPath the source ledger
		 */
		private ScanState(JsonMapper json, Path ledgerPath)
		{
			this.json = json;
			this.ledgerPath = ledgerPath;
		}

		/**
		 * Accepts one header or identity record.
		 *
		 * @param record the bounded JSON record
		 * @param lineNumber the source line number
		 * @throws IOException if the record violates the ledger contract
		 */
		private void accept(String record, int lineNumber) throws IOException
		{
			ObjectNode object = parseObject(json, record, ledgerPath, lineNumber);
			if (!headerRead)
			{
				readHeader(object, lineNumber);
				return;
			}
			readEntry(object, lineNumber);
		}

		/**
		 * Reads the first contract header.
		 *
		 * @param header the header object
		 * @param lineNumber the source line number
		 * @throws IOException if the header is invalid
		 */
		private void readHeader(ObjectNode header, int lineNumber) throws IOException
		{
			String source = ledgerPath + ":" + lineNumber;
			if (!new HashSet<>(header.propertyNames()).equals(HEADER_FIELDS) ||
				!header.path("schema_version").isIntegralNumber() ||
				header.path("schema_version").asInt(-1) != SCHEMA_VERSION)
			{
				throw new IOException("Unsupported prompt_test identity ledger header at " + source);
			}
			try
			{
				contractFingerprint = requireFingerprint(requiredText(header, "contract_sha256", source),
					"contractFingerprint");
			}
			catch (IllegalArgumentException e)
			{
				throw new IOException("PromptTest identity ledger has invalid contract_sha256 at " + source, e);
			}
			lastFingerprint = Fingerprints.ofUtf8(json.writeValueAsString(header));
			headerRead = true;
		}

		/**
		 * Reads and verifies one hash-chained identity entry.
		 *
		 * @param entry the entry object
		 * @param lineNumber the source line number
		 * @throws IOException if the entry is invalid
		 */
		private void readEntry(ObjectNode entry, int lineNumber) throws IOException
		{
			String source = ledgerPath + ":" + lineNumber;
			if (!new HashSet<>(entry.propertyNames()).equals(ENTRY_FIELDS))
				throw new IOException("PromptTest identity entry has unsupported or missing fields at " + source);
			if (!entry.path("ordinal").isIntegralNumber() || entry.path("ordinal").asInt(-1) != identities.size() + 1)
				throw new IOException("PromptTest identity entry has an invalid ordinal at " + source);
			String previous = requiredText(entry, "previous_sha256", source);
			if (!previous.equals(lastFingerprint))
				throw new IOException("PromptTest identity hash chain is broken at " + source);
			String retainedFingerprint = requiredText(entry, "entry_sha256", source);
			ObjectNode identity = entry.deepCopy();
			identity.remove("entry_sha256");
			String calculatedFingerprint = Fingerprints.ofUtf8(json.writeValueAsString(identity));
			if (!retainedFingerprint.equals(calculatedFingerprint))
				throw new IOException("PromptTest identity entry fingerprint is invalid at " + source);

			PromptTestPoolPartition.Identity promptTest;
			try
			{
				promptTest = new PromptTestPoolPartition.Identity(
					PromptTestPoolPartition.Pool.valueOf(requiredText(entry, "pool", source)),
					requiredText(entry, "instance_id", source), requiredText(entry, "seed", source),
					requiredText(entry, "content_sha256", source), requiredText(entry, "execution_id", source));
			}
			catch (IllegalArgumentException e)
			{
				throw new IOException("PromptTest identity entry has an unsupported value at " + source, e);
			}
			if (byExecutionIdentifier.putIfAbsent(promptTest.executionIdentifier(), promptTest) != null)
				throw new IOException("PromptTest identity ledger repeats execution_id at " + source);
			identities.add(promptTest);
			if (identities.size() > MAXIMUM_IDENTITIES)
				throw new IOException("PromptTest identity ledger exceeds its entry limit: " + ledgerPath);
			PromptTestPoolPartition.Result partition = PromptTestPoolPartition.validate(identities);
			if (partition.status() == PromptTestPoolPartition.Status.BLOCKED)
				throw new IOException("PromptTest identity ledger contains a cross-pool collision at " + source);
			lastFingerprint = retainedFingerprint;
		}
	}

	/**
	 * Prevents construction of this static ledger owner.
	 */
	private PromptTestIdentityLedger()
	{
	}
}
