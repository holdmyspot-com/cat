package io.github.cowwoc.cat.common.auth;

import io.github.cowwoc.cat.common.http.HoldMySpotHttp;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Keeps one WorkOS user session available to later CAT client processes.
 */
public final class WorkOsSessionStore
{
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
	private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
	private static final Set<String> FIELDS = Set.of("schema_version", "server_base", "client_id", "user_id",
		"access_token", "refresh_token", "selected_account_id", "last_authorized_at");
	private static final Set<String> LEGACY_FIELDS = Set.of("schema_version", "server_base", "client_id", "user_id",
		"access_token", "refresh_token", "selected_account_id");
	private static final int MAX_RECORD_BYTES = 64 * 1024;
	private final Path directory;
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates a store beneath a caller-owned private data directory.
	 *
	 * @param directory location that retains the login between client processes
	 */
	public WorkOsSessionStore(Path directory)
	{
		this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
	}

	/**
	 * Reads the saved server, client ID, and tokens for the current login.
	 *
	 * @return current session, or empty when no login has been saved
	 * @throws IOException if the stored session or its permissions are invalid
	 */
	public Optional<Session> load() throws IOException
	{
		return withLock(this::readSession);
	}

	/**
	 * Saves a completed login for later client processes.
	 *
	 * @param session authenticated user session and the host that may receive its access token
	 * @throws IOException if the session cannot be stored privately
	 */
	public void save(Session session) throws IOException
	{
		Objects.requireNonNull(session, "session");
		withLock(() ->
		{
			writeSession(session);
			return session;
		});
	}

	/**
	 * Replaces rotating tokens when the saved login still has the token the caller exchanged.
	 * A concurrent account choice is kept.
	 *
	 * @param expected session whose refresh token the caller exchanged
	 * @param replacement replacement tokens for the same WorkOS user
	 * @return true if the replacement was stored; false if another process changed the session
	 * @throws IOException if the saved session or its permissions are invalid
	 */
	public boolean replaceIfCurrent(Session expected, WorkOsDeviceAuthClient.Tokens replacement) throws IOException
	{
		Objects.requireNonNull(expected, "expected");
		Objects.requireNonNull(replacement, "replacement");
		if (!expected.tokens().userId().equals(replacement.userId()))
			throw new IllegalArgumentException("Replacement tokens belong to a different WorkOS user");

		return withLock(() ->
		{
			Optional<Session> current = readSession();
			if (current.isEmpty())
				return false;
			Session saved = current.orElseThrow();
			if (!sameLogin(expected, saved) || !saved.tokens().equals(expected.tokens()))
				return false;
			writeSession(new Session(saved.serverBase(), saved.clientId(), replacement, saved.selectedAccountId(),
				saved.lastAuthorizedAt()));
			return true;
		});
	}

	/**
	 * Selects a linked account for the saved login without replacing newer tokens.
	 *
	 * @param expected login whose account list the caller verified
	 * @param accountId positive HoldMySpot account ID from that list
	 * @return true if the account was selected; false if a different login replaced it
	 * @throws IOException if the saved session or its permissions are invalid
	 */
	boolean selectIfCurrent(Session expected, long accountId) throws IOException
	{
		Objects.requireNonNull(expected, "expected");
		if (accountId < 1)
			throw new IllegalArgumentException("Selected HoldMySpot account ID must be positive");
		return withLock(() ->
		{
			Optional<Session> current = readSession();
			if (current.isEmpty())
				return false;
			Session saved = current.orElseThrow();
			if (!sameLogin(expected, saved))
				return false;
			if (saved.selectedAccountId().isPresent() && saved.selectedAccountId().getAsLong() == accountId)
				return true;
			writeSession(new Session(saved.serverBase(), saved.clientId(), saved.tokens(), OptionalLong.of(accountId)));
			return true;
		});
	}

	/**
	 * Indicates whether a successful server check was saved for the same selected account and login.
	 *
	 * @param expected login and account whose authorization the server checked
	 * @param authorizedAt local time when the server confirmed authorization
	 * @return true if the check was saved; false if login or selection changed meanwhile
	 * @throws IOException if the saved session or its permissions are invalid
	 */
	public boolean recordAuthorizationIfCurrent(Session expected, Instant authorizedAt) throws IOException
	{
		Objects.requireNonNull(expected, "expected");
		Objects.requireNonNull(authorizedAt, "authorizedAt");
		if (expected.selectedAccountId().isEmpty())
			throw new IllegalArgumentException("An authorization requires a selected HoldMySpot account");
		return withLock(() ->
		{
			Optional<Session> current = readSession();
			if (current.isEmpty())
				return false;
			Session saved = current.orElseThrow();
			if (!sameLogin(expected, saved) || !expected.selectedAccountId().equals(saved.selectedAccountId()))
				return false;
			if (saved.lastAuthorizedAt().isPresent() && !authorizedAt.isAfter(saved.lastAuthorizedAt().orElseThrow()))
				return true;
			writeSession(new Session(saved.serverBase(), saved.clientId(), saved.tokens(), saved.selectedAccountId(),
				Optional.of(authorizedAt)));
			return true;
		});
	}

	/**
	 * Indicates whether a server rejection removed offline authorization for the checked account.
	 *
	 * @param expected login and account rejected by the server
	 * @return true if current authorization was cleared; false if login or selection changed meanwhile
	 * @throws IOException if the saved session or its permissions are invalid
	 */
	public boolean clearAuthorizationIfCurrent(Session expected) throws IOException
	{
		Objects.requireNonNull(expected, "expected");
		return withLock(() ->
		{
			Optional<Session> current = readSession();
			if (current.isEmpty())
				return false;
			Session saved = current.orElseThrow();
			if (!sameLogin(expected, saved) || !expected.selectedAccountId().equals(saved.selectedAccountId()))
				return false;
			if (saved.lastAuthorizedAt().isPresent())
				writeSession(new Session(saved.serverBase(), saved.clientId(), saved.tokens(),
					saved.selectedAccountId()));
			return true;
		});
	}

	/**
	 * Indicates whether two snapshots belong to the same provider login on the same server.
	 *
	 * @param first snapshot taken before a refresh or account choice
	 * @param second current saved snapshot
	 * @return true if the login identity has not changed
	 */
	static boolean sameLogin(Session first, Session second)
	{
		return first.serverBase().equals(second.serverBase()) && first.clientId().equals(second.clientId()) &&
			first.tokens().userId().equals(second.tokens().userId());
	}

	/**
	 * Serializes one session operation across client processes.
	 *
	 * @param operation operation that reads or changes the saved session
	 * @param <T> result type of the operation
	 * @return result produced while the exclusive lock was held
	 * @throws IOException if private storage or the lock cannot be used
	 */
	private <T> T withLock(LockedOperation<T> operation) throws IOException
	{
		prepareDirectory();
		Path lockFile = directory.resolve(".lock");
		try
		{
			Files.createFile(lockFile, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
		}
		catch (FileAlreadyExistsException _)
		{
			// A prior client process created the lock file.
		}
		verifyPrivateFile(lockFile);

		try (FileChannel channel = FileChannel.open(lockFile, WRITE, NOFOLLOW_LINKS);
			FileLock lock = acquire(channel))
		{
			if (!lock.isValid())
				throw new IOException("WorkOS session lock was lost; retry the operation");
			return operation.run();
		}
	}

	/**
	 * Creates a private session directory or checks an existing one.
	 *
	 * @throws IOException if the file system cannot keep the session private
	 */
	private void prepareDirectory() throws IOException
	{
		if (!directory.getFileSystem().supportedFileAttributeViews().contains("posix"))
			throw new IOException("WorkOS session storage requires owner-only POSIX file permissions");
		try
		{
			Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
		}
		catch (FileAlreadyExistsException _)
		{
			// A prior client process created the session directory.
		}
		if (!Files.isDirectory(directory, NOFOLLOW_LINKS) ||
			!Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS).equals(DIRECTORY_PERMISSIONS))
			throw new IOException("WorkOS session directory must be a private, owner-only directory: " + directory);
	}

	/**
	 * Obtains an exclusive lock without waiting indefinitely for another process.
	 *
	 * @param channel private lock file channel
	 * @return exclusive file lock
	 * @throws IOException if another client process owns the session lock
	 */
	private static FileLock acquire(FileChannel channel) throws IOException
	{
		try
		{
			FileLock lock = channel.tryLock();
			if (lock == null)
				throw new IOException("WorkOS session is busy; retry the operation");
			return lock;
		}
		catch (OverlappingFileLockException exception)
		{
			throw new IOException("WorkOS session is busy; retry the operation", exception);
		}
	}

	/**
	 * Reads a bounded saved session while the store lock is held.
	 *
	 * @return stored session, or empty before the first login
	 * @throws IOException if the record is unsafe or malformed
	 */
	private Optional<Session> readSession() throws IOException
	{
		Path file = directory.resolve("session.json");
		if (!Files.exists(file, NOFOLLOW_LINKS))
			return Optional.empty();
		verifyPrivateFile(file);
		try (InputStream input = Files.newInputStream(file, READ, NOFOLLOW_LINKS))
		{
			byte[] bytes = input.readNBytes(MAX_RECORD_BYTES + 1);
			if (bytes.length > MAX_RECORD_BYTES)
				throw new IOException("WorkOS session record exceeds the storage limit");
			JsonNode node = json.readTree(bytes);
			if (node == null || !node.isObject() || !node.path("schema_version").isIntegralNumber() ||
				!node.path("schema_version").canConvertToInt() ||
				(node.path("schema_version").asInt() != 2 && node.path("schema_version").asInt() != 3))
				throw new IOException("WorkOS session record has an invalid schema");
			int version = node.path("schema_version").asInt();
			Set<String> expectedFields = FIELDS;
			if (version == 2)
				expectedFields = LEGACY_FIELDS;
			if (!new HashSet<>(node.propertyNames()).equals(expectedFields) ||
				!node.path("selected_account_id").isIntegralNumber() ||
				!node.path("selected_account_id").canConvertToLong() ||
				node.path("selected_account_id").asLong() < 0)
				throw new IOException("WorkOS session record has an invalid schema");
			long selected = node.path("selected_account_id").asLong();
			OptionalLong selectedAccountId;
			if (selected == 0)
				selectedAccountId = OptionalLong.empty();
			else
				selectedAccountId = OptionalLong.of(selected);
			Optional<Instant> lastAuthorizedAt = Optional.empty();
			if (version == 3)
			{
				JsonNode authorizedAt = node.path("last_authorized_at");
				if (!authorizedAt.isString())
					throw new IOException("WorkOS session record has an invalid authorization time");
				if (!authorizedAt.stringValue().isEmpty())
					lastAuthorizedAt = Optional.of(Instant.parse(authorizedAt.stringValue()));
			}
			return Optional.of(new Session(URI.create(requiredText(node, "server_base")),
				requiredText(node, "client_id"), new WorkOsDeviceAuthClient.Tokens(
					requiredText(node, "user_id"), requiredText(node, "access_token"),
					requiredText(node, "refresh_token")), selectedAccountId, lastAuthorizedAt));
		}
		catch (JacksonException | IllegalArgumentException | DateTimeException exception)
		{
			throw new IOException("WorkOS session record is invalid; sign in again", exception);
		}
	}

	/**
	 * Reads one required nonblank field from a saved session.
	 *
	 * @param node stored session object
	 * @param field required field name
	 * @return field value
	 * @throws IOException if the field is missing or blank
	 */
	private static String requiredText(JsonNode node, String field) throws IOException
	{
		JsonNode value = node.path(field);
		if (!value.isString() || value.stringValue().isBlank())
			throw new IOException("WorkOS session record has no valid " + field);
		return value.stringValue();
	}

	/**
	 * Writes a replacement session atomically while the store lock is held.
	 *
	 * @param session session to retain
	 * @throws IOException if the private record cannot be committed
	 */
	private void writeSession(Session session) throws IOException
	{
		byte[] bytes = json.writeValueAsBytes(Map.of(
			"schema_version", 3,
			"server_base", session.serverBase().toString(),
			"client_id", session.clientId(),
			"user_id", session.tokens().userId(),
			"access_token", session.tokens().accessToken(),
			"refresh_token", session.tokens().refreshToken(),
			"selected_account_id", session.selectedAccountId().orElse(0),
			"last_authorized_at", session.lastAuthorizedAt().map(Instant::toString).orElse("")));
		if (bytes.length > MAX_RECORD_BYTES)
			throw new IOException("WorkOS session record exceeds the storage limit");
		Path temporary = Files.createTempFile(directory, ".session-", ".tmp",
			PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS))
			{
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining())
					channel.write(buffer);
				channel.force(true);
			}
			Files.move(temporary, directory.resolve("session.json"), StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Rejects a file that another operating-system account could read or replace.
	 *
	 * @param file existing lock or session record
	 * @throws IOException if the file is unsafe
	 */
	private static void verifyPrivateFile(Path file) throws IOException
	{
		if (!Files.isRegularFile(file, NOFOLLOW_LINKS) ||
			!Files.getPosixFilePermissions(file, NOFOLLOW_LINKS).equals(FILE_PERMISSIONS))
			throw new IOException("WorkOS session file must be a private, owner-only regular file: " + file);
	}

	/**
	 * Operation executed while one client process owns the session lock.
	 *
	 * @param <T> result type
	 */
	@FunctionalInterface
	private interface LockedOperation<T>
	{
		/**
		 * Reads or changes the saved session.
		 *
		 * @return operation result
		 * @throws IOException if the session cannot be used
		 */
		T run() throws IOException;
	}

	/**
	 * Binds user tokens to the server and public client that established the login.
	 *
	 * @param serverBase HoldMySpot API origin or product mount that receives the token
	 * @param clientId public WorkOS client ID accepted by that server
	 * @param tokens WorkOS user-session tokens
	 * @param selectedAccountId linked HoldMySpot account chosen for usage, or empty before selection
	 * @param lastAuthorizedAt most recent successful server authorization for the selected account
	 */
	public record Session(URI serverBase, String clientId, WorkOsDeviceAuthClient.Tokens tokens,
		OptionalLong selectedAccountId, Optional<Instant> lastAuthorizedAt)
	{
		/**
		 * Creates an unselected session immediately after login.
		 *
		 * @param serverBase HoldMySpot API origin or product mount that receives the token
		 * @param clientId public WorkOS client ID accepted by that server
		 * @param tokens WorkOS user-session tokens
		 */
		public Session(URI serverBase, String clientId, WorkOsDeviceAuthClient.Tokens tokens)
		{
			this(serverBase, clientId, tokens, OptionalLong.empty(), Optional.empty());
		}

		/**
		 * Creates a session whose selected account has not yet been authorized for offline use.
		 *
		 * @param serverBase HoldMySpot API origin or product mount that receives the token
		 * @param clientId public WorkOS client ID accepted by that server
		 * @param tokens WorkOS user-session tokens
		 * @param selectedAccountId linked HoldMySpot account chosen for usage, or empty before selection
		 */
		public Session(URI serverBase, String clientId, WorkOsDeviceAuthClient.Tokens tokens,
			OptionalLong selectedAccountId)
		{
			this(serverBase, clientId, tokens, selectedAccountId, Optional.empty());
		}

		/**
		 * Validates the session's host, public client, tokens, and optional account choice.
		 *
		 * @param serverBase HoldMySpot API origin or product mount that receives the token
		 * @param clientId public WorkOS client ID accepted by that server
		 * @param tokens WorkOS user-session tokens
		 * @param selectedAccountId linked HoldMySpot account chosen for usage, or empty before selection
		 * @param lastAuthorizedAt most recent successful server authorization for the selected account
		 */
		public Session
		{
			HoldMySpotHttp.endpoint(serverBase, "/v1/accounts");
			if (clientId == null || !clientId.matches("client_[A-Za-z0-9_-]+"))
				throw new IllegalArgumentException("WorkOS client ID is invalid");
			Objects.requireNonNull(tokens, "tokens");
			Objects.requireNonNull(selectedAccountId, "selectedAccountId");
			Objects.requireNonNull(lastAuthorizedAt, "lastAuthorizedAt");
			if (selectedAccountId.isPresent() && selectedAccountId.getAsLong() < 1)
				throw new IllegalArgumentException("Selected HoldMySpot account ID must be positive");
			if (selectedAccountId.isEmpty() && lastAuthorizedAt.isPresent())
				throw new IllegalArgumentException("An authorization requires a selected HoldMySpot account");
		}

		@Override
		public String toString()
		{
			return "Session[serverBase=" + serverBase + ", clientId=" + clientId +
				", selectedAccountId=" + selectedAccountId + ", lastAuthorizedAt=" + lastAuthorizedAt +
				", tokens=redacted]";
		}
	}
}
