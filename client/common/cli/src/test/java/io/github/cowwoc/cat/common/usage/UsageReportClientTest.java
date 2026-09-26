package io.github.cowwoc.cat.common.usage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the report transport against a local server response boundary.
 */
public final class UsageReportClientTest
{
	private static final UUID TURN_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");
	private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000456");
	private static final UsageReportOutbox.PendingReport REPORT = new UsageReportOutbox.PendingReport(EVENT_ID, 42,
		"{\"product_id\":\"product-a\",\"harness_id\":\"harness-a\",\"turn_id\":\"" + TURN_ID +
			"\",\"turn_output_tokens_so_far\":42,\"event_id\":\"" + EVENT_ID + "\"}");

	/**
	 * Creates the TestNG test class.
	 */
	public UsageReportClientTest()
	{
	}

	/**
	 * Sends the selected account through a product mount and accepts its committed acknowledgement.
	 *
	 * @throws IOException if the local fixture cannot run
	 * @throws InterruptedException if the HTTP exchange is interrupted
	 */
	@Test
	public void confirmsExactDelivery() throws IOException, InterruptedException
	{
		AtomicReference<String> requestBody = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> selectedAccount = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		try
		{
			server.createContext("/cat/v1/verified-usage-reports", exchange ->
			{
				requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
				selectedAccount.set(exchange.getRequestHeaders().getFirst("X-HoldMySpot-Account-ID"));
				respond(exchange, 200, "{\"event_id\":\"" + EVENT_ID +
					"\",\"accounted_output_tokens\":42}");
			});
			server.start();

			URI base = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
			UsageReportClient client = new UsageReportClient(HttpClient.newHttpClient(), base);
			requireThat(client.send("secret-session", 17, REPORT), "acknowledgement").
				isEqualTo(new UsageReportOutbox.Acknowledgement(EVENT_ID, 42));
			requireThat(authorization.get(), "authorization").isEqualTo("Bearer secret-session");
			requireThat(selectedAccount.get(), "selectedAccount").isEqualTo("17");
			requireThat(requestBody.get(), "reportBody").contains("\"product_id\":\"product-a\"").
				contains("\"harness_id\":\"harness-a\"").
				contains("\"turn_id\":\"" + TURN_ID + "\"").
				contains("\"event_id\":\"" + EVENT_ID + "\"").
				contains("\"turn_output_tokens_so_far\":42");
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * A lower report can be acknowledged with the turn's higher committed total.
	 *
	 * @throws IOException if the local fixture cannot run
	 * @throws InterruptedException if the HTTP exchange is interrupted
	 */
	@Test
	public void acceptsStaleTotal() throws IOException, InterruptedException
	{
		UsageReportOutbox.Acknowledgement acknowledgement = sendOne(200,
			"{\"event_id\":\"" + EVENT_ID + "\",\"accounted_output_tokens\":50}");
		requireThat(acknowledgement, "acknowledgement").
			isEqualTo(new UsageReportOutbox.Acknowledgement(EVENT_ID, 50));
	}

	/**
	 * Rejects a response for another delivery event.
	 *
	 * @throws IOException for the unconfirmed delivery
	 * @throws InterruptedException if the HTTP exchange is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Usage server did not confirm this delivery and count")
	public void rejectsWrongEvent() throws IOException, InterruptedException
	{
		sendOne(200, "{\"event_id\":\"" + TURN_ID + "\",\"accounted_output_tokens\":42}");
	}

	/**
	 * Rejects a committed turn total lower than the reported cumulative count.
	 *
	 * @throws IOException for the impossible acknowledgement
	 * @throws InterruptedException if the HTTP exchange is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Usage server did not confirm this delivery and count")
	public void rejectsLowerCount() throws IOException, InterruptedException
	{
		sendOne(200, "{\"event_id\":\"" + EVENT_ID + "\",\"accounted_output_tokens\":41}");
	}

	/**
	 * A conflict response does not confirm the delivery.
	 *
	 * @throws IOException for the server conflict
	 * @throws InterruptedException if the HTTP exchange is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Usage server returned HTTP 409")
	public void rejectsHttpConflict() throws IOException, InterruptedException
	{
		sendOne(409, "{\"error\":\"delivery_event_conflict\"}");
	}

	/**
	 * Exposes a rejected bearer token separately so the caller may refresh it once.
	 *
	 * @throws IOException for the rejected access token
	 * @throws InterruptedException if the HTTP exchange is interrupted
	 */
	@Test(expectedExceptions = UsageReportClient.AuthenticationRequiredException.class,
		expectedExceptionsMessageRegExp = "Usage server rejected the access token with HTTP 401")
	public void identifiesRejectedAccessToken() throws IOException, InterruptedException
	{
		sendOne(401, "{\"error\":\"invalid_token\"}");
	}

	/**
	 * Rejects an invalid selected account before sending an HTTP request.
	 *
	 * @throws IOException if the invalid input unexpectedly reaches HTTP
	 * @throws InterruptedException if the HTTP exchange is interrupted
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Selected HoldMySpot account ID must be positive")
	public void rejectsInvalidAccount() throws IOException, InterruptedException
	{
		UsageReportClient client = new UsageReportClient(HttpClient.newHttpClient(),
			URI.create("https://example.invalid"));
		client.send("secret-session", 0, REPORT);
	}

	/**
	 * Sends one report to a local server with the selected response.
	 *
	 * @param status response status
	 * @param body response JSON
	 * @return accepted acknowledgement
	 * @throws IOException if the server fails or does not confirm the report
	 * @throws InterruptedException if the HTTP exchange is interrupted
	 */
	private static UsageReportOutbox.Acknowledgement sendOne(int status, String body) throws IOException,
		InterruptedException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		try
		{
			server.createContext("/v1/verified-usage-reports", exchange -> respond(exchange, status, body));
			server.start();
			URI base = URI.create("http://localhost:" + server.getAddress().getPort());
			UsageReportClient client = new UsageReportClient(HttpClient.newHttpClient(), base);
			return client.send("secret-session", 17, REPORT);
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * Writes one local server response.
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
