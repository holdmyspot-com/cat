package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpServer;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.auth.WorkOsDeviceAuthClient;
import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Checks account-gated delivery of one packaged lazy rule.
 */
public final class CodexRuleLoadTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public CodexRuleLoadTest()
	{
	}

	/**
	 * Supplies a rule online, queues an offline delivery, and confirms both after the server returns.
	 *
	 * @throws IOException if test-owned rule, login, or HTTP state fails
	 * @throws InterruptedException if a local HTTP request is interrupted
	 */
	@Test
	public void deliversAndRetriesLazyRule() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-rule-load-"));
			Path plugin = Files.createDirectory(root.resolve("plugin"));
			Path rule = plugin.resolve("rules/include/common/write-clearly.md");
			Files.createDirectories(rule.getParent());
			Files.writeString(rule, "# Write Clearly\nRule content\n");
			Path codexHome = Files.createDirectory(root.resolve("codex-home"));
			Path data = Files.createDirectories(codexHome.resolve("plugins/data/cat-cat"));
			AtomicBoolean available = new AtomicBoolean(true);
			AtomicInteger sent = new AtomicInteger();
			AtomicReference<String> accountHeader = new AtomicReference<>();
			AtomicReference<String> featureBody = new AtomicReference<>();
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/cat/v1/accounts", exchange ->
			{
				if (!available.get())
					exchange.sendResponseHeaders(503, -1);
				else
				{
					byte[] body = ("{\"accounts\":[{\"id\":17,\"email\":\"user@example.com\"," +
						"\"verified\":true}]}").getBytes(UTF_8);
					exchange.sendResponseHeaders(200, body.length);
					exchange.getResponseBody().write(body);
				}
				exchange.close();
			});
			server.createContext("/cat/v1/feature-invocations", exchange ->
			{
				accountHeader.set(exchange.getRequestHeaders().getFirst("X-HoldMySpot-Account-ID"));
				featureBody.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
				sent.incrementAndGet();
				exchange.sendResponseHeaders(204, -1);
				exchange.close();
			});
			try
			{
				server.start();
				URI serverBase = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
				WorkOsSessionStore sessions = new WorkOsSessionStore(data.resolve("auth"));
				sessions.save(new WorkOsSessionStore.Session(serverBase, "client_test",
					new WorkOsDeviceAuthClient.Tokens("user_test", "access", "refresh"), OptionalLong.of(17)));
				Map<String, String> environment = Map.of("CODEX_HOME", codexHome.toString());
				String[] args = {"include/common/write-clearly.md"};
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				try (PrintStream error = new PrintStream(new ByteArrayOutputStream(), true, UTF_8))
				{
					CodexRuleLoad.load(environment, plugin, args, output, error);
					requireThat(output.toString(UTF_8), "onlineContent").isEqualTo(Files.readString(rule));
					requireThat(sent.get(), "onlineInvocations").isEqualTo(1);
					available.set(false);
					output.reset();
					CodexRuleLoad.load(environment, plugin, args, output, error);
					requireThat(output.toString(UTF_8), "offlineContent").isEqualTo(Files.readString(rule));
					requireThat(sent.get(), "offlineInvocationsNotSent").isEqualTo(1);
					requireThat(pendingCount(data.resolve("feature-outbox")), "offlinePending").isEqualTo(1L);
					available.set(true);
					CodexRuleLoad.load(environment, plugin, args, output, error);
				}
				requireThat(sent.get(), "confirmedInvocations").isEqualTo(3);
				requireThat(accountHeader.get(), "accountHeader").isEqualTo("17");
				requireThat(featureBody.get(), "featureBody").contains(
					"\"feature_id\":\"include/common/write-clearly.md\"");
				requireThat(pendingCount(data.resolve("feature-outbox")), "confirmedPending").isEqualTo(0L);
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * A missing account never receives rule content or a feature event.
	 *
	 * @throws IOException if test-owned directories fail
	 * @throws InterruptedException if the account check is interrupted
	 */
	@Test
	public void withholdsRuleWithoutLogin() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-rule-no-login-"));
			Path plugin = Files.createDirectory(root.resolve("plugin"));
			Path rule = plugin.resolve("rules/include/common/write-clearly.md");
			Files.createDirectories(rule.getParent());
			Files.writeString(rule, "private rule");
			Path codexHome = Files.createDirectory(root.resolve("codex-home"));
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream error = new PrintStream(new ByteArrayOutputStream(), true, UTF_8))
			{
				try
				{
					CodexRuleLoad.load(Map.of("CODEX_HOME", codexHome.toString()), plugin,
						new String[]{"include/common/write-clearly.md"}, output, error);
					throw new AssertionError("Rule content requires a signed-in account");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "recovery").contains("Sign in with");
				}
			}
			requireThat(output.size(), "undeliveredBytes").isEqualTo(0);
			requireThat(Files.exists(codexHome.resolve("plugins/data/cat-cat/feature-outbox")),
				"unattributedEvent").isFalse();
		}
	}

	/**
	 * Counts retained event files without counting the outbox lock.
	 *
	 * @param directory private feature outbox
	 * @return number of retained JSON events
	 * @throws IOException if the outbox cannot be read
	 */
	private static long pendingCount(Path directory) throws IOException
	{
		try (Stream<Path> entries = Files.list(directory))
		{
			return entries.filter(path -> path.getFileName().toString().endsWith(".json")).count();
		}
	}
}
