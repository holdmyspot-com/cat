package io.github.cowwoc.cat.common.account;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks account discovery against a local HTTP server speaking the host contract.
 */
public final class AccountDirectoryClientTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public AccountDirectoryClientTest()
	{
	}

	/**
	 * Requests accounts with the bearer token and retains the server's selected IDs and current emails.
	 *
	 * @throws IOException if the local HTTP exchange fails
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test
	public void listsLinkedAccounts() throws IOException, InterruptedException
	{
		AtomicReference<String> method = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> selection = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		try
		{
			server.createContext("/v1/accounts", exchange ->
			{
				method.set(exchange.getRequestMethod());
				authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
				selection.set(exchange.getRequestHeaders().getFirst("X-HoldMySpot-Account-ID"));
				respond(exchange, 200, """
					{"accounts":[{"id":17,"email":"first@example.com","verified":true},
					             {"id":25,"email":"second@example.com","verified":false}]}
					""");
			});
			server.start();

			AccountDirectoryClient client = new AccountDirectoryClient(HttpClient.newHttpClient(),
				URI.create("http://localhost:" + server.getAddress().getPort()));
			requireThat(client.list("session-token"), "linkedAccounts").isEqualTo(List.of(
				new AccountDirectoryClient.LinkedAccount(17, "first@example.com", true),
				new AccountDirectoryClient.LinkedAccount(25, "second@example.com", false)));
			requireThat(method.get(), "requestMethod").isEqualTo("GET");
			requireThat(authorization.get(), "authorization").isEqualTo("Bearer session-token");
			requireThat(selection.get(), "accountSelectionHeader").isNull();
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * A failed server response must not provide account choices.
	 *
	 * @throws IOException for the rejected server response
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test(expectedExceptions = AccountDirectoryClient.AuthenticationRequiredException.class,
		expectedExceptionsMessageRegExp = "Account directory returned HTTP 401")
	public void rejectsUnauthorizedList() throws IOException, InterruptedException
	{
		listFromServer(401, "{\"error\":\"unauthenticated\"}");
	}

	/**
	 * A server-side access denial is distinguishable from an outage.
	 *
	 * @throws IOException for the denied account request
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test(expectedExceptions = AccountDirectoryClient.AccessDeniedException.class)
	public void identifiesDeniedAccess() throws IOException, InterruptedException
	{
		listFromServer(403, "{\"error\":\"forbidden\"}");
	}

	/**
	 * A temporary host failure is distinguishable from a rejected login, so product access can use offline allowance.
	 *
	 * @throws IOException for the unavailable host
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test(expectedExceptions = AccountDirectoryClient.UnavailableException.class)
	public void identifiesUnavailableHost() throws IOException, InterruptedException
	{
		listFromServer(503, "{\"error\":\"unavailable\"}");
	}

	/**
	 * Duplicate IDs cannot be presented as distinct account choices.
	 *
	 * @throws IOException for the invalid server response
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Account directory returned invalid account list")
	public void rejectsDuplicateAccountIds() throws IOException, InterruptedException
	{
		listFromServer(200, """
			{"accounts":[{"id":17,"email":"first@example.com","verified":true},
			             {"id":17,"email":"other@example.com","verified":true}]}
			""");
	}

	/**
	 * A nonlocal plain-HTTP origin cannot receive a provider session token.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "HoldMySpot server base must be an HTTPS service origin")
	public void rejectsPlainHttpRemoteOrigin()
	{
		new AccountDirectoryClient(HttpClient.newHttpClient(), URI.create("http://example.com"));
	}

	/**
	 * A mount without a trailing slash would make Java resolve the API at the host root.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "HoldMySpot server mount must end in / and use lowercase path segments")
	public void rejectsMountWithoutTrailingSlash()
	{
		new AccountDirectoryClient(HttpClient.newHttpClient(), URI.create("https://api.holdmyspot.com/cat"));
	}

	/**
	 * Requests one server-selected response.
	 *
	 * @param status HTTP status
	 * @param body response JSON
	 * @return parsed accounts
	 * @throws IOException if the response is invalid
	 * @throws InterruptedException if the request is interrupted
	 */
	private static List<AccountDirectoryClient.LinkedAccount> listFromServer(int status, String body)
		throws IOException, InterruptedException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		try
		{
			server.createContext("/v1/accounts", exchange -> respond(exchange, status, body));
			server.start();
			AccountDirectoryClient client = new AccountDirectoryClient(HttpClient.newHttpClient(),
				URI.create("http://localhost:" + server.getAddress().getPort()));
			return client.list("session-token");
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * Writes the local server's account-list response.
	 *
	 * @param exchange local HTTP exchange
	 * @param status HTTP status
	 * @param body response JSON
	 * @throws IOException if the response cannot be written
	 */
	private static void respond(HttpExchange exchange, int status, String body) throws IOException
	{
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream output = exchange.getResponseBody())
		{
			output.write(bytes);
		}
	}
}
