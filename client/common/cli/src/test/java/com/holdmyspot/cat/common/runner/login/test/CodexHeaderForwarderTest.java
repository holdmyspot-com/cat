package com.holdmyspot.cat.common.runner.login.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.runner.login.CodexHeaderForwarder;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.testng.annotations.Test;

/**
 * Tests that {@link CodexHeaderForwarder} passes a session's request on to its provider with the provider's secret
 * headers and bearer token in place of the session's token, and passes on nothing for a request without a session's
 * token.
 */
public final class CodexHeaderForwarderTest
{
	private static final String SESSION_TOKEN = "fixture-session-token";
	private static final String HEADER_SECRET = "fixture-header-secret";
	private static final String BEARER = "fixture-provider-bearer";

	/**
	 * Creates the test.
	 */
	public CodexHeaderForwarderTest()
	{
	}

	/**
	 * A request that the provider received.
	 *
	 * @param method  the request's method
	 * @param path    the request's path and query
	 * @param headers the request's headers, with names in lower case
	 * @param body    the request's body
	 */
	private record Received(String method, String path, Map<String, List<String>> headers, String body)
	{
	}

	/**
	 * A provider that records each request and answers with a fixed body.
	 */
	private static final class Provider implements AutoCloseable
	{
		private final HttpServer server;
		private final List<Received> received = new CopyOnWriteArrayList<>();

		/**
		 * Starts a provider on the loopback interface.
		 *
		 * @throws IOException if it cannot listen
		 */
		Provider() throws IOException
		{
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.createContext("/", exchange ->
			{
				try (exchange)
				{
					Map<String, List<String>> headers = new TreeMap<>();
					for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet())
						headers.put(header.getKey().toLowerCase(Locale.ROOT), header.getValue());
					received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers,
						new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
					byte[] body = "data: provider-answer\n\n".getBytes(UTF_8);
					exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
					exchange.sendResponseHeaders(200, body.length);
					try (OutputStream output = exchange.getResponseBody())
					{
						output.write(body);
					}
				}
			});
			server.start();
		}

		/**
		 * Returns the provider's base URL.
		 *
		 * @return the URL
		 */
		URI baseUrl()
		{
			return URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" +
				server.getAddress().getPort() + "/v1");
		}

		@Override
		public void close()
		{
			server.stop(0);
		}
	}

	/**
	 * Sends a request to the forwarder.
	 *
	 * @param client        the client
	 * @param forwarder     the forwarder
	 * @param authorization the request's {@code Authorization} header, or empty
	 * @return the answer
	 * @throws IOException          if the request fails
	 * @throws InterruptedException if the request is interrupted
	 */
	private static HttpResponse<String> send(HttpClient client, CodexHeaderForwarder forwarder,
		Optional<String> authorization) throws IOException, InterruptedException
	{
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(forwarder.address() + "/responses?stream=1")).
			header("Content-Type", "application/json").header("X-Session-Header", "kept").
			POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"fixture\"}"));
		authorization.ifPresent(value -> request.header("Authorization", value));
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Verifies that a request with a session's token reaches the provider with the secret headers and bearer token
	 * added and the session's token removed, on a loopback listener.
	 *
	 * @throws IOException          if the fixture cannot be created
	 * @throws InterruptedException if a request is interrupted
	 */
	@Test
	public void forwardsWithSecretHeaders() throws IOException, InterruptedException
	{
		// Requirement: R-I15
		try (Provider provider = new Provider();
			CodexHeaderForwarder forwarder = CodexHeaderForwarder.start();
			HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build())
		{
			requireThat(forwarder.listener().isLoopbackAddress(), "loopback").isTrue();
			forwarder.register(SESSION_TOKEN, provider.baseUrl(), Map.of("X-Api-Key", HEADER_SECRET),
				Optional.of(() -> BEARER));

			HttpResponse<String> response = send(client, forwarder, Optional.of("Bearer " + SESSION_TOKEN));
			requireThat(response.statusCode(), "status").isEqualTo(200);
			requireThat(response.body(), "body").isEqualTo("data: provider-answer\n\n");
			requireThat(provider.received.size(), "received").isEqualTo(1);
			Received received = provider.received.getFirst();
			requireThat(received.method(), "method").isEqualTo("POST");
			requireThat(received.path(), "path").isEqualTo("/v1/responses?stream=1");
			requireThat(received.body(), "requestBody").isEqualTo("{\"model\":\"fixture\"}");
			requireThat(received.headers().get("x-api-key"), "secretHeader").isEqualTo(List.of(HEADER_SECRET));
			requireThat(received.headers().get("authorization"), "authorization").
				isEqualTo(List.of("Bearer " + BEARER));
			requireThat(received.headers().get("x-session-header"), "sessionHeader").isEqualTo(List.of("kept"));
			requireThat(received.headers().toString(), "headers").doesNotContain(SESSION_TOKEN);
		}
	}

	/**
	 * Verifies that a request with no token, a wrong one or an ended session's gets a refusal and never reaches the
	 * provider.
	 *
	 * @throws IOException          if the fixture cannot be created
	 * @throws InterruptedException if a request is interrupted
	 */
	@Test
	public void refusesWithoutSessionToken() throws IOException, InterruptedException
	{
		// Requirement: R-I15
		try (Provider provider = new Provider();
			CodexHeaderForwarder forwarder = CodexHeaderForwarder.start();
			HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build())
		{
			forwarder.register(SESSION_TOKEN, provider.baseUrl(), Map.of("X-Api-Key", HEADER_SECRET),
				Optional.empty());
			for (Optional<String> authorization : List.of(Optional.<String>empty(), Optional.of("Bearer wrong"),
				Optional.of(SESSION_TOKEN)))
			{
				HttpResponse<String> response = send(client, forwarder, authorization);
				requireThat(response.statusCode(), "status").isEqualTo(401);
				requireThat(response.body(), "body").doesNotContain(HEADER_SECRET);
			}
			forwarder.unregister(SESSION_TOKEN);
			HttpResponse<String> ended = send(client, forwarder, Optional.of("Bearer " + SESSION_TOKEN));
			requireThat(ended.statusCode(), "endedStatus").isEqualTo(401);
			requireThat(provider.received, "received").isEmpty();
		}
	}
}
