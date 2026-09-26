package io.github.cowwoc.cat.common.usage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Retains CAT rule-delivery identities until the server confirms each invocation.
 */
public final class FeatureInvocationOutbox
{
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
	private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
	private static final Set<String> FIELDS = Set.of("schema_version", "principal_id", "account_id", "feature_id",
		"invocation_id");
	private static final int MAX_RECORD_BYTES = 1_024;
	private final Path directory;
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Sends one retained invocation and returns only after server confirmation.
	 */
	@FunctionalInterface
	public interface Sender
	{
		/**
		 * Sends one immutable invocation for its original account.
		 *
		 * @param accessToken current user-session token
		 * @param accountId original selected account
		 * @param featureId installed rule path relative to {@code rules/}
		 * @param invocationId retained invocation identity
		 * @throws IOException if delivery is unconfirmed
		 * @throws InterruptedException if delivery is interrupted
		 */
		void send(String accessToken, long accountId, String featureId, UUID invocationId)
			throws IOException, InterruptedException;
	}

	/**
	 * Indicates that the host does not recognize an invocation's product or feature.
	 */
	public static final class UnregisteredFeatureException extends IOException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Creates a rejection that leaves this event pending without blocking other features.
		 */
		public UnregisteredFeatureException()
		{
			super("Feature invocation server returned HTTP 422");
		}
	}

	/**
	 * Creates an outbox in a private plugin data directory.
	 *
	 * @param directory directory that retains invocations across processes
	 */
	public FeatureInvocationOutbox(Path directory)
	{
		this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
	}

	/**
	 * Persists a new rule delivery before the hook supplies its content.
	 *
	 * @param principalId provider-qualified user identity
	 * @param accountId selected HoldMySpot account
	 * @param featureId installed rule path relative to {@code rules/}
	 * @return stable invocation identity for retries
	 * @throws IOException if the invocation cannot be retained
	 */
	public UUID enqueue(String principalId, long accountId, String featureId) throws IOException
	{
		validate(principalId, accountId, featureId);
		prepareDirectory();
		try (FileChannel channel = FileChannel.open(directory.resolve(".lock"), CREATE, WRITE);
			FileLock lock = acquire(channel))
		{
			if (!lock.isValid())
				throw new IOException("Feature invocation outbox lock was lost; retry this delivery");
			UUID invocationId = UUID.randomUUID();
			PendingInvocation pending = new PendingInvocation(principalId, accountId, featureId, invocationId);
			write(pending);
			return invocationId;
		}
	}

	/**
	 * Sends pending invocations only for the current user, retaining any unconfirmed event.
	 *
	 * @param principalId provider-qualified user identity
	 * @param accessToken current user-session token
	 * @param sender confirmed transport
	 * @return number of confirmed invocations
	 * @throws IOException if a record or delivery fails
	 * @throws InterruptedException if delivery is interrupted
	 */
	public int flush(String principalId, String accessToken, Sender sender) throws IOException, InterruptedException
	{
		validatePrincipal(principalId);
		if (accessToken == null || accessToken.isBlank())
			throw new IllegalArgumentException("Feature invocation bearer token is invalid");
		Objects.requireNonNull(sender, "sender");
		prepareDirectory();
		int confirmed = 0;
		UnregisteredFeatureException unregistered = null;
		try (FileChannel channel = FileChannel.open(directory.resolve(".lock"), CREATE, WRITE);
			FileLock lock = acquire(channel);
			DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json"))
		{
			if (!lock.isValid())
				throw new IOException("Feature invocation outbox lock was lost; retry this delivery");
			for (Path file: files)
			{
				PendingInvocation pending = read(file);
				if (!pending.principalId().equals(principalId))
					continue;
				try
				{
					sender.send(accessToken, pending.accountId(), pending.featureId(), pending.invocationId());
				}
				catch (UnregisteredFeatureException exception)
				{
					unregistered = exception;
					continue;
				}
				Files.delete(file);
				++confirmed;
			}
		}
		if (unregistered != null)
			throw unregistered;
		return confirmed;
	}

	/**
	 * Creates the private data directory and rejects symbolic-link replacement.
	 *
	 * @throws IOException if the directory cannot be used
	 */
	private void prepareDirectory() throws IOException
	{
		Files.createDirectories(directory);
		if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
			throw new IOException("Feature invocation outbox is not a regular directory: " + directory);
		if (directory.getFileSystem().supportedFileAttributeViews().contains("posix"))
			Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
	}

	/**
	 * Serializes updates from separate plugin processes.
	 *
	 * @param channel outbox lock channel
	 * @return exclusive lock
	 * @throws IOException if another process owns the outbox
	 */
	private static FileLock acquire(FileChannel channel) throws IOException
	{
		try
		{
			FileLock lock = channel.tryLock();
			if (lock == null)
				throw new IOException("Feature invocation outbox is busy; retry this delivery");
			return lock;
		}
		catch (OverlappingFileLockException exception)
		{
			throw new IOException("Feature invocation outbox is busy; retry this delivery", exception);
		}
	}

	/**
	 * Atomically retains one invocation before rule content is supplied.
	 *
	 * @param pending immutable invocation
	 * @throws IOException if the record cannot be persisted
	 */
	private void write(PendingInvocation pending) throws IOException
	{
		byte[] bytes = json.writeValueAsBytes(Map.of("schema_version", 1, "principal_id", pending.principalId(),
			"account_id", pending.accountId(), "feature_id", pending.featureId(),
			"invocation_id", pending.invocationId().toString()));
		if (bytes.length > MAX_RECORD_BYTES)
			throw new IOException("Feature invocation exceeds the outbox record limit");
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
			Files.move(temporary, directory.resolve(pending.invocationId() + ".json"),
				StandardCopyOption.ATOMIC_MOVE);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Validates a retained invocation before sending it.
	 *
	 * @param file record file
	 * @return bound invocation
	 * @throws IOException if the record is invalid or exposed
	 */
	private PendingInvocation read(Path file) throws IOException
	{
		if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > MAX_RECORD_BYTES)
			throw new IOException("Feature invocation outbox has an invalid record: " + file);
		if (file.getFileSystem().supportedFileAttributeViews().contains("posix") &&
			!Files.getPosixFilePermissions(file, NOFOLLOW_LINKS).equals(FILE_PERMISSIONS))
			throw new IOException("Feature invocation outbox record must be private: " + file);
		try
		{
			JsonNode node = json.readTree(file.toFile());
			if (node == null || !node.isObject() || !new HashSet<>(node.propertyNames()).equals(FIELDS) ||
				!node.path("schema_version").isIntegralNumber() || node.path("schema_version").asInt() != 1 ||
				!node.path("principal_id").isString() || !node.path("account_id").isIntegralNumber() ||
				!node.path("account_id").canConvertToLong() || !node.path("feature_id").isString() ||
				!node.path("invocation_id").isString())
				throw new IOException("Feature invocation outbox has an invalid record: " + file);
			PendingInvocation pending = new PendingInvocation(node.path("principal_id").stringValue(),
				node.path("account_id").asLong(), node.path("feature_id").stringValue(),
				UUID.fromString(node.path("invocation_id").stringValue()));
			validate(pending.principalId(), pending.accountId(), pending.featureId());
			if (!file.getFileName().toString().equals(pending.invocationId() + ".json"))
				throw new IOException("Feature invocation outbox has a mismatched invocation ID: " + file);
			return pending;
		}
		catch (JacksonException | IllegalArgumentException exception)
		{
			throw new IOException("Feature invocation outbox has an invalid record: " + file, exception);
		}
	}

	/**
	 * Validates one persisted owner and feature identity.
	 *
	 * @param principalId provider-qualified user identity
	 * @param accountId selected account
	 * @param featureId installed rule path
	 */
	private static void validate(String principalId, long accountId, String featureId)
	{
		validatePrincipal(principalId);
		if (accountId < 1)
			throw new IllegalArgumentException("Selected HoldMySpot account ID must be positive");
		if (featureId == null || featureId.isBlank() || featureId.length() > 128)
			throw new IllegalArgumentException("CAT feature ID is invalid");
	}

	/**
	 * Validates the authenticated owner recorded with an invocation.
	 *
	 * @param principalId provider-qualified user identity
	 */
	private static void validatePrincipal(String principalId)
	{
		if (principalId == null || principalId.isBlank() || principalId.length() > 255)
			throw new IllegalArgumentException("Feature invocation principal ID is invalid");
	}

	/**
	 * One invocation bound to its original login and selected account.
	 *
	 * @param principalId authenticated provider-qualified user identity
	 * @param accountId selected account at delivery time
	 * @param featureId delivered registered CAT rule
	 * @param invocationId stable event ID
	 */
	private record PendingInvocation(String principalId, long accountId, String featureId, UUID invocationId)
	{
	}
}
