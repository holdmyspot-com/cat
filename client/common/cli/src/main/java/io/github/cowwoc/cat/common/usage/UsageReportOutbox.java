package io.github.cowwoc.cat.common.usage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Retains authenticated usage reports until their server acknowledgement is confirmed.
 */
public final class UsageReportOutbox
{
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
	private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
	private static final Set<String> FIELDS = Set.of("schema_version", "principal_id", "account_id",
		"measurement_key", "event_id", "output_tokens_so_far", "body");
	private static final int MAX_BODY_BYTES = 16 * 1024;
	private static final int MAX_RECORD_BYTES = MAX_BODY_BYTES + 2048;
	private final Path directory;
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates an outbox in a caller-owned data directory.
	 *
	 * @param directory directory that persists pending reports across client processes
	 */
	public UsageReportOutbox(Path directory)
	{
		this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
	}

	/**
	 * Persists a measurement before any send and reuses its pending event ID on retry.
	 * The measurement key must identify every input that changes the report body except its event ID.
	 *
	 * @param principalId authenticated provider-qualified user identity
	 * @param accountId selected HoldMySpot account
	 * @param measurementKey stable identity for this exact cumulative measurement
	 * @param outputTokensSoFar verified cumulative generated output
	 * @param factory creates the report body using the retained event ID
	 * @return persisted report with its stable delivery event ID
	 * @throws IOException if the report cannot be persisted or a retained key has changed content
	 */
	public PendingReport enqueue(String principalId, long accountId, String measurementKey,
		long outputTokensSoFar, PayloadFactory factory) throws IOException
	{
		validatePrincipal(principalId);
		validateAccount(accountId);
		validateMeasurementKey(measurementKey);
		if (outputTokensSoFar < 0)
			throw new IllegalArgumentException("Usage count must be nonnegative");
		Objects.requireNonNull(factory, "factory");

		prepareDirectory();
		try (FileChannel channel = FileChannel.open(directory.resolve(".lock"), CREATE, WRITE);
			FileLock lock = acquire(channel);
			DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json"))
		{
			if (!lock.isValid())
				throw new IOException("Usage outbox lock was lost; retry this delivery");
			for (Path file: files)
			{
				Delivery pending = read(file);
				if (!pending.principalId().equals(principalId) || pending.accountId() != accountId ||
					!pending.measurementKey().equals(measurementKey))
					continue;
				String currentBody = factory.create(pending.report().eventId());
				if (pending.report().outputTokensSoFar() != outputTokensSoFar ||
					!pending.report().body().equals(currentBody))
					throw new IOException("Usage measurement changed under a retained key");
				return pending.report();
			}
			UUID eventId = UUID.randomUUID();
			PendingReport report = new PendingReport(eventId, outputTokensSoFar, factory.create(eventId));
			write(new Delivery(principalId, accountId, measurementKey, report));
			return report;
		}
	}

	/**
	 * Sends only reports belonging to this authenticated user and deletes each after its matching acknowledgement.
	 *
	 * @param principalId authenticated provider-qualified user identity
	 * @param bearerToken current access token for that user
	 * @param sender server transport that returns a committed acknowledgement
	 * @return number of confirmed reports
	 * @throws IOException if a record or request fails or the server does not confirm a report
	 * @throws InterruptedException if a request is interrupted
	 */
	public int flush(String principalId, String bearerToken, Sender sender) throws IOException, InterruptedException
	{
		validatePrincipal(principalId);
		if (bearerToken == null || bearerToken.isBlank())
			throw new IllegalArgumentException("Usage bearer token is invalid");
		Objects.requireNonNull(sender, "sender");

		prepareDirectory();
		int confirmed = 0;
		try (FileChannel channel = FileChannel.open(directory.resolve(".lock"), CREATE, WRITE);
			FileLock lock = acquire(channel);
			DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json"))
		{
			if (!lock.isValid())
				throw new IOException("Usage outbox lock was lost; retry this delivery");
			for (Path file: files)
			{
				Delivery pending = read(file);
				if (!pending.principalId().equals(principalId))
					continue;
				Acknowledgement receipt = sender.send(bearerToken, pending.accountId(), pending.report());
				if (receipt == null || !receipt.eventId().equals(pending.report().eventId()) ||
					receipt.accountedOutputTokens() < pending.report().outputTokensSoFar())
					throw new IOException("Usage server did not confirm this delivery and count");
				Files.delete(file);
				++confirmed;
			}
		}
		return confirmed;
	}

	/**
	 * Creates the private data directory and rejects a symbolic-link replacement.
	 *
	 * @throws IOException if the directory cannot be used
	 */
	private void prepareDirectory() throws IOException
	{
		Files.createDirectories(directory);
		if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
			throw new IOException("Usage outbox is not a regular directory: " + directory);
		if (directory.getFileSystem().supportedFileAttributeViews().contains("posix"))
			Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
	}

	/**
	 * Serializes updates from separate client processes.
	 *
	 * @param channel channel for the outbox lock file
	 * @return exclusive lock
	 * @throws IOException if another process currently owns the outbox
	 */
	private static FileLock acquire(FileChannel channel) throws IOException
	{
		try
		{
			FileLock lock = channel.tryLock();
			if (lock == null)
				throw new IOException("Usage outbox is busy; retry this delivery");
			return lock;
		}
		catch (OverlappingFileLockException exception)
		{
			throw new IOException("Usage outbox is busy; retry this delivery", exception);
		}
	}

	/**
	 * Writes one pending report atomically before any network delivery.
	 *
	 * @param pending report bound to its measuring login and selected account
	 * @throws IOException if the record cannot be persisted
	 */
	private void write(Delivery pending) throws IOException
	{
		PendingReport report = pending.report();
		byte[] bytes = json.writeValueAsBytes(Map.of(
			"schema_version", 1,
			"principal_id", pending.principalId(),
			"account_id", pending.accountId(),
			"measurement_key", pending.measurementKey(),
			"event_id", report.eventId().toString(),
			"output_tokens_so_far", report.outputTokensSoFar(),
			"body", report.body()));
		if (bytes.length > MAX_RECORD_BYTES)
			throw new IOException("Usage report exceeds the outbox record limit");
		Path temporary = Files.createTempFile(directory, ".pending-", ".tmp");
		try
		{
			if (directory.getFileSystem().supportedFileAttributeViews().contains("posix"))
				Files.setPosixFilePermissions(temporary, FILE_PERMISSIONS);
			try (FileChannel channel = FileChannel.open(temporary, WRITE))
			{
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining())
					channel.write(buffer);
				channel.force(true);
			}
			Files.move(temporary, directory.resolve(report.eventId() + ".json"), StandardCopyOption.ATOMIC_MOVE);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Reads and validates one pending delivery.
	 *
	 * @param file pending report file
	 * @return retained delivery
	 * @throws IOException if the record is missing, oversized, or malformed
	 */
	private Delivery read(Path file) throws IOException
	{
		if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > MAX_RECORD_BYTES)
			throw new IOException("Usage outbox has an invalid record: " + file);
		if (file.getFileSystem().supportedFileAttributeViews().contains("posix") &&
			!Files.getPosixFilePermissions(file, NOFOLLOW_LINKS).equals(FILE_PERMISSIONS))
			throw new IOException("Usage outbox record must be private: " + file);
		try
		{
			JsonNode node = json.readTree(file.toFile());
			if (node == null || !node.isObject() || !new HashSet<>(node.propertyNames()).equals(FIELDS) ||
				!node.path("schema_version").isIntegralNumber() || !node.path("schema_version").canConvertToInt() ||
				node.path("schema_version").asInt() != 1 || !node.path("principal_id").isString() ||
				!node.path("account_id").isIntegralNumber() || !node.path("account_id").canConvertToLong() ||
				!node.path("measurement_key").isString() || !node.path("event_id").isString() ||
				!node.path("output_tokens_so_far").isIntegralNumber() ||
				!node.path("output_tokens_so_far").canConvertToLong() || !node.path("body").isString())
				throw new IOException("Usage outbox has an invalid record: " + file);
			String principalId = node.path("principal_id").stringValue();
			String measurementKey = node.path("measurement_key").stringValue();
			validatePrincipal(principalId);
			validateMeasurementKey(measurementKey);
			long accountId = node.path("account_id").asLong();
			validateAccount(accountId);
			PendingReport report = new PendingReport(UUID.fromString(node.path("event_id").stringValue()),
				node.path("output_tokens_so_far").asLong(), node.path("body").stringValue());
			if (!file.getFileName().toString().equals(report.eventId() + ".json"))
				throw new IOException("Usage outbox has a mismatched event ID: " + file);
			return new Delivery(principalId, accountId, measurementKey, report);
		}
		catch (JacksonException | IllegalArgumentException exception)
		{
			throw new IOException("Usage outbox has an invalid record: " + file, exception);
		}
	}

	/**
	 * Validates the authenticated owner recorded with a report.
	 *
	 * @param principalId provider-qualified user identity
	 */
	private static void validatePrincipal(String principalId)
	{
		if (principalId == null || principalId.isBlank() || principalId.length() > 255)
			throw new IllegalArgumentException("Usage principal ID is invalid");
	}

	/**
	 * Validates the selected account recorded with a report.
	 *
	 * @param accountId HoldMySpot account ID
	 */
	private static void validateAccount(long accountId)
	{
		if (accountId < 1)
			throw new IllegalArgumentException("Selected HoldMySpot account ID must be positive");
	}

	/**
	 * Validates the key that identifies one immutable measurement.
	 *
	 * @param measurementKey caller-defined measurement identity
	 */
	private static void validateMeasurementKey(String measurementKey)
	{
		if (measurementKey == null || measurementKey.isBlank() || measurementKey.length() > 512)
			throw new IllegalArgumentException("Usage measurement key is invalid");
	}

	/**
	 * Builds one harness-specific wire body with the outbox's durable event ID.
	 */
	@FunctionalInterface
	public interface PayloadFactory
	{
		/**
		 * Builds the exact request body to retain and retry.
		 *
		 * @param eventId stable delivery event ID
		 * @return JSON report body
		 * @throws IOException if the body cannot be created
		 */
		String create(UUID eventId) throws IOException;
	}

	/**
	 * Sends one retained report and returns the server's committed acknowledgement.
	 */
	@FunctionalInterface
	public interface Sender
	{
		/**
		 * Sends the exact retained body for the selected account.
		 *
		 * @param bearerToken current access token for the report's user
		 * @param accountId selected HoldMySpot account ID
		 * @param report retained body and event identity
		 * @return server acknowledgement
		 * @throws IOException if delivery is not confirmed
		 * @throws InterruptedException if delivery is interrupted
		 */
		Acknowledgement send(String bearerToken, long accountId, PendingReport report) throws IOException,
			InterruptedException;
	}

	/**
	 * One immutable report waiting for the server's acknowledgement.
	 *
	 * @param eventId durable delivery identity
	 * @param outputTokensSoFar verified cumulative generated output
	 * @param body exact JSON request body
	 */
	public record PendingReport(UUID eventId, long outputTokensSoFar, String body)
	{
		/**
		 * Requires a usable event, count, and bounded request body.
		 *
		 * @param eventId durable delivery identity
		 * @param outputTokensSoFar verified cumulative generated output
		 * @param body exact JSON request body
		 */
		public PendingReport
		{
			Objects.requireNonNull(eventId, "eventId");
			if (outputTokensSoFar < 0)
				throw new IllegalArgumentException("Usage count must be nonnegative");
			if (body == null || body.isBlank() || body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES)
				throw new IllegalArgumentException("Usage report body is invalid or too large");
		}
	}

	/**
	 * Committed result for one delivery event.
	 *
	 * @param eventId delivery event ID
	 * @param accountedOutputTokens resulting turn high-water
	 */
	public record Acknowledgement(UUID eventId, long accountedOutputTokens)
	{
		/**
		 * Requires a nonnegative committed count and an event identity.
		 *
		 * @param eventId delivery event ID
		 * @param accountedOutputTokens resulting turn high-water
		 */
		public Acknowledgement
		{
			Objects.requireNonNull(eventId, "eventId");
			if (accountedOutputTokens < 0)
				throw new IllegalArgumentException("Accounted usage must be nonnegative");
		}
	}

	/**
	 * A pending report bound to the authenticated user and selected account.
	 *
	 * @param principalId provider-qualified user identity
	 * @param accountId selected account
	 * @param measurementKey stable identity for this exact measurement
	 * @param report immutable report body and event ID
	 */
	private record Delivery(String principalId, long accountId, String measurementKey, PendingReport report)
	{
	}
}
