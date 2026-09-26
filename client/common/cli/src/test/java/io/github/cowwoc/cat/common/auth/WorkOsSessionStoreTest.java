package io.github.cowwoc.cat.common.auth;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks that a later CAT process can use a privately retained WorkOS session.
 */
public final class WorkOsSessionStoreTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public WorkOsSessionStoreTest()
	{
	}

	/**
	 * A new store instance reads the same user, host, and tokens after a login.
	 *
	 * @throws IOException if the test-owned store cannot persist its session
	 */
	@Test
	public void retainsLoginAcrossProcesses() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-workos-session-"));
			Path directory = root.resolve("auth");
			WorkOsSessionStore.Session session = session("access-first", "refresh-first");
			new WorkOsSessionStore(directory).save(session);

			requireThat(new WorkOsSessionStore(directory).load().orElseThrow(), "restartedSession").isEqualTo(session);
			requireThat(Files.getPosixFilePermissions(directory), "directoryPermissions").
				isEqualTo(PosixFilePermissions.fromString("rwx------"));
		}
	}

	/**
	 * A later process reads the server-validated account and time that bound the offline allowance.
	 *
	 * @throws IOException if the test-owned store cannot persist its session
	 */
	@Test
	public void retainsAuthorizationAfterRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-account-authorization-"));
			WorkOsSessionStore store = new WorkOsSessionStore(root.resolve("auth"));
			WorkOsSessionStore.Session login = session("access-first", "refresh-first");
			Instant verifiedAt = Instant.parse("2026-09-25T12:00:00Z");
			WorkOsSessionStore.Session authorized = new WorkOsSessionStore.Session(login.serverBase(),
				login.clientId(), login.tokens(), OptionalLong.of(17), Optional.of(verifiedAt));
			store.save(authorized);

			requireThat(new WorkOsSessionStore(root.resolve("auth")).load().orElseThrow(), "restartedSession").
				isEqualTo(authorized);
		}
	}

	/**
	 * Rotating the same user's tokens keeps authorization for the selected account.
	 *
	 * @throws IOException if the test-owned store cannot persist its session
	 */
	@Test
	public void retainsAuthorizationAfterRefresh() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-account-authorization-"));
			WorkOsSessionStore store = new WorkOsSessionStore(root.resolve("auth"));
			WorkOsSessionStore.Session login = session("access-first", "refresh-first");
			Instant authorizedAt = Instant.parse("2026-09-25T12:00:00Z");
			WorkOsSessionStore.Session authorized = new WorkOsSessionStore.Session(login.serverBase(),
				login.clientId(), login.tokens(), OptionalLong.of(17), Optional.of(authorizedAt));
			store.save(authorized);

			WorkOsDeviceAuthClient.Tokens replacement = new WorkOsDeviceAuthClient.Tokens("user_test",
				"access-second", "refresh-second");
			requireThat(store.replaceIfCurrent(authorized, replacement), "refresh").isTrue();
			requireThat(store.load().orElseThrow().lastAuthorizedAt(), "lastAuthorizedAt").
				isEqualTo(Optional.of(authorizedAt));
		}
	}

	/**
	 * Selecting a different account cannot inherit the previous account's offline authorization.
	 *
	 * @throws IOException if the test-owned store cannot persist its session
	 */
	@Test
	public void clearsAuthorizationOnAccountChange() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-account-authorization-"));
			WorkOsSessionStore store = new WorkOsSessionStore(root.resolve("auth"));
			WorkOsSessionStore.Session login = session("access-first", "refresh-first");
			WorkOsSessionStore.Session authorized = new WorkOsSessionStore.Session(login.serverBase(),
				login.clientId(), login.tokens(), OptionalLong.of(17),
				Optional.of(Instant.parse("2026-09-25T12:00:00Z")));
			store.save(authorized);

			requireThat(store.selectIfCurrent(authorized, 18), "selection").isTrue();
			requireThat(store.load().orElseThrow().lastAuthorizedAt(), "lastAuthorizedAt").isEmpty();
		}
	}

	/**
	 * The server's successful check survives a later process with the same selected account.
	 *
	 * @throws IOException if the test-owned store cannot persist its session
	 */
	@Test
	public void recordsAuthorizationForAccount() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-account-authorization-"));
			WorkOsSessionStore store = new WorkOsSessionStore(root.resolve("auth"));
			WorkOsSessionStore.Session login = session("access-first", "refresh-first");
			WorkOsSessionStore.Session selected = new WorkOsSessionStore.Session(login.serverBase(),
				login.clientId(), login.tokens(), OptionalLong.of(17));
			store.save(selected);
			Instant authorizedAt = Instant.parse("2026-09-25T12:00:00Z");

			requireThat(store.recordAuthorizationIfCurrent(selected, authorizedAt), "recorded").isTrue();
			requireThat(new WorkOsSessionStore(root.resolve("auth")).load().orElseThrow().lastAuthorizedAt(),
				"restartedAuthorization").isEqualTo(Optional.of(authorizedAt));
		}
	}

	/**
	 * A server reply for the old account cannot authorize another account selected during the request.
	 *
	 * @throws IOException if the test-owned store cannot persist its session
	 */
	@Test
	public void rejectsAuthorizationAfterSwitch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-account-authorization-"));
			WorkOsSessionStore store = new WorkOsSessionStore(root.resolve("auth"));
			WorkOsSessionStore.Session login = session("access-first", "refresh-first");
			WorkOsSessionStore.Session selected = new WorkOsSessionStore.Session(login.serverBase(),
				login.clientId(), login.tokens(), OptionalLong.of(17));
			store.save(selected);
			requireThat(store.selectIfCurrent(selected, 18), "newSelection").isTrue();

			requireThat(store.recordAuthorizationIfCurrent(selected, Instant.parse("2026-09-25T12:00:00Z")),
				"staleAuthorization").isFalse();
			requireThat(store.load().orElseThrow().lastAuthorizedAt(), "lastAuthorizedAt").isEmpty();
		}
	}

	/**
	 * A server rejection removes the saved offline allowance for that account across restarts.
	 *
	 * @throws IOException if the test-owned store cannot persist its session
	 */
	@Test
	public void clearsAuthorizationAfterRejection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-account-authorization-"));
			WorkOsSessionStore store = new WorkOsSessionStore(root.resolve("auth"));
			WorkOsSessionStore.Session login = session("access-first", "refresh-first");
			WorkOsSessionStore.Session authorized = new WorkOsSessionStore.Session(login.serverBase(),
				login.clientId(), login.tokens(), OptionalLong.of(17),
				Optional.of(Instant.parse("2026-09-25T12:00:00Z")));
			store.save(authorized);

			requireThat(store.clearAuthorizationIfCurrent(authorized), "cleared").isTrue();
			requireThat(new WorkOsSessionStore(root.resolve("auth")).load().orElseThrow().lastAuthorizedAt(),
				"restartedAuthorization").isEmpty();
		}
	}

	/**
	 * A late refresh cannot overwrite a newer rotating token saved by another process.
	 *
	 * @throws IOException if the test-owned store cannot persist its session
	 */
	@Test
	public void retainsNewerRefreshAfterConcurrentChange() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-workos-session-"));
			WorkOsSessionStore store = new WorkOsSessionStore(root.resolve("auth"));
			WorkOsSessionStore.Session original = session("access-first", "refresh-first");
			WorkOsDeviceAuthClient.Tokens newer = new WorkOsDeviceAuthClient.Tokens("user_test",
				"access-second", "refresh-second");
			store.save(original);

			requireThat(store.replaceIfCurrent(original, newer), "firstRefresh").isTrue();
			requireThat(new WorkOsSessionStore(root.resolve("auth")).replaceIfCurrent(original,
				new WorkOsDeviceAuthClient.Tokens("user_test", "access-stale", "refresh-stale")),
				"staleRefresh").isFalse();
			requireThat(store.load().orElseThrow().tokens(), "savedTokens").isEqualTo(newer);
		}
	}

	/**
	 * Account selection and token rotation both survive when either finishes first.
	 *
	 * @throws IOException if the test-owned store cannot persist its session
	 */
	@Test
	public void preservesSelectionAndTokensAcrossRaces() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-workos-session-"));
			WorkOsSessionStore store = new WorkOsSessionStore(root.resolve("auth"));
			WorkOsSessionStore.Session original = session("access-first", "refresh-first");
			WorkOsDeviceAuthClient.Tokens newer = new WorkOsDeviceAuthClient.Tokens("user_test",
				"access-second", "refresh-second");
			store.save(original);

			requireThat(store.selectIfCurrent(original, 17), "selectionBeforeRefresh").isTrue();
			requireThat(store.replaceIfCurrent(original, newer), "refreshAfterSelection").isTrue();
			WorkOsSessionStore.Session afterSelectionFirst = new WorkOsSessionStore(root.resolve("auth")).load().
				orElseThrow();
			requireThat(afterSelectionFirst.selectedAccountId(), "selectedAccount").isEqualTo(OptionalLong.of(17));
			requireThat(afterSelectionFirst.tokens(), "refreshedTokens").isEqualTo(newer);

			store.save(original);
			requireThat(store.replaceIfCurrent(original, newer), "refreshBeforeSelection").isTrue();
			requireThat(store.selectIfCurrent(original, 29), "selectionAfterRefresh").isTrue();
			WorkOsSessionStore.Session afterRefreshFirst = new WorkOsSessionStore(root.resolve("auth")).load().
				orElseThrow();
			requireThat(afterRefreshFirst.selectedAccountId(), "laterSelectedAccount").
				isEqualTo(OptionalLong.of(29));
			requireThat(afterRefreshFirst.tokens(), "laterRefreshedTokens").isEqualTo(newer);
		}
	}

	/**
	 * Stored credentials are private to the operating-system account running CAT.
	 *
	 * @throws IOException if the test-owned store cannot persist its session
	 */
	@Test
	public void keepsStoredCredentialsPrivate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-workos-session-"));
			Path directory = root.resolve("auth");
			new WorkOsSessionStore(directory).save(session("access-first", "refresh-first"));

			try (Stream<Path> files = Files.list(directory))
			{
				for (Path file: files.toList())
					requireThat(Files.getPosixFilePermissions(file), "credentialPermissions").
						isEqualTo(PosixFilePermissions.fromString("rw-------"));
			}
		}
	}

	/**
	 * Creates one valid session bound to the configured beta API and public client.
	 *
	 * @param accessToken access token for the API
	 * @param refreshToken token that rotates on refresh
	 * @return session for the test login
	 */
	private static WorkOsSessionStore.Session session(String accessToken, String refreshToken)
	{
		return new WorkOsSessionStore.Session(URI.create("https://api.holdmyspot.com/cat/"), "client_test",
			new WorkOsDeviceAuthClient.Tokens("user_test", accessToken, refreshToken));
	}
}
