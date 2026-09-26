package io.github.cowwoc.cat.codex;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the installed command's authorization, enrollment, and saved-account handoff.
 */
public final class CodexLoginTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public CodexLoginTest()
	{
	}

	/**
	 * One approved device login stores its tokens and sole linked account for the usage hook.
	 *
	 * @throws IOException if a local HTTP exchange or saved session fails
	 * @throws InterruptedException if the device poll is interrupted
	 */
	@Test
	public void savesApprovedLoginAndAccount() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path userHome = cleanup.add(Files.createTempDirectory("cat-codex-login-"));
			Path home = Files.createDirectory(userHome.resolve(".codex"));
			AtomicInteger polls = new AtomicInteger();
			AtomicReference<String> usageBody = new AtomicReference<>();
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/cat/v1/workos-client-configuration", exchange ->
				respond(exchange, 200, "{\"client_id\":\"client_test\"}"));
			server.createContext("/user_management/authorize/device", exchange ->
			{
				requireThat(exchange.getRequestBody().readAllBytes().length > 0, "authorizationBody").isTrue();
				respond(exchange, 200, """
					{"device_code":"device-secret","user_code":"ABCD-1234",
					"verification_uri":"https://auth.workos.com/activate","expires_in":600,"interval":1}
					""");
			});
			server.createContext("/user_management/authenticate", exchange ->
			{
				if (polls.incrementAndGet() == 1)
					respond(exchange, 400, "{\"error\":\"authorization_pending\"}");
				else
					respond(exchange, 200, """
						{"user":{"id":"user_a"},"access_token":"access-secret","refresh_token":"refresh-secret"}
						""");
			});
			server.createContext("/cat/v1/accounts", exchange ->
			{
				requireThat(exchange.getRequestHeaders().getFirst("Authorization"), "authorization").
					isEqualTo("Bearer access-secret");
				if ("POST".equals(exchange.getRequestMethod()))
					respond(exchange, 201, "{\"id\":17}");
				else
					respond(exchange, 200,
						"{\"accounts\":[{\"id\":17,\"email\":\"user@example.com\",\"verified\":true}]}");
			});
			server.createContext("/cat/v1/verified-usage-reports", exchange ->
			{
				String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				usageBody.set(body);
				String eventId = JsonMapper.builder().build().readTree(body).path("event_id").stringValue();
				respond(exchange, 200, "{\"event_id\":\"" + eventId +
					"\",\"accounted_output_tokens\":42}");
			});
			try
			{
				server.start();
				URI origin = URI.create("http://localhost:" + server.getAddress().getPort());
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				try (HttpClient http = HttpClient.newHttpClient();
					PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8))
				{
					CodexLogin.login(Map.of("HOME", userHome.toString()), new String[0], http,
						origin.resolve("/cat/"), origin.resolve("/sandbox/"), origin, output);
				}
				String printed = bytes.toString(StandardCharsets.UTF_8);
				requireThat(printed, "printed").contains("https://auth.workos.com/activate").
					contains("ABCD-1234").contains("selected HoldMySpot account 17");
				requireThat(printed.contains("access-secret") || printed.contains("refresh-secret") ||
					printed.contains("device-secret"), "printedSecret").isFalse();
				WorkOsSessionStore.Session saved = new WorkOsSessionStore(home.resolve("plugins/data/cat-cat/auth")).
					load().orElseThrow();
				requireThat(saved.tokens().userId(), "savedUser").isEqualTo("user_a");
				requireThat(saved.selectedAccountId().orElseThrow(), "selectedAccount").isEqualTo(17L);
				requireThat(polls.get(), "devicePolls").isEqualTo(2);

				UUID sessionId = UUID.fromString("01a0d58c-2542-7983-9386-1f5a2fadd3d7");
				UUID turnId = UUID.fromString("00000000-0000-0000-0000-000000000456");
				Path rollout = home.resolve("rollout-2026-09-25T00-00-00-" + sessionId + ".jsonl");
				Files.writeString(rollout, """
					{"type":"session_meta","payload":{"session_id":"%s","cli_version":"0.157.0"}}
					{"type":"token_usage_record","payload":{"session_id":"%s","root_turn_id":"%s",
					"turn_id":"%s","response_id":"response-first","usage":{"output_tokens":42},
					"turn_token_usage":{"output_tokens":42}}}
					""".formatted(sessionId, sessionId, turnId, turnId));
				String stopEvent = """
					{"hook_event_name":"Stop","session_id":"%s","turn_id":"%s",
					"transcript_path":"%s","stop_hook_active":false}
					""".formatted(sessionId, turnId, rollout);
				Path plugin = Files.createDirectory(home.resolve("plugin"));
				ByteArrayOutputStream hookResponse = new ByteArrayOutputStream();
				try (PrintStream diagnostic = new PrintStream(new ByteArrayOutputStream(), true,
					StandardCharsets.UTF_8))
				{
					CodexStopHook.run(new String[0], new ByteArrayInputStream(
						stopEvent.getBytes(StandardCharsets.UTF_8)), hookResponse, diagnostic,
						Map.of("HOME", home.toString(), "PLUGIN_ROOT", plugin.toString(),
							"PLUGIN_DATA", home.resolve("plugins/data/cat-cat").toString(),
							"CODEX_HOME", home.toString()), HttpClient.newHttpClient(), origin);
				}
				requireThat(JsonMapper.builder().build().readTree(hookResponse.toByteArray()).size(),
					"stopResponseFields").isEqualTo(0);
				requireThat(usageBody.get(), "usageBody").contains("\"turn_output_tokens_so_far\":42");
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * A sandbox login sends account requests to the sandbox API and saves that API base with the session.
	 *
	 * @throws IOException if a local HTTP exchange or saved session fails
	 * @throws InterruptedException if the device poll is interrupted
	 */
	@Test
	public void sandboxLoginUsesSandboxApi() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path userHome = cleanup.add(Files.createTempDirectory("cat-sandbox-login-"));
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/sandbox/v1/workos-client-configuration", exchange ->
				respond(exchange, 200, "{\"client_id\":\"client_sandbox\"}"));
			server.createContext("/user_management/authorize/device", exchange ->
				respond(exchange, 200, """
					{"device_code":"sandbox-device","user_code":"SBOX-1234",
					"verification_uri":"https://auth.workos.com/activate","expires_in":600,"interval":1}
					"""));
			server.createContext("/user_management/authenticate", exchange ->
				respond(exchange, 200, """
					{"user":{"id":"user_sandbox"},"access_token":"sandbox-access",
					"refresh_token":"sandbox-refresh"}
					"""));
			server.createContext("/sandbox/v1/accounts", exchange ->
			{
				if ("POST".equals(exchange.getRequestMethod()))
					respond(exchange, 201, "{\"id\":23}");
				else
					respond(exchange, 200,
						"{\"accounts\":[{\"id\":23,\"email\":\"sandbox@example.com\",\"verified\":true}]}");
			});
			try
			{
				server.start();
				URI origin = URI.create("http://localhost:" + server.getAddress().getPort());
				URI sandboxBase = origin.resolve("/sandbox/");
				try (HttpClient http = HttpClient.newHttpClient();
					PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
				{
					CodexLogin.login(Map.of("HOME", userHome.toString()), new String[]{"--sandbox"}, http,
						origin.resolve("/production/"), sandboxBase, origin, output);
				}
				WorkOsSessionStore.Session saved = new WorkOsSessionStore(userHome.resolve(
					".codex/plugins/data/cat-cat/auth")).load().orElseThrow();
				requireThat(saved.serverBase(), "serverBase").isEqualTo(sandboxBase);
				requireThat(saved.clientId(), "clientId").isEqualTo("client_sandbox");
				requireThat(saved.selectedAccountId().orElseThrow(), "selectedAccount").isEqualTo(23L);
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * An unknown login option is rejected with the supported command usage.
	 *
	 * @throws IOException if an unexpected request or storage operation fails
	 * @throws InterruptedException if an unexpected device poll is interrupted
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Usage: login \\[--sandbox\\]")
	public void unknownLoginOptionIsRejected() throws IOException, InterruptedException
	{
		try (HttpClient http = HttpClient.newHttpClient();
			PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
		{
			CodexLogin.login(Map.of(), new String[]{"--other"}, http,
				URI.create("https://api.holdmyspot.com/cat/"),
				URI.create("https://api-sandbox.holdmyspot.com/cat/"),
				URI.create("https://api.workos.com"), output);
		}
	}

	/**
	 * Writes one local host or WorkOS response.
	 *
	 * @param exchange local HTTP request
	 * @param status HTTP response status
	 * @param body JSON response body
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
