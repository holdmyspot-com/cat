package io.github.cowwoc.cat.common.usage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.auth.WorkOsDeviceAuthClient;
import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks authenticated report recovery across a rejected access token and process restart.
 */
public final class WorkOsUsageDeliveryTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public WorkOsUsageDeliveryTest()
	{
	}

	/**
	 * A rejected token rotates once and the retained report is confirmed with the new token.
	 *
	 * @throws IOException if the test-owned store or local HTTP server fails
	 * @throws InterruptedException if the HTTP request is interrupted
	 */
	@Test
	public void refreshesAndConfirmsPendingReport() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-workos-delivery-"));
			AtomicReference<String> eventId = new AtomicReference<>();
			AtomicInteger reportRequests = new AtomicInteger();
			AtomicInteger refreshRequests = new AtomicInteger();
			AtomicReference<String> refreshBody = new AtomicReference<>();
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			try
			{
				server.createContext("/cat/v1/verified-usage-reports", exchange ->
				{
					reportRequests.incrementAndGet();
					if ("Bearer access-first".equals(exchange.getRequestHeaders().getFirst("Authorization")))
						respond(exchange, 401, "{\"error\":\"invalid_token\"}");
					else
					{
						requireThat(exchange.getRequestHeaders().getFirst("Authorization"), "retriedAuthorization").
							isEqualTo("Bearer access-second");
						respond(exchange, 200, "{\"event_id\":\"" + eventId.get() +
							"\",\"accounted_output_tokens\":42}");
					}
				});
				server.createContext("/user_management/authenticate", exchange ->
				{
					refreshRequests.incrementAndGet();
					refreshBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
					respond(exchange, 200, "{\"user\":{\"id\":\"user_test\"}," +
						"\"access_token\":\"access-second\",\"refresh_token\":\"refresh-second\"}");
				});
				server.start();

				URI origin = URI.create("http://localhost:" + server.getAddress().getPort());
				WorkOsSessionStore sessions = new WorkOsSessionStore(root.resolve("auth"));
				sessions.save(new WorkOsSessionStore.Session(origin.resolve("/cat/"), "client_test",
					new WorkOsDeviceAuthClient.Tokens("user_test", "access-first", "refresh-first")));
				UsageReportOutbox outbox = new UsageReportOutbox(root.resolve("outbox"));
				UsageReportOutbox.PendingReport pending = outbox.enqueue("workos:user_test", 17,
					"cat/turn-1/42", 42, id -> "{\"event_id\":\"" + id + "\"}");
				eventId.set(pending.eventId().toString());

				WorkOsUsageDelivery restarted = new WorkOsUsageDelivery(HttpClient.newHttpClient(), origin,
					new WorkOsSessionStore(root.resolve("auth")), new UsageReportOutbox(root.resolve("outbox")));
				restarted.flush();
				restarted.flush();
				requireThat(reportRequests.get(), "reportRequests").isEqualTo(2);
				requireThat(refreshRequests.get(), "refreshRequests").isEqualTo(1);
				requireThat(refreshBody.get(), "refreshBody").contains("refresh_token=refresh-first").
					contains("client_id=client_test");
				requireThat(sessions.load().orElseThrow().tokens(), "savedTokens").isEqualTo(
					new WorkOsDeviceAuthClient.Tokens("user_test", "access-second", "refresh-second"));
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * A server conflict leaves the report pending and does not rotate the WorkOS session.
	 *
	 * @throws IOException for the rejected report or test-owned storage failure
	 * @throws InterruptedException if the HTTP request is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Usage server returned HTTP 409")
	public void retainsConflictWithoutRefreshing() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-workos-delivery-"));
			AtomicInteger refreshRequests = new AtomicInteger();
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			try
			{
				server.createContext("/cat/v1/verified-usage-reports", exchange ->
					respond(exchange, 409, "{\"error\":\"delivery_event_conflict\"}"));
				server.createContext("/user_management/authenticate", exchange ->
				{
					refreshRequests.incrementAndGet();
					respond(exchange, 500, "{}");
				});
				server.start();

				URI origin = URI.create("http://localhost:" + server.getAddress().getPort());
				WorkOsSessionStore sessions = new WorkOsSessionStore(root.resolve("auth"));
				sessions.save(new WorkOsSessionStore.Session(origin.resolve("/cat/"), "client_test",
					new WorkOsDeviceAuthClient.Tokens("user_test", "access-first", "refresh-first")));
				UsageReportOutbox outbox = new UsageReportOutbox(root.resolve("outbox"));
				UsageReportOutbox.PendingReport pending = outbox.enqueue("workos:user_test", 17,
					"cat/turn-1/42", 42, id -> "{\"event_id\":\"" + id + "\"}");
				WorkOsUsageDelivery delivery = new WorkOsUsageDelivery(HttpClient.newHttpClient(), origin,
					sessions, outbox);
				try
				{
					delivery.flush();
				}
				finally
				{
					requireThat(refreshRequests.get(), "refreshRequests").isEqualTo(0);
					requireThat(outbox.enqueue("workos:user_test", 17, "cat/turn-1/42", 42,
						id -> "{\"event_id\":\"" + id + "\"}"), "pendingReport").isEqualTo(pending);
				}
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * Writes one response from the local provider or host fixture.
	 *
	 * @param exchange local HTTP exchange
	 * @param status response status
	 * @param body response JSON
	 * @throws IOException if the response cannot be written
	 */
	private static void respond(HttpExchange exchange, int status, String body) throws IOException
	{
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream output = exchange.getResponseBody())
		{
			output.write(bytes);
		}
	}
}
