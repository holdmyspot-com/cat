package io.github.cowwoc.cat.common.usage;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpServer;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;

/**
 * Verifies durable feature-invocation delivery across plugin processes.
 */
public final class FeatureInvocationOutboxTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public FeatureInvocationOutboxTest()
	{
	}

	/**
	 * Retries one exact event after the host recovers and deletes it only after HTTP 204.
	 *
	 * @throws IOException if the test-owned server or outbox fails
	 * @throws InterruptedException if the HTTP request is interrupted
	 */
	@Test
	public void confirmsRetryAcrossHttpBoundary() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-feature-http-outbox-"));
			Path directory = root.resolve("outbox");
			FeatureInvocationOutbox outbox = new FeatureInvocationOutbox(directory);
			UUID invocationId = outbox.enqueue("workos:user-a", 17, "include/common/write-clearly.md");
			AtomicInteger requests = new AtomicInteger();
			AtomicReference<String> firstBody = new AtomicReference<>();
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/cat/v1/feature-invocations", exchange ->
			{
				requireThat(exchange.getRequestHeaders().getFirst("X-HoldMySpot-Account-ID"), "accountHeader").
					isEqualTo("17");
				String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
				if (requests.incrementAndGet() == 1)
				{
					firstBody.set(body);
					exchange.sendResponseHeaders(503, -1);
				}
				else
				{
					requireThat(body, "retryBody").isEqualTo(firstBody.get());
					exchange.sendResponseHeaders(204, -1);
				}
				exchange.close();
			});
			try
			{
				server.start();
				URI base = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
				FeatureInvocationClient sender = new FeatureInvocationClient(HttpClient.newHttpClient(), base);
				try
				{
					outbox.flush("workos:user-a", "access", sender);
					throw new AssertionError("HTTP 503 cannot confirm a feature invocation");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "failure").isEqualTo(
						"Feature invocation server returned HTTP 503");
				}
				requireThat(Files.exists(directory.resolve(invocationId + ".json")), "pendingRecord").isTrue();
				requireThat(new FeatureInvocationOutbox(directory).flush("workos:user-a", "access", sender),
					"confirmed").isEqualTo(1);
				requireThat(Files.exists(directory.resolve(invocationId + ".json")), "confirmedRecord").isFalse();
				requireThat(requests.get(), "requests").isEqualTo(2);
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * A rejected feature stays pending while another feature reaches the server and clears.
	 *
	 * @throws IOException if test-owned storage or HTTP setup fails
	 * @throws InterruptedException if the HTTP request is interrupted
	 */
	@Test
	public void unregisteredDoesNotBlockDelivery() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-feature-partial-flush-"));
			Path directory = root.resolve("outbox");
			FeatureInvocationOutbox outbox = new FeatureInvocationOutbox(directory);
			UUID unregisteredId = outbox.enqueue("workos:user-a", 17, "include/new-rule.md");
			UUID registeredId = outbox.enqueue("workos:user-a", 17, "common/default-rules.md");
			AtomicBoolean catalogUpdated = new AtomicBoolean();
			AtomicInteger registeredRequests = new AtomicInteger();
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/cat/v1/feature-invocations", exchange ->
			{
				String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
				if (body.contains("\"feature_id\":\"include/new-rule.md\"") && !catalogUpdated.get())
					exchange.sendResponseHeaders(422, -1);
				else
				{
					if (body.contains("\"feature_id\":\"common/default-rules.md\""))
						registeredRequests.incrementAndGet();
					exchange.sendResponseHeaders(204, -1);
				}
				exchange.close();
			});
			try
			{
				server.start();
				URI base = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
				FeatureInvocationClient sender = new FeatureInvocationClient(HttpClient.newHttpClient(), base);
				try
				{
					outbox.flush("workos:user-a", "access", sender);
					throw new AssertionError("An unregistered feature must remain pending");
				}
				catch (FeatureInvocationOutbox.UnregisteredFeatureException expected)
				{
					requireThat(expected.getMessage(), "failure").isEqualTo(
						"Feature invocation server returned HTTP 422");
				}
				requireThat(registeredRequests.get(), "registeredRequests").isEqualTo(1);
				requireThat(Files.exists(directory.resolve(registeredId + ".json")), "registeredPending").isFalse();
				requireThat(Files.exists(directory.resolve(unregisteredId + ".json")), "unregisteredPending").isTrue();
				catalogUpdated.set(true);
				requireThat(outbox.flush("workos:user-a", "access", sender), "confirmedAfterCatalogUpdate").
					isEqualTo(1);
				requireThat(Files.exists(directory.resolve(unregisteredId + ".json")), "remainingPending").isFalse();
				requireThat(registeredRequests.get(), "registeredRetries").isEqualTo(1);
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * A failed send keeps the exact invocation and original account for a restarted sender.
	 *
	 * @throws IOException if the test-owned outbox fails
	 * @throws InterruptedException if delivery is interrupted
	 */
	@Test
	public void retriesForOriginalAccount() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-feature-outbox-"));
			Path directory = root.resolve("outbox");
			FeatureInvocationOutbox first = new FeatureInvocationOutbox(directory);
			UUID invocationId = first.enqueue("workos:user-a", 17, "include/common/write-clearly.md");
			UUID otherUser = first.enqueue("workos:user-b", 18, "common/default-rules.md");
			AtomicInteger attempts = new AtomicInteger();
			FeatureInvocationOutbox restarted = new FeatureInvocationOutbox(directory);
			FeatureInvocationOutbox.Sender sender = (token, accountId, featureId, retainedId) ->
			{
				requireThat(token, "token").isEqualTo("access");
				requireThat(accountId, "accountId").isEqualTo(17);
				requireThat(featureId, "featureId").isEqualTo("include/common/write-clearly.md");
				requireThat(retainedId, "invocationId").isEqualTo(invocationId);
				if (attempts.incrementAndGet() == 1)
					throw new IOException("server unavailable");
			};
			try
			{
				restarted.flush("workos:user-a", "access", sender);
				throw new AssertionError("An unconfirmed invocation must remain queued");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "failure").isEqualTo("server unavailable");
			}
			requireThat(Files.exists(directory.resolve(invocationId + ".json")), "pendingRecord").isTrue();
			requireThat(new FeatureInvocationOutbox(directory).flush("workos:user-a", "access", sender),
				"confirmed").isEqualTo(1);
			requireThat(Files.exists(directory.resolve(invocationId + ".json")), "confirmedRecord").isFalse();
			requireThat(Files.exists(directory.resolve(otherUser + ".json")), "otherUserRecord").isTrue();
			requireThat(attempts.get(), "attempts").isEqualTo(2);
		}
	}
}
