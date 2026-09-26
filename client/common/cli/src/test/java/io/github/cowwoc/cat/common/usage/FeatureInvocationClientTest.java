package io.github.cowwoc.cat.common.usage;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;

/**
 * Verifies feature-invocation transport at a local HTTP boundary.
 */
public final class FeatureInvocationClientTest
{
	private static final UUID INVOCATION_ID = UUID.fromString("672db61d-dfd4-4aec-90ba-8dbad66e2b22");

	/**
	 * Creates the TestNG test class.
	 */
	public FeatureInvocationClientTest()
	{
	}

	/**
	 * Sends the stable event and selected account, and accepts only the route's committed response.
	 *
	 * @throws IOException if the local server cannot run
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test
	public void confirmsFeatureInvocation() throws IOException, InterruptedException
	{
		AtomicReference<String> body = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> account = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/cat/v1/feature-invocations", exchange ->
		{
			body.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			account.set(exchange.getRequestHeaders().getFirst("X-HoldMySpot-Account-ID"));
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		try
		{
			server.start();
			URI base = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
			new FeatureInvocationClient(HttpClient.newHttpClient(), base).send("access", 17,
				"include/common/write-clearly.md", INVOCATION_ID);

			requireThat(authorization.get(), "authorization").isEqualTo("Bearer access");
			requireThat(account.get(), "account").isEqualTo("17");
			requireThat(body.get(), "body").contains("\"product_id\":\"cat\"").
				contains("\"feature_id\":\"include/common/write-clearly.md\"").
				contains("\"invocation_id\":\"" + INVOCATION_ID + "\"");
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * A non-committed response leaves the invocation available for retry.
	 *
	 * @throws IOException for the unconfirmed invocation
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Feature invocation server returned HTTP 503")
	public void rejectsUnavailableServer() throws IOException, InterruptedException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/cat/v1/feature-invocations", exchange ->
		{
			byte[] response = "unavailable".getBytes(UTF_8);
			exchange.sendResponseHeaders(503, response.length);
			try (OutputStream output = exchange.getResponseBody())
			{
				output.write(response);
			}
		});
		try
		{
			server.start();
			URI base = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
			new FeatureInvocationClient(HttpClient.newHttpClient(), base).send("access", 17,
				"include/common/write-clearly.md", INVOCATION_ID);
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * Distinguishes an expired access token so the caller can refresh it before retrying the same invocation.
	 *
	 * @throws IOException for the rejected token
	 * @throws InterruptedException if the request is interrupted
	 */
	@Test(expectedExceptions = FeatureInvocationClient.AuthenticationRequiredException.class)
	public void identifiesRejectedAccessToken() throws IOException, InterruptedException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/cat/v1/feature-invocations", exchange ->
		{
			exchange.sendResponseHeaders(401, -1);
			exchange.close();
		});
		try
		{
			server.start();
			URI base = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
			new FeatureInvocationClient(HttpClient.newHttpClient(), base).send("expired", 17,
				"include/common/write-clearly.md", INVOCATION_ID);
		}
		finally
		{
			server.stop(0);
		}
	}
}
