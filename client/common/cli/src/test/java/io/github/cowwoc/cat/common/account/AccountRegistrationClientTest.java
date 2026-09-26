package io.github.cowwoc.cat.common.account;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the account-registration request and response at a local HTTP boundary.
 */
public final class AccountRegistrationClientTest
{
	/**
	 * Creates the registration client test fixture.
	 */
	public AccountRegistrationClientTest()
	{
	}

	/**
	 * A verified login registers with its bearer token and receives the created account ID.
	 *
	 * @throws IOException if the local server or request fails
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test
	public void registersVerifiedLogin() throws IOException, InterruptedException
	{
		AtomicReference<String> method = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		try
		{
			server.createContext("/v1/accounts", exchange ->
			{
				method.set(exchange.getRequestMethod());
				authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
				requireThat(exchange.getRequestBody().readAllBytes().length, "requestBodySize").isZero();
				respond(exchange, 201, "{\"id\":17}");
			});
			server.start();
			try (HttpClient http = HttpClient.newHttpClient())
			{
				AccountRegistrationClient client = new AccountRegistrationClient(http,
					URI.create("http://localhost:" + server.getAddress().getPort()));
				requireThat(client.register("session-token"), "createdAccount").isEqualTo(OptionalLong.of(17));
			}
			requireThat(method.get(), "requestMethod").isEqualTo("POST");
			requireThat(authorization.get(), "authorization").isEqualTo("Bearer session-token");
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * An already linked login is confirmed without inventing another account ID.
	 *
	 * @throws IOException if the local server or request fails
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test
	public void acceptsExistingAccount() throws IOException, InterruptedException
	{
		requireThat(registerFromServer(204, ""), "existingAccount").isEqualTo(OptionalLong.empty());
	}

	/**
	 * A failed registration cannot be treated as an existing account.
	 *
	 * @throws IOException for the rejected response
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Account registration returned HTTP 409")
	public void rejectsUnverifiedSignup() throws IOException, InterruptedException
	{
		registerFromServer(409, "{\"error\":\"email_unverified\"}");
	}

	/**
	 * A malformed created-account ID cannot be used for account selection.
	 *
	 * @throws IOException for the malformed response
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Account registration returned invalid account ID")
	public void rejectsMalformedCreatedId() throws IOException, InterruptedException
	{
		registerFromServer(201, "{\"id\":\"17\"}");
	}

	/**
	 * Sends one selected server response to the registration client.
	 *
	 * @param status HTTP status
	 * @param body response body
	 * @return registration result
	 * @throws IOException if the local server or request fails
	 * @throws InterruptedException if the request is interrupted
	 */
	private static OptionalLong registerFromServer(int status, String body) throws IOException, InterruptedException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		try
		{
			server.createContext("/v1/accounts", exchange -> respond(exchange, status, body));
			server.start();
			try (HttpClient http = HttpClient.newHttpClient())
			{
				AccountRegistrationClient client = new AccountRegistrationClient(http,
					URI.create("http://localhost:" + server.getAddress().getPort()));
				return client.register("session-token");
			}
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * Writes one local account-registration response.
	 *
	 * @param exchange local HTTP exchange
	 * @param status HTTP status
	 * @param body response body
	 * @throws IOException if the response cannot be written
	 */
	private static void respond(HttpExchange exchange, int status, String body) throws IOException
	{
		if (status == 204)
		{
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
			return;
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
