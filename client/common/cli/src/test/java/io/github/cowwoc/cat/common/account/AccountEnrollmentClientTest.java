package io.github.cowwoc.cat.common.account;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the account-registration and discovery handoff at a local HTTP boundary.
 */
public final class AccountEnrollmentClientTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public AccountEnrollmentClientTest()
	{
	}

	/**
	 * A created account appears in the list returned to the login flow.
	 *
	 * @throws IOException if the local server or client fails
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test
	public void listsNewlyCreatedAccount() throws IOException, InterruptedException
	{
		AtomicInteger requests = new AtomicInteger();
		HttpServer server = server(201, "{\"id\":17}", """
			{"accounts":[{"id":17,"email":"first@example.com","verified":true}]}
			""", requests);
		try
		{
			server.start();
			try (HttpClient http = HttpClient.newHttpClient())
			{
				AccountEnrollmentClient client = new AccountEnrollmentClient(http, origin(server));
				requireThat(client.enroll("session-token"), "accounts").isEqualTo(List.of(
					new AccountDirectoryClient.LinkedAccount(17, "first@example.com", true)));
			}
			requireThat(requests.get(), "requestCount").isEqualTo(2);
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * Requests the public API path when a reverse proxy mounts the service under a product prefix.
	 *
	 * @throws IOException if the local server or client fails
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test
	public void enrollsThroughMountedApiPath() throws IOException, InterruptedException
	{
		AtomicInteger requests = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/cat/v1/accounts", exchange ->
		{
			if (requests.incrementAndGet() == 1)
				respond(exchange, 201, "{\"id\":37}");
			else
				respond(exchange, 200,
					"{\"accounts\":[{\"id\":37,\"email\":\"mounted@example.com\",\"verified\":true}]}");
		});
		try
		{
			server.start();
			try (HttpClient http = HttpClient.newHttpClient())
			{
				AccountEnrollmentClient client = new AccountEnrollmentClient(http, origin(server).resolve("/cat/"));
				requireThat(client.enroll("session-token"), "accounts").isEqualTo(List.of(
					new AccountDirectoryClient.LinkedAccount(37, "mounted@example.com", true)));
			}
			requireThat(requests.get(), "requestCount").isEqualTo(2);
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * An existing linked login still receives its current account list.
	 *
	 * @throws IOException if the local server or client fails
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test
	public void listsExistingAccount() throws IOException, InterruptedException
	{
		HttpServer server = server(204, "", """
			{"accounts":[{"id":21,"email":"existing@example.com","verified":true}]}
			""", new AtomicInteger());
		try
		{
			server.start();
			try (HttpClient http = HttpClient.newHttpClient())
			{
				AccountEnrollmentClient client = new AccountEnrollmentClient(http, origin(server));
				requireThat(client.enroll("session-token"), "accounts").isEqualTo(List.of(
					new AccountDirectoryClient.LinkedAccount(21, "existing@example.com", true)));
			}
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * A created ID missing from discovery cannot be used for account selection.
	 *
	 * @throws IOException for an inconsistent server response
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "New account is missing from the linked account list")
	public void rejectsMissingCreatedAccount() throws IOException, InterruptedException
	{
		HttpServer server = server(201, "{\"id\":17}", """
			{"accounts":[{"id":18,"email":"other@example.com","verified":true}]}
			""", new AtomicInteger());
		try
		{
			server.start();
			try (HttpClient http = HttpClient.newHttpClient())
			{
				new AccountEnrollmentClient(http, origin(server)).enroll("session-token");
			}
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * Represents the registration and account-list routes with one local HTTP server.
	 *
	 * @param registrationStatus POST response status
	 * @param registrationBody POST response body
	 * @param listBody GET response body
	 * @param requests completed request count
	 * @return local server ready to start
	 * @throws IOException if a port cannot be reserved
	 */
	private static HttpServer server(int registrationStatus, String registrationBody, String listBody,
		AtomicInteger requests) throws IOException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/v1/accounts", exchange ->
		{
			int request = requests.incrementAndGet();
			requireThat(exchange.getRequestHeaders().getFirst("Authorization"), "authorization").
				isEqualTo("Bearer session-token");
			if (request == 1)
			{
				requireThat(exchange.getRequestMethod(), "registrationMethod").isEqualTo("POST");
				respond(exchange, registrationStatus, registrationBody);
			}
			else
			{
				requireThat(exchange.getRequestMethod(), "discoveryMethod").isEqualTo("GET");
				respond(exchange, 200, listBody);
			}
		});
		return server;
	}

	/**
	 * Returns the local test server's origin.
	 *
	 * @param server started local server
	 * @return HTTP origin
	 */
	private static URI origin(HttpServer server)
	{
		return URI.create("http://localhost:" + server.getAddress().getPort());
	}

	/**
	 * Writes one response from the local account server.
	 *
	 * @param exchange local request
	 * @param status HTTP status
	 * @param body response body
	 * @throws IOException if the local response cannot be written
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
