package io.github.cowwoc.cat.common.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks that a saved account choice comes from the host's linked-account list.
 */
public final class WorkOsAccountSelectorTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public WorkOsAccountSelectorTest()
	{
	}

	/**
	 * A linked account survives restart, while an unlinked choice leaves it unchanged.
	 *
	 * @throws IOException for the unlinked account or fixture failure
	 * @throws InterruptedException if the local HTTP exchange is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "HoldMySpot account 31 is not linked to the current WorkOS login")
	public void selectsOnlyHostLinkedAccount() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-account-selection-"));
			AtomicInteger requests = new AtomicInteger();
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			try
			{
				server.createContext("/cat/v1/accounts", exchange ->
				{
					requests.incrementAndGet();
					requireThat(exchange.getRequestHeaders().getFirst("Authorization"), "authorization").
						isEqualTo("Bearer access-first");
					respond(exchange, "{\"accounts\":[{\"id\":17,\"email\":\"one@example.com\"," +
						"\"verified\":true},{\"id\":29,\"email\":\"two@example.com\"," +
						"\"verified\":true}]}");
				});
				server.start();

				URI serverBase = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
				WorkOsSessionStore sessions = new WorkOsSessionStore(root.resolve("auth"));
				sessions.save(new WorkOsSessionStore.Session(serverBase, "client_test",
					new WorkOsDeviceAuthClient.Tokens("user_test", "access-first", "refresh-first")));
				WorkOsAccountSelector selector = new WorkOsAccountSelector(HttpClient.newHttpClient(), sessions);
				selector.select(29);
				requireThat(new WorkOsSessionStore(root.resolve("auth")).load().orElseThrow().selectedAccountId(),
					"selectedAccount").isEqualTo(OptionalLong.of(29));

				try
				{
					selector.select(31);
				}
				finally
				{
					requireThat(requests.get(), "accountListRequests").isEqualTo(2);
					requireThat(sessions.load().orElseThrow().selectedAccountId(), "retainedSelection").
						isEqualTo(OptionalLong.of(29));
				}
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * A new login during account lookup keeps the earlier user's choice out of the new session.
	 *
	 * @throws IOException for the changed login or fixture failure
	 * @throws InterruptedException if the local HTTP exchange is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "WorkOS login changed while selecting an account; retry the selection")
	public void rejectsSelectionAfterLoginChange() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-account-selection-"));
			WorkOsSessionStore sessions = new WorkOsSessionStore(root.resolve("auth"));
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			try
			{
				URI serverBase = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
				sessions.save(new WorkOsSessionStore.Session(serverBase, "client_test",
					new WorkOsDeviceAuthClient.Tokens("user_first", "access-first", "refresh-first")));
				server.createContext("/cat/v1/accounts", exchange ->
				{
					sessions.save(new WorkOsSessionStore.Session(serverBase, "client_test",
						new WorkOsDeviceAuthClient.Tokens("user_second", "access-second", "refresh-second")));
					respond(exchange, "{\"accounts\":[{\"id\":17,\"email\":\"one@example.com\"," +
						"\"verified\":true}]}");
				});
				server.start();

				try
				{
					new WorkOsAccountSelector(HttpClient.newHttpClient(), sessions).select(17);
				}
				finally
				{
					WorkOsSessionStore.Session saved = sessions.load().orElseThrow();
					requireThat(saved.tokens().userId(), "savedUser").isEqualTo("user_second");
					requireThat(saved.selectedAccountId(), "savedSelection").isEqualTo(OptionalLong.empty());
				}
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * Writes a linked-account response from the local host fixture.
	 *
	 * @param exchange local HTTP exchange
	 * @param body linked-account JSON
	 * @throws IOException if the response cannot be written
	 */
	private static void respond(HttpExchange exchange, String body) throws IOException
	{
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream output = exchange.getResponseBody())
		{
			output.write(bytes);
		}
	}
}
