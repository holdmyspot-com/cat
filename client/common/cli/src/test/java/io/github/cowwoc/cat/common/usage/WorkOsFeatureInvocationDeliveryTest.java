package io.github.cowwoc.cat.common.usage;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.auth.WorkOsDeviceAuthClient;
import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;

/**
 * Checks retained rule-delivery events across an expired WorkOS access token.
 */
public final class WorkOsFeatureInvocationDeliveryTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public WorkOsFeatureInvocationDeliveryTest()
	{
	}

	/**
	 * Refreshes once, retries the same invocation for its original account, and leaves another user's event pending.
	 *
	 * @throws IOException if test-owned storage or HTTP setup fails
	 * @throws InterruptedException if an HTTP request is interrupted
	 */
	@Test
	public void refreshesAndConfirmsOriginalInvocation() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-feature-delivery-"));
			Path outboxDirectory = root.resolve("outbox");
			FeatureInvocationOutbox outbox = new FeatureInvocationOutbox(outboxDirectory);
			UUID invocationId = outbox.enqueue("workos:user_test", 17, "common/default-rules.md");
			UUID otherUserId = outbox.enqueue("workos:other_user", 18, "common/default-rules.md");
			AtomicInteger invocationRequests = new AtomicInteger();
			AtomicInteger refreshRequests = new AtomicInteger();
			AtomicReference<String> firstBody = new AtomicReference<>();
			AtomicReference<String> retriedBody = new AtomicReference<>();
			AtomicReference<String> retriedAccount = new AtomicReference<>();
			AtomicReference<String> retriedAuthorization = new AtomicReference<>();
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/cat/v1/feature-invocations", exchange ->
			{
				String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
				if (invocationRequests.incrementAndGet() == 1)
				{
					firstBody.set(body);
					respond(exchange, 401, "{\"error\":\"invalid_token\"}");
				}
				else
				{
					retriedBody.set(body);
					retriedAccount.set(exchange.getRequestHeaders().getFirst("X-HoldMySpot-Account-ID"));
					retriedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
					exchange.sendResponseHeaders(204, -1);
					exchange.close();
				}
			});
			server.createContext("/user_management/authenticate", exchange ->
			{
				refreshRequests.incrementAndGet();
				exchange.getRequestBody().readAllBytes();
				respond(exchange, 200, "{\"user\":{\"id\":\"user_test\"}," +
					"\"access_token\":\"access-second\",\"refresh_token\":\"refresh-second\"}");
			});
			try
			{
				server.start();
				URI origin = URI.create("http://localhost:" + server.getAddress().getPort());
				WorkOsSessionStore sessions = new WorkOsSessionStore(root.resolve("auth"));
				sessions.save(new WorkOsSessionStore.Session(origin.resolve("/cat/"), "client_test",
					new WorkOsDeviceAuthClient.Tokens("user_test", "access-first", "refresh-first")));
				WorkOsFeatureInvocationDelivery delivery = new WorkOsFeatureInvocationDelivery(HttpClient.newHttpClient(),
					origin, sessions, new FeatureInvocationOutbox(outboxDirectory));
				delivery.flush();
				delivery.flush();
				requireThat(invocationRequests.get(), "invocationRequests").isEqualTo(2);
				requireThat(refreshRequests.get(), "refreshRequests").isEqualTo(1);
				requireThat(retriedBody.get(), "retriedBody").isEqualTo(firstBody.get()).
					contains("\"invocation_id\":\"" + invocationId + "\"");
				requireThat(retriedAccount.get(), "retriedAccount").isEqualTo("17");
				requireThat(retriedAuthorization.get(), "retriedAuthorization").isEqualTo("Bearer access-second");
				requireThat(Files.exists(outboxDirectory.resolve(invocationId + ".json")), "confirmedEvent").isFalse();
				requireThat(Files.exists(outboxDirectory.resolve(otherUserId + ".json")), "otherUserEvent").isTrue();
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
	 * Writes a response from the local host or identity provider fixture.
	 *
	 * @param exchange local HTTP exchange
	 * @param status HTTP status
	 * @param body response body
	 * @throws IOException if the response cannot be written
	 */
	private static void respond(HttpExchange exchange, int status, String body) throws IOException
	{
		byte[] bytes = body.getBytes(UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream output = exchange.getResponseBody())
		{
			output.write(bytes);
		}
	}
}
