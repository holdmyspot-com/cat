package io.github.cowwoc.cat.common.optimization;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.BoundedJsonLines;
import io.github.cowwoc.cat.common.RecordStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records local evidence deletions without deleting or rewriting the historical evidence that references them.
 */
public final class DeletionReceiptLedger
{
	private static final int MAXIMUM_LINE_CHARACTERS = 64 * 1024;
	private static final String ROOT = "ROOT";
	private static final Set<String> FIELDS = Set.of("schema_version", "artifact_sha256", "deleted_at", "reason",
		"affected_operation_ids", "previous_sha256", "entry_sha256");

	/**
	 * Records one deletion or returns its identical retained receipt.
	 *
	 * @param json the JSON codec
	 * @param projectRoot the project containing CAT history
	 * @param artifactFingerprint the deleted artifact fingerprint
	 * @param deletedAt the deletion instant
	 * @param reason the nonblank deletion reason
	 * @param affectedOperationIds ordered affected operation identities
	 * @return the retained deletion receipt
	 * @throws IOException if the ledger is malformed or the record conflicts with retained history
	 */
	public static Entry record(JsonMapper json, Path projectRoot, String artifactFingerprint, Instant deletedAt,
		String reason, List<String> affectedOperationIds) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(deletedAt, "deletedAt must not be null");
		artifactFingerprint = fingerprint(artifactFingerprint, "artifactFingerprint");
		reason = text(reason, "reason");
		affectedOperationIds = operationIds(affectedOperationIds);
		Path ledger = ledger(projectRoot);
		Entry[] answer = new Entry[1];
		String retainedArtifact = artifactFingerprint;
		String retainedReason = reason;
		List<String> retainedIds = affectedOperationIds;
		RecordStore.withLock(ledger, () ->
		{
			Scan scan = scan(json, ledger, entry ->
			{
				if (entry.artifactFingerprint().equals(retainedArtifact) && entry.affectedOperationIds().equals(retainedIds))
				{
					if (!entry.reason().equals(retainedReason))
						throw new IOException("Deletion receipt conflicts with retained reason: " + ledger);
					answer[0] = entry;
				}
			});
			if (answer[0] != null)
				return;
			Entry entry = create(json, retainedArtifact, deletedAt, retainedReason, retainedIds, scan.lastFingerprint());
			append(json, ledger, entry);
			answer[0] = entry;
		});
		return answer[0];
	}

	/**
	 * Finds one retained deletion receipt by artifact and ordered operation identities.
	 *
	 * @param json the JSON codec
	 * @param projectRoot the project containing CAT history
	 * @param artifactFingerprint the artifact fingerprint
	 * @param affectedOperationIds ordered affected operation identities
	 * @return the matching retained receipt, if one exists
	 * @throws IOException if the ledger is malformed
	 */
	public static java.util.Optional<Entry> find(JsonMapper json, Path projectRoot, String artifactFingerprint,
		List<String> affectedOperationIds) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		artifactFingerprint = fingerprint(artifactFingerprint, "artifactFingerprint");
		affectedOperationIds = operationIds(affectedOperationIds);
		Entry[] answer = new Entry[1];
		String retainedArtifact = artifactFingerprint;
		List<String> retainedIds = affectedOperationIds;
		scan(json, ledger(projectRoot), entry ->
		{
			if (entry.artifactFingerprint().equals(retainedArtifact) && entry.affectedOperationIds().equals(retainedIds))
				answer[0] = entry;
		});
		return java.util.Optional.ofNullable(answer[0]);
	}

	/**
	 * Streams each verified deletion receipt in ledger order.
	 *
	 * @param json the JSON codec
	 * @param projectRoot the project containing CAT history
	 * @param consumer the receipt consumer
	 * @throws IOException if the ledger is malformed
	 */
	public static void read(JsonMapper json, Path projectRoot, Consumer<Entry> consumer) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(consumer, "consumer must not be null");
		scan(json, ledger(projectRoot), consumer::accept);
	}

	/**
	 * Streams and verifies the ledger while retaining only its final hash.
	 *
	 * @param json the JSON codec
	 * @param ledger the ledger path
	 * @param consumer the verified receipt consumer
	 * @return the final retained hash
	 * @throws IOException if a receipt is malformed
	 */
	private static Scan scan(JsonMapper json, Path ledger, EntryConsumer consumer) throws IOException
	{
		if (!Files.exists(ledger))
			return new Scan(ROOT);
		String[] previous = {ROOT};
		BoundedJsonLines.read(ledger, MAXIMUM_LINE_CHARACTERS, "Deletion receipt ledger", (line, lineNumber) ->
		{
			Entry entry = parse(json, line, ledger, lineNumber);
			if (!entry.previousFingerprint().equals(previous[0]))
				throw new IOException("Deletion receipt breaks its hash chain at line " + lineNumber + ": " + ledger);
			Entry expected = create(json, entry.artifactFingerprint(), entry.deletedAt(), entry.reason(),
				entry.affectedOperationIds(), previous[0]);
			if (!expected.entryFingerprint().equals(entry.entryFingerprint()))
				throw new IOException("Deletion receipt fingerprint is invalid at line " + lineNumber + ": " + ledger);
			previous[0] = entry.entryFingerprint();
			consumer.accept(entry);
		});
		return new Scan(previous[0]);
	}

	/**
	 * Parses one strict JSONL receipt.
	 *
	 * @param json the JSON codec
	 * @param line the JSONL record
	 * @param ledger the source ledger
	 * @param lineNumber the one-based source line
	 * @return the parsed receipt
	 * @throws IOException if the record is invalid
	 */
	private static Entry parse(JsonMapper json, String line, Path ledger, int lineNumber) throws IOException
	{
		try
		{
			JsonNode value = json.readTree(line);
			if (!(value instanceof ObjectNode object) || !new HashSet<>(object.propertyNames()).equals(FIELDS))
				throw new IOException("Deletion receipt has unsupported fields at line " + lineNumber + ": " + ledger);
			if (object.path("schema_version").asInt(-1) != 1)
				throw new IOException("Deletion receipt schema is unsupported at line " + lineNumber + ": " + ledger);
			JsonNode ids = object.path("affected_operation_ids");
			if (!(ids instanceof ArrayNode array))
				throw new IOException("Deletion receipt operation identities are invalid at line " + lineNumber +
					": " + ledger);
			List<String> values = new ArrayList<>();
			for (JsonNode id : array)
				values.add(id.asString());
			return new Entry(object.path("artifact_sha256").asString(), Instant.parse(object.path("deleted_at").asString()),
				object.path("reason").asString(), values, object.path("previous_sha256").asString(),
				object.path("entry_sha256").asString());
		}
		catch (RuntimeException e)
		{
			throw new IOException("Invalid deletion receipt at line " + lineNumber + ": " + ledger, e);
		}
	}

	/**
	 * Creates one receipt with a content-derived entry fingerprint.
	 *
	 * @param json the JSON codec
	 * @param artifact the deleted artifact fingerprint
	 * @param deletedAt the deletion time
	 * @param reason the deletion reason
	 * @param ids affected operation identities
	 * @param previous the preceding hash
	 * @return the complete receipt
	 * @throws IOException if canonical encoding fails
	 */
	private static Entry create(JsonMapper json, String artifact, Instant deletedAt, String reason, List<String> ids,
		String previous) throws IOException
	{
		ObjectNode node = node(json, artifact, deletedAt, reason, ids, previous, null);
		return new Entry(artifact, deletedAt, reason, ids, previous,
			Fingerprints.ofUtf8(json.writeValueAsString(node)));
	}

	/**
	 * Appends and forces one canonical JSONL receipt.
	 *
	 * @param json the JSON codec
	 * @param ledger the destination ledger
	 * @param entry the complete receipt
	 * @throws IOException if append or force fails
	 */
	private static void append(JsonMapper json, Path ledger, Entry entry) throws IOException
	{
		Files.createDirectories(ledger.getParent());
		String line = json.writeValueAsString(node(json, entry.artifactFingerprint(), entry.deletedAt(), entry.reason(),
			entry.affectedOperationIds(), entry.previousFingerprint(), entry.entryFingerprint())) + "\n";
		try (FileChannel channel = FileChannel.open(ledger, java.nio.file.StandardOpenOption.CREATE,
			java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND))
		{
			channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
			channel.force(true);
		}
	}

	/**
	 * Returns the strict insertion-ordered receipt document.
	 *
	 * @param json the JSON codec
	 * @param artifact the deleted artifact fingerprint
	 * @param deletedAt the deletion time
	 * @param reason the deletion reason
	 * @param ids affected operation identities
	 * @param previous the preceding hash
	 * @param fingerprint the entry hash, or {@code null} while calculating it
	 * @return the strict document
	 */
	private static ObjectNode node(JsonMapper json, String artifact, Instant deletedAt, String reason, List<String> ids,
		String previous, String fingerprint)
	{
		ObjectNode result = json.createObjectNode();
		result.put("schema_version", 1);
		result.put("artifact_sha256", artifact);
		result.put("deleted_at", deletedAt.toString());
		result.put("reason", reason);
		ArrayNode array = result.putArray("affected_operation_ids");
		ids.forEach(array::add);
		result.put("previous_sha256", previous);
		if (fingerprint != null)
			result.put("entry_sha256", fingerprint);
		return result;
	}

	/**
	 * Resolves the one permitted deletion-ledger location.
	 *
	 * @param projectRoot the project root
	 * @return the normalized ledger path
	 */
	private static Path ledger(Path projectRoot)
	{
		Objects.requireNonNull(projectRoot, "projectRoot must not be null");
		return projectRoot.toAbsolutePath().normalize().resolve(".cat/history/deletions.jsonl");
	}

	/**
	 * Validates one lower-case SHA-256 fingerprint.
	 *
	 * @param value the candidate value
	 * @param name the diagnostic parameter name
	 * @return the validated value
	 */
	private static String fingerprint(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (!value.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 fingerprint");
		return value;
	}

	/**
	 * Validates one required nonblank text value.
	 *
	 * @param value the candidate text
	 * @param name the diagnostic parameter name
	 * @return the validated text
	 */
	private static String text(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Validates ordered unique operation identities.
	 *
	 * @param values the candidate identities
	 * @return the immutable identities
	 */
	private static List<String> operationIds(List<String> values)
	{
		Objects.requireNonNull(values, "affectedOperationIds must not be null");
		List<String> result = List.copyOf(values);
		if (result.isEmpty() || result.stream().anyMatch(value -> value == null || value.isBlank()) ||
			new HashSet<>(result).size() != result.size())
		{
			throw new IllegalArgumentException("affectedOperationIds must be nonempty, unique, and nonblank");
		}
		return result;
	}

	/**
	 * Holds one durable deletion receipt.
	 *
	 * @param artifactFingerprint the deleted artifact fingerprint
	 * @param deletedAt the deletion time
	 * @param reason the deletion reason
	 * @param affectedOperationIds ordered affected operations
	 * @param previousFingerprint the preceding receipt fingerprint
	 * @param entryFingerprint this receipt fingerprint
	 */
	public record Entry(String artifactFingerprint, Instant deletedAt, String reason, List<String> affectedOperationIds,
		String previousFingerprint, String entryFingerprint)
	{
		/**
		 * Validates one immutable deletion receipt.
		 *
		 * @param artifactFingerprint the deleted artifact fingerprint
		 * @param deletedAt the deletion time
		 * @param reason the deletion reason
		 * @param affectedOperationIds ordered affected operations
		 * @param previousFingerprint the preceding receipt fingerprint
		 * @param entryFingerprint this receipt fingerprint
		 */
		public Entry
		{
			artifactFingerprint = fingerprint(artifactFingerprint, "artifactFingerprint");
			Objects.requireNonNull(deletedAt, "deletedAt must not be null");
			reason = text(reason, "reason");
			affectedOperationIds = operationIds(affectedOperationIds);
			if (!ROOT.equals(previousFingerprint))
				previousFingerprint = fingerprint(previousFingerprint, "previousFingerprint");
			entryFingerprint = fingerprint(entryFingerprint, "entryFingerprint");
		}
	}

	/**
	 * Holds the preceding hash needed by the next append.
	 *
	 * @param lastFingerprint the previous entry fingerprint
	 */
	private record Scan(String lastFingerprint)
	{
	}

	/**
	 * Consumes one verified deletion receipt while allowing ledger validation to fail.
	 */
	@FunctionalInterface
	private interface EntryConsumer
	{
		/**
		 * Consumes one receipt.
		 *
		 * @param entry the verified receipt
		 * @throws IOException if processing cannot continue
		 */
		void accept(Entry entry) throws IOException;
	}

	/** Prevents construction of this static utility. */
	private DeletionReceiptLedger()
	{
	}
}
