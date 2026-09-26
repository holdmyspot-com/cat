package io.github.cowwoc.cat.common.auth;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.sun.net.httpserver.HttpServer;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;

/**
 * Checks account authorization while a local HTTP server represents the HoldMySpot host.
 */
public final class WorkOsAccountAccessTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public WorkOsAccountAccessTest()
	{
	}

	/**
	 * A server outage permits feature delivery before, but not at, the 24-hour boundary.
	 *
	 * @throws IOException if the local server or session store fails
	 * @throws InterruptedException if the account request is interrupted
	 */
	@Test
	public void limitsOfflineUseToTwentyFourHours() throws IOException, InterruptedException
	{
		Instant authorizedAt = Instant.parse("2026-09-25T12:00:00Z");
		requireThat(checkHostResponse(503, Optional.of(authorizedAt), authorizedAt.plusSeconds(86_399)).decision(),
			"beforeBoundary").
			isEqualTo(WorkOsAccountAccess.Decision.OFFLINE);
		requireThat(checkHostResponse(503, Optional.of(authorizedAt), authorizedAt.plusSeconds(86_400)).decision(),
			"atBoundary").
			isEqualTo(WorkOsAccountAccess.Decision.CONNECTIVITY_REQUIRED);
	}

	/**
	 * A login without a prior server check cannot gain offline access.
	 *
	 * @throws IOException if the local server or session store fails
	 * @throws InterruptedException if the account request is interrupted
	 */
	@Test
	public void requiresPriorCheckForOfflineUse() throws IOException, InterruptedException
	{
		requireThat(checkHostResponse(503, Optional.empty(), Instant.parse("2026-09-25T12:00:00Z")).decision(),
			"noPriorCheck").
			isEqualTo(WorkOsAccountAccess.Decision.CONNECTIVITY_REQUIRED);
	}

	/**
	 * Keeps the checked login and account with both online and permitted offline decisions.
	 *
	 * @throws IOException if the local server or session store fails
	 * @throws InterruptedException if the account request is interrupted
	 */
	@Test
	public void returnsCheckedIdentityForDelivery() throws IOException, InterruptedException
	{
		Instant authorizedAt = Instant.parse("2026-09-25T12:00:00Z");
		WorkOsAccountAccess.AuthorizedAccount expected = new WorkOsAccountAccess.AuthorizedAccount(
			"workos:user_test", 17);
		AccessResult online = checkHostResponse(200, Optional.empty(), authorizedAt);
		AccessResult offline = checkHostResponse(503, Optional.of(authorizedAt), authorizedAt.plusSeconds(60));
		AccessResult denied = checkHostResponse(403, Optional.of(authorizedAt), authorizedAt.plusSeconds(60));

		requireThat(online.decision(), "onlineDecision").isEqualTo(WorkOsAccountAccess.Decision.ONLINE);
		requireThat(online.authorizedAccount(), "onlineAccount").isEqualTo(Optional.of(expected));
		requireThat(offline.authorizedAccount(), "offlineAccount").isEqualTo(Optional.of(expected));
		requireThat(denied.authorizedAccount(), "deniedAccount").isEmpty();
	}

	/**
	 * An explicit server denial clears the stored offline allowance.
	 *
	 * @throws IOException if the local server or session store fails
	 * @throws InterruptedException if the account request is interrupted
	 */
	@Test
	public void clearsOfflineUseAfterDenial() throws IOException, InterruptedException
	{
		Instant authorizedAt = Instant.parse("2026-09-25T12:00:00Z");
		AccessResult result = checkHostResponse(403, Optional.of(authorizedAt), authorizedAt.plusSeconds(60));
		requireThat(result.decision(), "decision").isEqualTo(WorkOsAccountAccess.Decision.ACCESS_DENIED);
		requireThat(result.lastAuthorizedAt(), "lastAuthorizedAt").isEmpty();
	}

	/**
	 * A rejected access token cannot retain offline access when token refresh also fails.
	 *
	 * @throws IOException if the local server or session store fails
	 * @throws InterruptedException if the account request is interrupted
	 */
	@Test
	public void clearsOfflineUseAfterRejectedToken() throws IOException, InterruptedException
	{
		Instant authorizedAt = Instant.parse("2026-09-25T12:00:00Z");
		AccessResult result = checkHostResponse(401, Optional.of(authorizedAt), authorizedAt.plusSeconds(60));
		requireThat(result.decision(), "decision").isEqualTo(WorkOsAccountAccess.Decision.VERIFICATION_FAILED);
		requireThat(result.lastAuthorizedAt(), "lastAuthorizedAt").isEmpty();
	}

	/**
	 * A response for the old account cannot authorize offline use after the user selects another account.
	 *
	 * @throws IOException if the local server or session store fails
	 * @throws InterruptedException if the account request is interrupted
	 */
	@Test
	public void rejectsOfflineUseAfterAccountSwitch() throws IOException, InterruptedException
	{
		Instant authorizedAt = Instant.parse("2026-09-25T12:00:00Z");
		AccessResult result = checkHostResponse(503, Optional.of(authorizedAt),
			authorizedAt.plusSeconds(60), true);
		requireThat(result.decision(), "decision").isEqualTo(WorkOsAccountAccess.Decision.CONNECTIVITY_REQUIRED);
		requireThat(result.lastAuthorizedAt(), "lastAuthorizedAt").isEmpty();
	}

	/**
	 * A response for an account replaced during the request cannot produce an event owner.
	 *
	 * @throws IOException if the local server or session store fails
	 * @throws InterruptedException if the account request is interrupted
	 */
	@Test
	public void rejectsOnlineAccountSwitch() throws IOException, InterruptedException
	{
		Instant now = Instant.parse("2026-09-25T12:00:00Z");
		AccessResult result = checkHostResponse(200, Optional.empty(), now, true);
		requireThat(result.decision(), "decision").isEqualTo(WorkOsAccountAccess.Decision.VERIFICATION_FAILED);
		requireThat(result.authorizedAccount(), "authorizedAccount").isEmpty();
	}

	/**
	 * An expired access token is refreshed before the host decides whether the login still owns the account.
	 *
	 * @throws IOException if the local host, provider, or session store fails
	 * @throws InterruptedException if either HTTP request is interrupted
	 */
	@Test
	public void refreshesRejectedAccessToken() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-refresh-access-"));
			WorkOsSessionStore sessions = new WorkOsSessionStore(root.resolve("auth"));
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/cat/v1/accounts", exchange ->
			{
				if ("Bearer access-new".equals(exchange.getRequestHeaders().getFirst("Authorization")))
					respond(exchange, 200, "{\"accounts\":[{\"id\":17,\"email\":\"user@example.com\"," +
						"\"verified\":true}]}");
				else
					respond(exchange, 401, "{}");
			});
			server.createContext("/user_management/authenticate", exchange -> respond(exchange, 200,
				"{\"user\":{\"id\":\"user_test\"},\"access_token\":\"access-new\"," +
					"\"refresh_token\":\"refresh-new\"}"));
			try
			{
				server.start();
				URI origin = URI.create("http://localhost:" + server.getAddress().getPort());
				sessions.save(new WorkOsSessionStore.Session(origin.resolve("/cat/"), "client_test",
					new WorkOsDeviceAuthClient.Tokens("user_test", "access-old", "refresh-old"),
					OptionalLong.of(17)));
				Instant now = Instant.parse("2026-09-25T12:00:00Z");
				try (HttpClient http = HttpClient.newHttpClient())
				{
					WorkOsAccountAccess access = new WorkOsAccountAccess(http, origin, sessions,
						Clock.fixed(now, ZoneOffset.UTC));
					requireThat(access.check(), "authorization").isEqualTo(WorkOsAccountAccess.Decision.ONLINE);
				}
				requireThat(sessions.load().orElseThrow().tokens().accessToken(), "accessToken").
					isEqualTo("access-new");
				requireThat(sessions.load().orElseThrow().lastAuthorizedAt(), "lastAuthorizedAt").
					isEqualTo(Optional.of(now));
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * Writes one local host or provider HTTP response.
	 *
	 * @param exchange request being answered
	 * @param status HTTP status sent to the client
	 * @param body response body
	 * @throws IOException if the response cannot be written
	 */
	private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
		throws IOException
	{
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream output = exchange.getResponseBody())
		{
			output.write(bytes);
		}
	}

	/**
	 * Runs an account check against a local server returning the selected status.
	 *
	 * @param status account server's HTTP status
	 * @param authorizedAt prior confirmed authorization, or empty when none exists
	 * @param now time of the account request
	 * @return decision and retained authorization after the check
	 * @throws IOException if the test-owned server or saved login fails
	 * @throws InterruptedException if the HTTP request is interrupted
	 */
	private static AccessResult checkHostResponse(int status, Optional<Instant> authorizedAt, Instant now)
		throws IOException, InterruptedException
	{
		return checkHostResponse(status, authorizedAt, now, false);
	}

	/**
	 * Runs one account check, optionally changing account selection before the response.
	 *
	 * @param status account server's HTTP status
	 * @param authorizedAt prior confirmed authorization
	 * @param now time of the account request
	 * @param switchAccount whether the user selects another account during the request
	 * @return decision and retained authorization after the check
	 * @throws IOException if the test-owned server or saved login fails
	 * @throws InterruptedException if the HTTP request is interrupted
	 */
	private static AccessResult checkHostResponse(int status, Optional<Instant> authorizedAt, Instant now,
		boolean switchAccount) throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-offline-access-"));
			WorkOsSessionStore sessions = new WorkOsSessionStore(root.resolve("auth"));
			HttpServer host = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			AtomicReference<WorkOsSessionStore.Session> selected = new AtomicReference<>();
			host.createContext("/cat/v1/accounts", exchange ->
			{
				if (switchAccount)
					sessions.selectIfCurrent(selected.get(), 18);
				if (status == 200)
					respond(exchange, status, "{\"accounts\":[{\"id\":17,\"email\":\"user@example.com\"," +
						"\"verified\":true}]}");
				else
				{
					exchange.sendResponseHeaders(status, -1);
					exchange.close();
				}
			});
			try
			{
				host.start();
				URI base = URI.create("http://localhost:" + host.getAddress().getPort() + "/cat/");
				WorkOsDeviceAuthClient.Tokens tokens = new WorkOsDeviceAuthClient.Tokens("user_test",
					"access", "refresh");
				WorkOsSessionStore.Session login = new WorkOsSessionStore.Session(base, "client_test", tokens,
					OptionalLong.of(17), authorizedAt);
				sessions.save(login);
				selected.set(login);
				try (HttpClient http = HttpClient.newHttpClient())
				{
					URI providerOrigin = URI.create("http://localhost:" + host.getAddress().getPort());
					WorkOsAccountAccess access = new WorkOsAccountAccess(http, providerOrigin, sessions,
						Clock.fixed(now, ZoneOffset.UTC));
					WorkOsAccountAccess.CheckResult result = access.checkForDelivery();
					return new AccessResult(result.decision(), result.authorizedAccount(),
						sessions.load().orElseThrow().lastAuthorizedAt());
				}
			}
			finally
			{
				host.stop(0);
			}
		}
	}

	/**
	 * Captures whether a host response left an offline allowance for the account.
	 *
	 * @param decision product access decision
	 * @param authorizedAccount identity that may receive features, if access was granted
	 * @param lastAuthorizedAt stored successful check after that decision
	 */
	private record AccessResult(WorkOsAccountAccess.Decision decision,
		Optional<WorkOsAccountAccess.AuthorizedAccount> authorizedAccount, Optional<Instant> lastAuthorizedAt)
	{
	}
}
