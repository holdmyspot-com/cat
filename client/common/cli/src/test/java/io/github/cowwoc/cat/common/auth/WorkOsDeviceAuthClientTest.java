package io.github.cowwoc.cat.common.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks WorkOS public-client authorization and token rotation through a local HTTP fixture.
 */
public final class WorkOsDeviceAuthClientTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public WorkOsDeviceAuthClientTest()
	{
	}

	/**
	 * Distinguishes pending, slower polling, completion, and rotation without sending a client secret.
	 *
	 * @throws IOException if the local provider fixture cannot run
	 * @throws InterruptedException if the client exchange is interrupted
	 */
	@Test
	public void exchangesDeviceCodeAndRefreshesTokens() throws IOException, InterruptedException
	{
		AtomicReference<String> authorizationForm = new AtomicReference<>();
		AtomicReference<String> lastTokenForm = new AtomicReference<>();
		AtomicInteger polls = new AtomicInteger();
		HttpServer provider = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		try
		{
			provider.createContext("/user_management/authorize/device", exchange ->
			{
				authorizationForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				respond(exchange, 200, """
					{"device_code":"secret-device","user_code":"ABCD-EFGH",
					"verification_uri":"https://login.example.com/device","expires_in":300,"interval":5}
					""");
			});
			provider.createContext("/user_management/authenticate", exchange ->
			{
				String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				lastTokenForm.set(form);
				if (form.contains("grant_type=refresh_token"))
				{
					String userId = "user_alice";
					if (form.contains("refresh_token=refresh-2"))
						userId = "user_eve";
					respond(exchange, 200,
						"{\"user\":{\"id\":\"" + userId + "\"},\"access_token\":\"access-2\"," +
							"\"refresh_token\":\"refresh-2\"}");
				}
				else
				{
					switch (polls.incrementAndGet())
					{
						case 1 -> respond(exchange, 400, "{\"error\":\"authorization_pending\"}");
						case 2 -> respond(exchange, 400, "{\"error\":\"slow_down\"}");
						case 3 -> respond(exchange, 200,
							"{\"user\":{\"id\":\"user_alice\"},\"access_token\":\"access-1\"," +
								"\"refresh_token\":\"refresh-1\"}");
						default -> respond(exchange, 500, "{\"error\":\"unexpected_poll\"}");
					}
				}
			});
			provider.start();

			URI providerBase = URI.create("http://localhost:" + provider.getAddress().getPort());
			WorkOsDeviceAuthClient client = new WorkOsDeviceAuthClient(HttpClient.newHttpClient(),
				providerBase, "client_test123");
			WorkOsDeviceAuthClient.DeviceAuthorization authorization = client.authorize();
			requireThat(authorization.userCode(), "userCode").isEqualTo("ABCD-EFGH");
			requireThat(authorization.verificationUri(), "verificationUri").
				isEqualTo(URI.create("https://login.example.com/device"));
			requireThat(authorization.expiresInSeconds(), "expiresInSeconds").isEqualTo(300L);
			requireThat(authorization.intervalSeconds(), "intervalSeconds").isEqualTo(5L);
			requireThat(authorizationForm.get(), "authorizationForm").isEqualTo("client_id=client_test123");
			requireThat(authorization.toString(), "authorizationDiagnostic").doesNotContain("secret-device");

			requireThat(client.poll(authorization.deviceCode()), "firstPoll").
				isEqualTo(new WorkOsDeviceAuthClient.PollResult.Pending());
			requireThat(client.poll(authorization.deviceCode()), "secondPoll").
				isEqualTo(new WorkOsDeviceAuthClient.PollResult.SlowDown());
			WorkOsDeviceAuthClient.PollResult.Complete completed =
				(WorkOsDeviceAuthClient.PollResult.Complete) client.poll(authorization.deviceCode());
			requireThat(completed.tokens(), "thirdPollTokens").
				isEqualTo(new WorkOsDeviceAuthClient.Tokens("user_alice", "access-1", "refresh-1"));
			requireThat(completed.tokens().userId(), "thirdPollUser").isEqualTo("user_alice");
			requireThat(lastTokenForm.get(), "deviceCodeForm").contains("device_code=secret-device").
				contains("client_id=client_test123").doesNotContain("client_secret");

			WorkOsDeviceAuthClient.Tokens refreshed = client.refresh(completed.tokens());
			requireThat(refreshed, "refreshedTokens").
				isEqualTo(new WorkOsDeviceAuthClient.Tokens("user_alice", "access-2", "refresh-2"));
			requireThat(refreshed.userId(), "refreshedUser").isEqualTo("user_alice");
			requireThat(new WorkOsDeviceAuthClient.Tokens("user_alice", "access-2", "refresh-2").toString(),
				"tokenDiagnostic").doesNotContain("refresh-2").doesNotContain("access-2");
			requireThat(lastTokenForm.get(), "refreshForm").contains("refresh_token=refresh-1").
				contains("client_id=client_test123").doesNotContain("client_secret");
		}
		finally
		{
			provider.stop(0);
		}
	}

	/**
	 * A refresh for another WorkOS user cannot replace this user's tokens.
	 *
	 * @throws IOException for the changed user or local fixture failure
	 * @throws InterruptedException if the exchange is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "WorkOS refreshed a different user; sign in again")
	public void rejectsChangedRefreshUser() throws IOException, InterruptedException
	{
		HttpServer provider = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		try
		{
			provider.createContext("/user_management/authenticate", exchange -> respond(exchange, 200,
				"{\"user\":{\"id\":\"user_eve\"},\"access_token\":\"access-2\"," +
					"\"refresh_token\":\"refresh-2\"}"));
			provider.start();
			WorkOsDeviceAuthClient client = new WorkOsDeviceAuthClient(HttpClient.newHttpClient(),
				URI.create("http://localhost:" + provider.getAddress().getPort()), "client_test123");
			client.refresh(new WorkOsDeviceAuthClient.Tokens("user_alice", "access-1", "refresh-1"));
		}
		finally
		{
			provider.stop(0);
		}
	}

	/**
	 * Denied device authorization is reported as a specific failure.
	 *
	 * @throws IOException for the denial or local fixture failure
	 * @throws InterruptedException if the exchange is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "WorkOS device authorization was denied")
	public void rejectsDeniedAuthorization() throws IOException, InterruptedException
	{
		HttpServer provider = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		try
		{
			provider.createContext("/user_management/authenticate", exchange -> respond(exchange, 400,
				"{\"error\":\"access_denied\"}"));
			provider.start();
			WorkOsDeviceAuthClient client = new WorkOsDeviceAuthClient(HttpClient.newHttpClient(),
				URI.create("http://localhost:" + provider.getAddress().getPort()), "client_test123");
			client.poll("secret-device");
		}
		finally
		{
			provider.stop(0);
		}
	}

	/**
	 * An unsupported provider error does not reveal the secret device code.
	 *
	 * @throws IOException for the unsupported result or local fixture failure
	 * @throws InterruptedException if the exchange is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "WorkOS device poll returned an unsupported error")
	public void rejectsUnsupportedPollError() throws IOException, InterruptedException
	{
		HttpServer provider = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		try
		{
			provider.createContext("/user_management/authenticate", exchange -> respond(exchange, 400,
				"{\"error\":\"device_code=secret-device\"}"));
			provider.start();
			WorkOsDeviceAuthClient client = new WorkOsDeviceAuthClient(HttpClient.newHttpClient(),
				URI.create("http://localhost:" + provider.getAddress().getPort()), "client_test123");
			client.poll("secret-device");
		}
		finally
		{
			provider.stop(0);
		}
	}

	/**
	 * Writes one local WorkOS-shaped response to the request being tested.
	 *
	 * @param exchange local HTTP exchange
	 * @param status HTTP status
	 * @param body JSON response body
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
