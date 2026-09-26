package io.github.cowwoc.cat.common.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks discovery of the WorkOS application accepted by a mounted HoldMySpot host.
 */
public final class WorkOsClientConfigurationClientTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public WorkOsClientConfigurationClientTest()
	{
	}

	/**
	 * Fetches the public client ID without a login token from the mounted host route.
	 *
	 * @throws IOException if the local HTTP exchange fails
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test
	public void discoversClientIdBeforeLogin() throws IOException, InterruptedException
	{
		AtomicReference<String> method = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/cat/v1/workos-client-configuration", exchange ->
		{
			method.set(exchange.getRequestMethod());
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			respond(exchange, 200, "{\"client_id\":\"client_beta123\"}");
		});
		try
		{
			server.start();
			try (HttpClient http = HttpClient.newHttpClient())
			{
				URI base = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
				requireThat(new WorkOsClientConfigurationClient(http, base).fetchClientId(), "clientId").
					isEqualTo("client_beta123");
			}
			requireThat(method.get(), "method").isEqualTo("GET");
			requireThat(authorization.get(), "authorization").isNull();
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * An invalid configuration cannot start a device authorization.
	 *
	 * @throws IOException for the invalid response
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "HoldMySpot host returned no WorkOS client ID")
	public void rejectsMissingClientId() throws IOException, InterruptedException
	{
		fetchFromServer(200, "{}");
	}

	/**
	 * A failed configuration lookup cannot be treated as a valid login setup.
	 *
	 * @throws IOException for the server failure
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "HoldMySpot client configuration returned HTTP 503")
	public void rejectsUnavailableConfiguration() throws IOException, InterruptedException
	{
		fetchFromServer(503, "{\"error\":\"temporarily_unavailable\"}");
	}

	/**
	 * Fetches one local provider-configuration response.
	 *
	 * @param status HTTP status
	 * @param body response body
	 * @return published client ID
	 * @throws IOException if the response cannot be accepted
	 * @throws InterruptedException if the request is interrupted
	 */
	private static String fetchFromServer(int status, String body) throws IOException, InterruptedException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/v1/workos-client-configuration", exchange -> respond(exchange, status, body));
		try
		{
			server.start();
			try (HttpClient http = HttpClient.newHttpClient())
			{
				URI base = URI.create("http://localhost:" + server.getAddress().getPort());
				return new WorkOsClientConfigurationClient(http, base).fetchClientId();
			}
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * Writes one response from the local HoldMySpot host.
	 *
	 * @param exchange local HTTP request
	 * @param status HTTP status
	 * @param body JSON response
	 * @throws IOException if the local response cannot be written
	 */
	private static void respond(HttpExchange exchange, int status, String body) throws IOException
	{
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
