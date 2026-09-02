package io.github.cowwoc.cat.common.optimization;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.RecordStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads, claims, and renews the strict durable ownership record for one operation.
 */
public final class OperationClaimDocument
{
	private static final int SCHEMA_VERSION = 1;
	private static final int MAXIMUM_DOCUMENT_BYTES = 64 * 1024;
	private static final List<String> FIELDS = List.of("schema_version", "operation_id", "owner_id", "process_id",
		"acquired_at", "renewed_at", "expires_at");

	/**
	 * Acquires an absent or expired operation claim.
	 *
	 * @param json the JSON codec
	 * @param clock the acquisition clock
	 * @param directory the operation directory
	 * @param ownerId the random owner identifier
	 * @param processId the positive operating-system process identifier
	 * @return the new claim, or empty when another owner has a live claim
	 * @throws IOException if the operation is terminal or its records are invalid or inaccessible
	 */
	public static Optional<OperationClaim> tryAcquire(JsonMapper json, Clock clock, OperationDirectory directory,
		UUID ownerId, long processId) throws IOException
	{
		requireArguments(json, clock, directory, ownerId, processId);
		OperationClaim[] result = new OperationClaim[1];
		RecordStore.withLock(directory.claimPath(), () ->
		{
			requireNonterminal(directory);
			OperationClaim existing = readLocked(json, directory).orElse(null);
			Instant now = clock.instant();
			if (existing != null && existing.expiresAt().isAfter(now))
			{
				if (!ownedBy(existing, ownerId, processId))
					return;
				OperationClaim renewed = new OperationClaim(existing.operationId(), ownerId, processId,
					existing.acquiredAt(), now, now.plus(OperationClaim.LEASE_DURATION));
				writeLocked(json, directory.claimPath(), renewed);
				result[0] = renewed;
				return;
			}
			OperationClaim acquired = new OperationClaim(directory.manifest().operationId(), ownerId, processId, now, now,
				now.plus(OperationClaim.LEASE_DURATION));
			writeLocked(json, directory.claimPath(), acquired);
			result[0] = acquired;
		});
		return Optional.ofNullable(result[0]);
	}

	/**
	 * Renews a claim held by its exact owner.
	 *
	 * @param json the JSON codec
	 * @param clock the renewal clock
	 * @param directory the operation directory
	 * @param expected the claim expected by the owner
	 * @return the renewed claim
	 * @throws IOException if the operation is terminal, the claim changed, or its records are invalid
	 */
	public static OperationClaim renew(JsonMapper json, Clock clock, OperationDirectory directory,
		OperationClaim expected) throws IOException
	{
		Objects.requireNonNull(expected, "expected must not be null");
		if (!expected.operationId().equals(directory.manifest().operationId()))
			throw new IllegalArgumentException("expected claim does not belong to the operation directory");
		requireArguments(json, clock, directory, expected.ownerId(), expected.processId());
		OperationClaim[] result = new OperationClaim[1];
		RecordStore.withLock(directory.claimPath(), () ->
		{
			requireNonterminal(directory);
			OperationClaim actual = readLocked(json, directory).orElseThrow(() ->
				new IOException("Operation claim is absent: " + directory.claimPath()));
			if (!actual.equals(expected))
				throw new IOException("Operation claim changed before renewal: " + directory.claimPath());
			Instant now = clock.instant();
			OperationClaim renewed = new OperationClaim(actual.operationId(), actual.ownerId(), actual.processId(),
				actual.acquiredAt(), now, now.plus(OperationClaim.LEASE_DURATION));
			writeLocked(json, directory.claimPath(), renewed);
			result[0] = renewed;
		});
		return result[0];
	}

	/**
	 * Reads the current claim, if one has been published.
	 *
	 * @param json the JSON codec
	 * @param directory the operation directory
	 * @return the current claim, or empty when no claim exists
	 * @throws IOException if the record is malformed or unsafe
	 */
	public static Optional<OperationClaim> read(JsonMapper json, OperationDirectory directory) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(directory, "directory must not be null");
		OperationClaim[] result = new OperationClaim[1];
		RecordStore.withLock(directory.claimPath(), () -> result[0] = readLocked(json, directory).orElse(null));
		return Optional.ofNullable(result[0]);
	}

	/**
	 * Validates common public method arguments.
	 *
	 * @param json the JSON codec
	 * @param clock the operation clock
	 * @param directory the operation directory
	 * @param ownerId the proposed owner identifier
	 * @param processId the proposed process identifier
	 */
	private static void requireArguments(JsonMapper json, Clock clock, OperationDirectory directory, UUID ownerId,
		long processId)
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(clock, "clock must not be null");
		Objects.requireNonNull(directory, "directory must not be null");
		Objects.requireNonNull(ownerId, "ownerId must not be null");
		if (processId <= 0)
			throw new IllegalArgumentException("processId must be positive");
	}

	/**
	 * Rejects claims for completed or failed operations.
	 *
	 * @param directory the operation directory
	 * @throws IOException if the operation is terminal
	 */
	private static void requireNonterminal(OperationDirectory directory) throws IOException
	{
		if (directory.verifiedTerminalReceipt().isPresent())
			throw new IOException("Cannot claim terminal operation: " + directory.root());
	}

	/**
	 * Reads the document while the caller owns its lock.
	 *
	 * @param json the JSON codec
	 * @param directory the operation directory
	 * @return the parsed claim, or empty when absent
	 * @throws IOException if the claim is malformed or unsafe
	 */
	private static Optional<OperationClaim> readLocked(JsonMapper json, OperationDirectory directory) throws IOException
	{
		Path path = directory.claimPath();
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Expected regular operation claim: " + path);
		String content = BoundedInput.readUtf8(path, MAXIMUM_DOCUMENT_BYTES, "Operation claim");
		try
		{
			JsonMapper strict = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			JsonNode parsed = strict.readTree(content);
			if (!(parsed instanceof ObjectNode document))
				throw new IOException("Operation claim is not a JSON object: " + path);
			if (!fieldNames(document).equals(FIELDS))
				throw new IOException("Operation claim has unsupported, missing, or reordered fields: " + path);
			int schemaVersion = integer(document, "schema_version", path);
			if (schemaVersion != SCHEMA_VERSION)
				throw new IOException("Unsupported operation-claim schema " + schemaVersion + ": " + path);
			OperationClaim claim = new OperationClaim(text(document, "operation_id", path),
				uuid(document, "owner_id", path), positiveLong(document, "process_id", path),
				instant(document, "acquired_at", path), instant(document, "renewed_at", path),
				instant(document, "expires_at", path));
			if (!claim.operationId().equals(directory.manifest().operationId()))
				throw new IOException("Operation claim identity does not match its manifest: " + path);
			return Optional.of(claim);
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid operation claim at " + path + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Publishes an exact claim document while the caller owns its lock.
	 *
	 * @param json the JSON codec
	 * @param path the claim-document path
	 * @param claim the claim to publish
	 * @throws IOException if publication fails
	 */
	private static void writeLocked(JsonMapper json, Path path, OperationClaim claim) throws IOException
	{
		ObjectNode document = json.createObjectNode();
		document.put("schema_version", SCHEMA_VERSION);
		document.put("operation_id", claim.operationId());
		document.put("owner_id", claim.ownerId().toString());
		document.put("process_id", claim.processId());
		document.put("acquired_at", claim.acquiredAt().toString());
		document.put("renewed_at", claim.renewedAt().toString());
		document.put("expires_at", claim.expiresAt().toString());
		RecordStore.write(json, path, document);
	}

	/**
	 * Returns whether the claim belongs to the supplied owner identity.
	 *
	 * @param claim the claim to inspect
	 * @param ownerId the owner identifier
	 * @param processId the process identifier
	 * @return whether the supplied identity owns the claim
	 */
	private static boolean ownedBy(OperationClaim claim, UUID ownerId, long processId)
	{
		return claim.ownerId().equals(ownerId) && claim.processId() == processId;
	}

	/**
	 * Returns JSON field names in parsed order.
	 *
	 * @param document the parsed claim document
	 * @return ordered field names
	 */
	private static List<String> fieldNames(ObjectNode document)
	{
		return new ArrayList<>(document.propertyNames());
	}

	/**
	 * Reads one required nonblank text field.
	 *
	 * @param document the parsed claim document
	 * @param field the field name
	 * @param path the source path
	 * @return the required text
	 * @throws IOException if the field is invalid
	 */
	private static String text(ObjectNode document, String field, Path path) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Operation claim has invalid " + field + ": " + path);
		return value.asString();
	}

	/**
	 * Reads one exact integer field.
	 *
	 * @param document the parsed claim document
	 * @param field the field name
	 * @param path the source path
	 * @return the integer value
	 * @throws IOException if the field is invalid
	 */
	private static int integer(ObjectNode document, String field, Path path) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isInt())
			throw new IOException("Operation claim has invalid " + field + ": " + path);
		return value.intValue();
	}

	/**
	 * Reads one positive exact integer field.
	 *
	 * @param document the parsed claim document
	 * @param field the field name
	 * @param path the source path
	 * @return the positive integer value
	 * @throws IOException if the field is invalid
	 */
	private static long positiveLong(ObjectNode document, String field, Path path) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isLong() && !value.isInt())
			throw new IOException("Operation claim has invalid " + field + ": " + path);
		long result = value.longValue();
		if (result <= 0)
			throw new IOException("Operation claim has nonpositive " + field + ": " + path);
		return result;
	}

	/**
	 * Reads a canonical UUID.
	 *
	 * @param document the parsed claim document
	 * @param field the field name
	 * @param path the source path
	 * @return the UUID value
	 * @throws IOException if the field is invalid
	 */
	private static UUID uuid(ObjectNode document, String field, Path path) throws IOException
	{
		String value = text(document, field, path);
		try
		{
			UUID result = UUID.fromString(value);
			if (!result.toString().equals(value))
				throw new IOException("Operation claim has noncanonical " + field + ": " + path);
			return result;
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Operation claim has invalid " + field + ": " + path, e);
		}
	}

	/**
	 * Reads a canonical UTC instant.
	 *
	 * @param document the parsed claim document
	 * @param field the field name
	 * @param path the source path
	 * @return the instant value
	 * @throws IOException if the field is invalid
	 */
	private static Instant instant(ObjectNode document, String field, Path path) throws IOException
	{
		String value = text(document, field, path);
		try
		{
			Instant result = Instant.parse(value);
			if (!result.toString().equals(value))
				throw new IOException("Operation claim has noncanonical " + field + ": " + path);
			return result;
		}
		catch (DateTimeException e)
		{
			throw new IOException("Operation claim has invalid " + field + ": " + path, e);
		}
	}

	/**
	 * Prevents construction of this static document boundary.
	 */
	private OperationClaimDocument()
	{
	}
}
