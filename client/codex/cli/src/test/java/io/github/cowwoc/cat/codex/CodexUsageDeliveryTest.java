package io.github.cowwoc.cat.codex;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.auth.WorkOsDeviceAuthClient;
import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;
import io.github.cowwoc.cat.common.usage.UsageReportClient;
import io.github.cowwoc.cat.common.usage.UsageReportOutbox;
import io.github.cowwoc.cat.common.usage.WorkOsUsageDelivery;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks Codex report encoding and delivery through the shared usage outbox.
 */
public final class CodexUsageDeliveryTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public CodexUsageDeliveryTest()
	{
	}

	/**
	 * A measured Codex response reaches the host with the saved login's selected account after an outbox restart.
	 *
	 * @throws IOException if the rollout, saved state, or local host fails
	 * @throws InterruptedException if the local HTTP exchange is interrupted
	 */
	@Test
	public void deliversMeasuredRolloutWithSavedAccount() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-measured-"));
			UUID turnId = UUID.fromString("00000000-0000-0000-0000-000000000456");
			String rollout = """
				{"type":"session_meta","payload":{"session_id":"session-test","cli_version":"0.157.0"}}
				{"type":"token_usage_record","payload":{"session_id":"session-test","root_turn_id":"%s",
				"turn_id":"%s","response_id":"response-first","usage":{"output_tokens":42},
				"turn_token_usage":{"output_tokens":42}}}
				""".formatted(turnId, turnId);
			CodexRolloutUsageReader.TurnUsage measured = CodexRolloutUsageReader.readTurn(
				new ByteArrayInputStream(rollout.getBytes(StandardCharsets.UTF_8)), turnId).orElseThrow();

			AtomicReference<String> requestBody = new AtomicReference<>();
			AtomicReference<String> selectedAccount = new AtomicReference<>();
			AtomicReference<UUID> expectedEventId = new AtomicReference<>();
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			try
			{
				server.createContext("/cat/v1/verified-usage-reports", exchange ->
				{
					requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
					selectedAccount.set(exchange.getRequestHeaders().getFirst("X-HoldMySpot-Account-ID"));
					respond(exchange, 200, "{\"event_id\":\"" + expectedEventId.get() +
						"\",\"accounted_output_tokens\":42}");
				});
				server.start();

				URI serverBase = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
				WorkOsSessionStore sessions = new WorkOsSessionStore(root.resolve("auth"));
				WorkOsSessionStore.Session login = new WorkOsSessionStore.Session(serverBase, "client_test",
					new WorkOsDeviceAuthClient.Tokens("user_a", "access", "refresh"), OptionalLong.of(17));
				sessions.save(login);
				UsageReportOutbox firstProcess = new UsageReportOutbox(root.resolve("outbox"));
				CodexUsageReportEncoder encoder = new CodexUsageReportEncoder();
				String measurementKey = "cat/codex/" + measured.harnessVersion().runtimeValue() + "/" +
					turnId + "/" + measured.outputTokens();
				UsageReportOutbox.PendingReport report = firstProcess.enqueue("workos:" + login.tokens().userId(),
					login.selectedAccountId().orElseThrow(), measurementKey, measured.outputTokens(),
					eventId -> encoder.encode(new CodexUsageReportEncoder.Report(turnId, eventId,
							measured.outputTokens(), measured.harnessVersion())));
				expectedEventId.set(report.eventId());

				UsageReportOutbox restarted = new UsageReportOutbox(root.resolve("outbox"));
				URI workOsApiBase = URI.create("http://localhost:" + server.getAddress().getPort());
				WorkOsUsageDelivery delivery = new WorkOsUsageDelivery(HttpClient.newHttpClient(), workOsApiBase,
					sessions, restarted);
				delivery.flush();

				requireThat(selectedAccount.get(), "selectedAccount").isEqualTo("17");
				requireThat(requestBody.get(), "requestBody").contains(report.eventId().toString()).
					contains("\"turn_output_tokens_so_far\":42").contains("\"harness_version\":\"0.157.0\"");
				requireThat(restarted.flush("workos:user_a", "access",
					new UsageReportClient(HttpClient.newHttpClient(), serverBase)), "pendingAfterReceipt").isEqualTo(0);
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * Restarting the outbox retains the event and sends it once with its selected account.
	 *
	 * @throws IOException if the local outbox or HTTP fixture fails
	 * @throws InterruptedException if the HTTP exchange is interrupted
	 */
	@Test
	public void retainsAndDeliversAfterRestart() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-usage-"));
			Path data = root.resolve("outbox");
			UUID turnId = UUID.fromString("00000000-0000-0000-0000-000000000123");
			CodexUsageReportEncoder encoder = new CodexUsageReportEncoder();
			UsageReportOutbox firstProcess = new UsageReportOutbox(data);
			UsageReportOutbox.PendingReport report = firstProcess.enqueue("workos:user_a", 17,
				"cat/codex/0.155.0/" + turnId + "/42", 42,
				eventId -> encoder.encode(new CodexUsageReportEncoder.Report(turnId, eventId, 42,
					CodexMeteringVersion.V_0_155_0)));
			UsageReportOutbox restarted = new UsageReportOutbox(data);
			requireThat(restarted.enqueue("workos:user_a", 17, "cat/codex/0.155.0/" + turnId + "/42", 42,
				eventId -> encoder.encode(new CodexUsageReportEncoder.Report(turnId, eventId, 42,
					CodexMeteringVersion.V_0_155_0))),
				"samePendingReport").isEqualTo(report);

			AtomicInteger requestCount = new AtomicInteger();
			AtomicReference<String> requestBody = new AtomicReference<>();
			AtomicReference<String> selectedAccount = new AtomicReference<>();
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			try
			{
				server.createContext("/v1/verified-usage-reports", exchange ->
				{
					requestCount.incrementAndGet();
					requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
					selectedAccount.set(exchange.getRequestHeaders().getFirst("X-HoldMySpot-Account-ID"));
					respond(exchange, 200, "{\"event_id\":\"" + report.eventId() +
						"\",\"accounted_output_tokens\":42}");
				});
				server.start();
				UsageReportClient client = new UsageReportClient(HttpClient.newHttpClient(),
					URI.create("http://localhost:" + server.getAddress().getPort()));
				WorkOsDeviceAuthClient.Tokens otherUser = new WorkOsDeviceAuthClient.Tokens("user_b", "other", "refresh");
				requireThat(restarted.flush("workos:" + otherUser.userId(), otherUser.accessToken(), client),
					"otherUserSent").isEqualTo(0);
				requireThat(requestCount.get(), "requestsForOtherUser").isEqualTo(0);
				WorkOsDeviceAuthClient.Tokens measuringUser = new WorkOsDeviceAuthClient.Tokens("user_a", "access",
					"refresh");
				requireThat(restarted.flush("workos:" + measuringUser.userId(), measuringUser.accessToken(), client),
					"sent").isEqualTo(1);
				requireThat(restarted.flush("workos:" + measuringUser.userId(), measuringUser.accessToken(), client),
					"resent").isEqualTo(0);
				requireThat(requestCount.get(), "requestCount").isEqualTo(1);
				requireThat(selectedAccount.get(), "selectedAccount").isEqualTo("17");
				requireThat(requestBody.get(), "requestBody").contains(report.eventId().toString()).
					contains("\"turn_output_tokens_so_far\":42");
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * An unconfirmed HTTP result leaves the exact report available after a plugin restart.
	 *
	 * @throws IOException for the first unconfirmed delivery or fixture failure
	 * @throws InterruptedException if the HTTP exchange is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Usage server did not confirm this delivery and count")
	public void retriesUnconfirmedDelivery() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-usage-"));
			Path data = root.resolve("outbox");
			UUID turnId = UUID.fromString("00000000-0000-0000-0000-000000000789");
			CodexUsageReportEncoder encoder = new CodexUsageReportEncoder();
			UsageReportOutbox firstProcess = new UsageReportOutbox(data);
			UsageReportOutbox.PendingReport report = firstProcess.enqueue("workos:user_a", 17,
				"cat/codex/0.155.0/" + turnId + "/42", 42,
				eventId -> encoder.encode(new CodexUsageReportEncoder.Report(turnId, eventId, 42,
					CodexMeteringVersion.V_0_155_0)));
			AtomicInteger requestCount = new AtomicInteger();
			AtomicReference<String> firstBody = new AtomicReference<>();
			AtomicReference<String> retryBody = new AtomicReference<>();
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			try
			{
				server.createContext("/v1/verified-usage-reports", exchange ->
				{
					String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
					if (requestCount.incrementAndGet() == 1)
					{
						firstBody.set(body);
						respond(exchange, 200, "{\"event_id\":\"" + turnId +
							"\",\"accounted_output_tokens\":42}");
					}
					else
					{
						retryBody.set(body);
						respond(exchange, 200, "{\"event_id\":\"" + report.eventId() +
							"\",\"accounted_output_tokens\":42}");
					}
				});
				server.start();
				UsageReportClient client = new UsageReportClient(HttpClient.newHttpClient(),
					URI.create("http://localhost:" + server.getAddress().getPort()));
				WorkOsDeviceAuthClient.Tokens tokens = new WorkOsDeviceAuthClient.Tokens("user_a", "access", "refresh");
				try
				{
					firstProcess.flush("workos:" + tokens.userId(), tokens.accessToken(), client);
				}
				finally
				{
					UsageReportOutbox restarted = new UsageReportOutbox(data);
					requireThat(restarted.enqueue("workos:user_a", 17,
						"cat/codex/0.155.0/" + turnId + "/42", 42,
						eventId -> encoder.encode(new CodexUsageReportEncoder.Report(turnId, eventId, 42,
							CodexMeteringVersion.V_0_155_0))),
						"retainedReport").isEqualTo(report);
					requireThat(restarted.flush("workos:" + tokens.userId(), tokens.accessToken(), client),
						"retryConfirmed").isEqualTo(1);
					requireThat(requestCount.get(), "requestCount").isEqualTo(2);
					requireThat(retryBody.get(), "retryBody").isEqualTo(firstBody.get());
				}
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * Writes one local server response.
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
