package io.github.cowwoc.cat.codex;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.auth.WorkOsDeviceAuthClient;
import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;
import io.github.cowwoc.cat.common.usage.UsageReportOutbox;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the native Stop event's report and delivery boundary.
 */
public final class CodexStopHookTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public CodexStopHookTest()
	{
	}

	/**
	 * A completed turn is sent once, and a later unreadable turn still retries a pending report.
	 *
	 * @throws IOException if the local rollout, saved login, or HTTP fixture fails
	 */
	@Test
	public void sendsMeasuredStopEvent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-stop-hook-"));
			Path home = Files.createDirectory(root.resolve("home"));
			Path plugin = Files.createDirectory(root.resolve("plugin"));
			Path data = Files.createDirectory(root.resolve("data"));
			UUID sessionId = UUID.fromString("01a0d58c-2542-7983-9386-1f5a2fadd3d7");
			UUID turnId = UUID.fromString("00000000-0000-0000-0000-000000000456");
			Path transcript = root.resolve("rollout-2026-09-25T00-00-00-" + sessionId + ".jsonl");
			Files.writeString(transcript, """
				{"type":"session_meta","payload":{"session_id":"%s","cli_version":"0.157.0"}}
				{"type":"token_usage_record","payload":{"session_id":"%s","root_turn_id":"%s",
				"turn_id":"%s","response_id":"response-first","usage":{"output_tokens":42},
				"turn_token_usage":{"output_tokens":42}}}
				""".formatted(sessionId, sessionId, turnId, turnId));

			AtomicReference<String> requestBody = new AtomicReference<>();
			AtomicReference<String> selectedAccount = new AtomicReference<>();
			AtomicInteger requestCount = new AtomicInteger();
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			try
			{
				JsonMapper json = JsonMapper.builder().build();
				server.createContext("/cat/v1/verified-usage-reports", exchange ->
				{
					requestCount.incrementAndGet();
					String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
					requestBody.set(body);
					selectedAccount.set(exchange.getRequestHeaders().getFirst("X-HoldMySpot-Account-ID"));
					String eventId = json.readTree(body).path("event_id").stringValue();
					respond(exchange, "{\"event_id\":\"" + eventId +
						"\",\"accounted_output_tokens\":42}");
				});
				server.start();
				URI serverBase = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
				WorkOsSessionStore sessions = new WorkOsSessionStore(data.resolve("auth"));
				sessions.save(new WorkOsSessionStore.Session(serverBase, "client_test",
					new WorkOsDeviceAuthClient.Tokens("user_a", "access", "refresh"), OptionalLong.of(17)));
				String event = """
					{"hook_event_name":"Stop","session_id":"%s","turn_id":"%s",
					"transcript_path":"%s","stop_hook_active":false}
					""".formatted(sessionId, turnId, transcript);
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				ByteArrayOutputStream error = new ByteArrayOutputStream();
				try (PrintStream diagnostic = new PrintStream(error, true, StandardCharsets.UTF_8))
				{
					CodexStopHook.run(new String[0], new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)),
						output, diagnostic, Map.of("HOME", home.toString(), "PLUGIN_ROOT", plugin.toString(),
							"PLUGIN_DATA", data.toString()), HttpClient.newHttpClient(),
						URI.create("http://localhost:" + server.getAddress().getPort()));
				}
				requireThat(json.readTree(output.toByteArray()).size(), "hookResponseFields").isEqualTo(0);
				requireThat(selectedAccount.get(), "selectedAccount").isEqualTo("17");
				requireThat(requestBody.get(), "requestBody").contains("\"turn_output_tokens_so_far\":42").
					contains("\"harness_version\":\"0.157.0\"");
				try (Stream<Path> reports = Files.list(data.resolve("outbox")))
				{
					requireThat(reports.filter(path -> path.getFileName().toString().endsWith(".json")).count(),
						"pendingReports").isEqualTo(0L);
				}

				UUID pendingTurn = UUID.fromString("00000000-0000-0000-0000-000000000789");
				CodexUsageReportEncoder encoder = new CodexUsageReportEncoder();
				new UsageReportOutbox(data.resolve("outbox")).enqueue("workos:user_a", 17,
					"cat/codex/0.157.0/" + pendingTurn + "/42", 42,
					eventId -> encoder.encode(new CodexUsageReportEncoder.Report(pendingTurn, eventId, 42,
						CodexMeteringVersion.V_0_157_0)));
				Files.writeString(transcript, """
					{"type":"session_meta","payload":{"session_id":"%s","cli_version":"99.0.0"}}
					""".formatted(sessionId));
				ByteArrayOutputStream retryOutput = new ByteArrayOutputStream();
				try (PrintStream diagnostic = new PrintStream(new ByteArrayOutputStream(), true,
					StandardCharsets.UTF_8))
				{
					CodexStopHook.run(new String[0], new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)),
						retryOutput, diagnostic, Map.of("HOME", home.toString(), "PLUGIN_ROOT", plugin.toString(),
							"PLUGIN_DATA", data.toString()), HttpClient.newHttpClient(),
						URI.create("http://localhost:" + server.getAddress().getPort()));
				}
				requireThat(requestCount.get(), "requestsAfterUnsupportedTurn").isEqualTo(2);
				requireThat(json.readTree(retryOutput.toByteArray()).path("systemMessage").isString(),
					"unsupportedTurnWarning").isTrue();
				try (Stream<Path> reports = Files.list(data.resolve("outbox")))
				{
					requireThat(reports.filter(path -> path.getFileName().toString().endsWith(".json")).count(),
						"pendingAfterRetry").isEqualTo(0L);
				}
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * Returns one acknowledgement from the local host.
	 *
	 * @param exchange local HTTP request
	 * @param body response body
	 * @throws IOException if the response cannot be written
	 */
	private static void respond(HttpExchange exchange, String body) throws IOException
	{
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream response = exchange.getResponseBody())
		{
			response.write(bytes);
		}
	}
}
