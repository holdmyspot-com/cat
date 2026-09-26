package io.github.cowwoc.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpServer;
import io.github.cowwoc.cat.codex.CodexSubagentStartHook;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.auth.WorkOsDeviceAuthClient;
import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Verifies Codex SubagentStart rule injection.
 */
public final class CodexSubagentStartHookTest
{
	/**
	 * Verifies the native hook selects only rules addressed to the spawned Codex agent type.
	 */
	@Test
	public void filtersRulesForSubagentAudience() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-subagent-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path rules = Files.createDirectories(plugin.resolve("rules/common"));
			Files.writeString(rules.resolve("main.md"), "---\nagents: [\"main\"]\n---\nmain guidance");
			Files.writeString(rules.resolve("subagents.md"), "---\nagents: [\"subagents\"]\n---\nsubagent guidance");
			Files.writeString(rules.resolve("reviewer.md"), "---\nagents: [\"cat:reviewer\"]\n---\nreviewer guidance");

			String reviewer = context(invokeAuthorized("{\"cwd\":\"" + project +
				"\",\"agent_type\":\"cat:reviewer\"}", Map.of("PLUGIN_ROOT", plugin.toString()), Path.of(".")));
			String worker = context(invokeAuthorized("{\"cwd\":\"" + project +
				"\",\"agent_type\":\"cat:worker\"}", Map.of("PLUGIN_ROOT", plugin.toString()), Path.of(".")));

			requireThat(reviewer, "reviewer").contains("subagent guidance").contains("reviewer guidance").
				doesNotContain("main guidance");
			requireThat(worker, "worker").contains("subagent guidance").doesNotContain("reviewer guidance").
				doesNotContain("main guidance");
		}
	}

	/**
	 * A spawned agent receives no CAT rule content when its login is missing.
	 *
	 * @throws IOException if the hook response is invalid
	 */
	@Test
	public void withholdsRulesWithoutAccount() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-subagent-no-account-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("rule.md"),
				"account-only rule");
			String context = context(invoke("{\"cwd\":\"" + project +
				"\",\"agent_type\":\"cat:worker\"}", Map.of("PLUGIN_ROOT", plugin.toString()), project));
			requireThat(context, "context").doesNotContain("account-only rule");
		}
	}

	/**
	 * Saves a selected login and asks a local account server before running the subagent hook.
	 *
	 * @param input native SubagentStart input
	 * @param environment hook environment
	 * @param workingDirectory hook process directory
	 * @return captured hook response
	 * @throws IOException if the login or local server cannot be prepared
	 */
	private static HookResult invokeAuthorized(String input, Map<String, String> environment, Path workingDirectory)
		throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-subagent-auth-"));
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/cat/v1/accounts", exchange ->
			{
				byte[] body = "{\"accounts\":[{\"id\":17,\"email\":\"user@example.com\",\"verified\":true}]}".
					getBytes(UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream response = exchange.getResponseBody())
				{
					response.write(body);
				}
			});
			try
			{
				server.start();
				URI base = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
				Files.createDirectories(codexHome.resolve("plugins/data/cat-cat"));
				new WorkOsSessionStore(codexHome.resolve("plugins/data/cat-cat/auth")).save(
					new WorkOsSessionStore.Session(base, "client_test",
						new WorkOsDeviceAuthClient.Tokens("user_test", "access", "refresh"), OptionalLong.of(17)));
				Map<String, String> authorizedEnvironment = new HashMap<>(environment);
				authorizedEnvironment.put("CODEX_HOME", codexHome.toString());
				return invoke(input, authorizedEnvironment, workingDirectory);
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * Runs the SubagentStart hook with supplied input and process state.
	 *
	 * @param input the hook input
	 * @param environment the process environment
	 * @param workingDirectory the process working directory
	 * @return the captured standard streams
	 */
	private static HookResult invoke(String input, Map<String, String> environment, Path workingDirectory)
	{
		assert input != null;
		assert environment != null;
		assert workingDirectory != null;
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		Map<String, String> nativeEnvironment = new HashMap<>(environment);
		nativeEnvironment.putIfAbsent("HOME", workingDirectory.resolve("home").toString());
		CodexSubagentStartHook.run(new String[0], new ByteArrayInputStream(input.getBytes(UTF_8)), output,
			new PrintStream(error, true, UTF_8), nativeEnvironment, workingDirectory);
		return new HookResult(output.toString(UTF_8), error.toString(UTF_8));
	}

	/**
	 * Extracts the returned SubagentStart context.
	 *
	 * @param result the captured hook result
	 * @return the additional context
	 * @throws IOException if the result is not JSON
	 */
	private static String context(HookResult result) throws IOException
	{
		assert result != null;
		try (ToolScope scope = new ToolScope())
		{
			JsonNode envelope = scope.json().readTree(result.output());
			return envelope.at("/hookSpecificOutput/additionalContext").asString();
		}
	}

	/**
	 * Captures the hook's standard streams.
	 *
	 * @param output the standard output
	 * @param error the standard error
	 */
	private record HookResult(String output, String error)
	{
	}
}
