package io.github.cowwoc.cat.codex;

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
import java.util.Map;
import java.util.OptionalLong;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks that the Codex command selects an account through the saved login.
 */
public final class CodexAccountSelectTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public CodexAccountSelectTest()
	{
	}

	/**
	 * The command finds the saved login under Codex home and stores an account listed by the host.
	 *
	 * @throws IOException if the local host or session store fails
	 * @throws InterruptedException if the local HTTP exchange is interrupted
	 */
	@Test
	public void selectsLinkedAccountFromCodexHome() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path userHome = cleanup.add(Files.createTempDirectory("cat-codex-account-"));
			Path codexHome = Files.createDirectory(userHome.resolve(".codex"));
			Path dataDirectory = Files.createDirectories(codexHome.resolve("plugins/data/cat-cat"));
			WorkOsSessionStore sessions = new WorkOsSessionStore(dataDirectory.resolve("auth"));
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			try
			{
				server.createContext("/cat/v1/accounts", exchange ->
				{
					requireThat(exchange.getRequestHeaders().getFirst("Authorization"), "authorization").
						isEqualTo("Bearer access-first");
					respond(exchange);
				});
				server.start();
				URI serverBase = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
				sessions.save(new WorkOsSessionStore.Session(serverBase, "client_test",
					new WorkOsDeviceAuthClient.Tokens("user_test", "access-first", "refresh-first")));

				String confirmation = CodexAccountSelect.select(Map.of("HOME", userHome.toString()),
					new String[]{"17"}, HttpClient.newHttpClient());

				requireThat(confirmation, "confirmation").isEqualTo("Selected HoldMySpot account 17");
				requireThat(sessions.load().orElseThrow().selectedAccountId(), "selectedAccount").
					isEqualTo(OptionalLong.of(17));
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * Rejects a zero account ID before reading saved login state.
	 *
	 * @throws IOException if an unexpected store failure occurs
	 * @throws InterruptedException if an unexpected HTTP call is interrupted
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "HoldMySpot account ID must be a positive integer")
	public void rejectsZeroAccountId() throws IOException, InterruptedException
	{
		CodexAccountSelect.select(Map.of(), new String[]{"0"}, HttpClient.newHttpClient());
	}

	/**
	 * Sends one linked account from the local host fixture to the account selector.
	 *
	 * @param exchange local HTTP exchange
	 * @throws IOException if the response cannot be written
	 */
	private static void respond(HttpExchange exchange) throws IOException
	{
		byte[] body = """
			{"accounts":[{"id":17,"email":"one@example.com","verified":true}]}
			""".getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream output = exchange.getResponseBody())
		{
			output.write(body);
		}
	}
}
