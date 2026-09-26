package io.github.cowwoc.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpServer;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.auth.WorkOsDeviceAuthClient;
import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;
import io.github.cowwoc.cat.common.usage.UsageReportOutbox;
import io.github.cowwoc.cat.codex.CodexMeteringVersion;
import io.github.cowwoc.cat.codex.CodexSessionStartHook;
import io.github.cowwoc.cat.codex.CodexUsageReportEncoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Verifies Codex SessionStart rule injection.
 */
public final class CodexSessionStartHookTest
{
	/**
	 * Retries pending usage when an online account check restores connectivity.
	 *
	 * @throws IOException if the test-owned login, outbox, or hook response cannot be used
	 */
	@Test
	public void flushesPendingUsageAfterReconnect() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-reconnect-project-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-reconnect-plugin-"));
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-reconnect-home-"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("rule.md"),
				"account-only rule");
			Path data = Files.createDirectories(codexHome.resolve("plugins/data/cat-cat"));
			UsageReportOutbox outbox = new UsageReportOutbox(data.resolve("outbox"));
			UUID turnId = UUID.randomUUID();
			CodexUsageReportEncoder encoder = new CodexUsageReportEncoder();
			UsageReportOutbox.PendingReport pending = outbox.enqueue("workos:user_test", 17,
				"codex:" + turnId + ":42", 42, eventId -> encoder.encode(new CodexUsageReportEncoder.Report(
					turnId, eventId, 42, CodexMeteringVersion.V_0_155_0)));
			AtomicInteger sent = new AtomicInteger();
			AtomicInteger featureInvocations = new AtomicInteger();
			AtomicReference<String> featureBody = new AtomicReference<>();
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
			server.createContext("/cat/v1/verified-usage-reports", exchange ->
			{
				requireThat(exchange.getRequestHeaders().getFirst("X-HoldMySpot-Account-ID"), "accountHeader").
					isEqualTo("17");
				int request = sent.incrementAndGet();
				byte[] body;
				int status;
				if (request == 1)
				{
					body = ("{\"event_id\":\"" + pending.eventId() +
						"\",\"accounted_output_tokens\":42}").getBytes(UTF_8);
					status = 200;
				}
				else
				{
					body = "unavailable".getBytes(UTF_8);
					status = 503;
				}
				exchange.sendResponseHeaders(status, body.length);
				try (OutputStream response = exchange.getResponseBody())
				{
					response.write(body);
				}
			});
			server.createContext("/cat/v1/feature-invocations", exchange ->
			{
				requireThat(exchange.getRequestHeaders().getFirst("X-HoldMySpot-Account-ID"),
					"featureAccountHeader").isEqualTo("17");
				featureBody.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
				featureInvocations.incrementAndGet();
				exchange.sendResponseHeaders(204, -1);
				exchange.close();
			});
			try
			{
				server.start();
				URI base = URI.create("http://localhost:" + server.getAddress().getPort() + "/cat/");
				new WorkOsSessionStore(data.resolve("auth")).save(new WorkOsSessionStore.Session(base,
					"client_test", new WorkOsDeviceAuthClient.Tokens("user_test", "access", "refresh"),
					OptionalLong.of(17)));

				HookResult result = invokeRaw("{\"cwd\":\"" + project + "\"}", Map.of("PLUGIN_ROOT",
					plugin.toString(), "CODEX_HOME", codexHome.toString()), project);

				requireThat(context(result), "context").contains("account-only rule");
				requireThat(sent.get(), "sentReports").isEqualTo(1);
				requireThat(featureInvocations.get(), "featureInvocations").isEqualTo(1);
				requireThat(featureBody.get(), "featureBody").contains("\"feature_id\":\"common/rule.md\"");
				requireThat(Files.exists(data.resolve("outbox").resolve(pending.eventId() + ".json")),
					"pendingReport").isFalse();
				UsageReportOutbox.PendingReport unsent = outbox.enqueue("workos:user_test", 17,
					"codex:" + turnId + ":43", 43, eventId -> encoder.encode(new CodexUsageReportEncoder.Report(
						turnId, eventId, 43, CodexMeteringVersion.V_0_155_0)));
				HookResult failedDelivery = invokeRaw("{\"cwd\":\"" + project + "\"}", Map.of("PLUGIN_ROOT",
					plugin.toString(), "CODEX_HOME", codexHome.toString()), project);
				requireThat(context(failedDelivery), "contextAfterFailedDelivery").contains("account-only rule");
				requireThat(sent.get(), "attemptedReports").isEqualTo(2);
				requireThat(Files.exists(data.resolve("outbox").resolve(unsent.eventId() + ".json")),
					"retainedReport").isTrue();
			}
			finally
			{
				server.stop(0);
			}
		}
	}
	/**
	 * Withholds direct rules when no authenticated HoldMySpot account is available.
	 */
	@Test
	public void withholdsRulesWithoutAccount() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-no-account-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("rule.md"),
				"account-only rule");

			HookResult result = invokeRaw("{\"cwd\":\"" + project + "\"}",
				Map.of("PLUGIN_ROOT", plugin.toString()), project);

			requireThat(context(result), "context").doesNotContain("account-only rule");
			requireThat(readResponse(result).path("systemMessage").asString(), "recoveryMessage").
				contains("client/bin/login");
			requireThat(readResponse(result).has("continue"), "stoppedAgent").isFalse();
		}
	}

	/**
	 * A damaged saved login withholds rules and explains recovery without stopping the agent.
	 *
	 * @throws IOException if the test-owned login or hook response cannot be used
	 */
	@Test
	public void withholdsRulesForInvalidLogin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-invalid-login-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("rule.md"),
				"account-only rule");
			Path auth = Files.createDirectories(codexHome.resolve("plugins/data/cat-cat/auth"));
			Files.setPosixFilePermissions(auth, PosixFilePermissions.fromString("rwx------"));
			Path session = Files.writeString(auth.resolve("session.json"), "not-json");
			Files.setPosixFilePermissions(session, PosixFilePermissions.fromString("rw-------"));

			HookResult result = invokeRaw("{\"cwd\":\"" + project + "\"}", Map.of("PLUGIN_ROOT",
				plugin.toString(), "CODEX_HOME", codexHome.toString()), project);
			requireThat(context(result), "context").doesNotContain("account-only rule");
			requireThat(readResponse(result).has("continue"), "stoppedAgent").isFalse();
			requireThat(readResponse(result).path("systemMessage").asString(), "recoveryMessage").
				contains("client/bin/login");
		}
	}

	/**
	 * Withholds rules when the server no longer links the saved account to the current login.
	 */
	@Test
	public void withholdsRulesAfterAccountUnlink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-unlinked-account-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path userHome = cleanup.add(Files.createTempDirectory("cat-user-home-"));
			Path codexHome = Files.createDirectory(userHome.resolve(".codex"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("rule.md"),
				"account-only rule");
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/cat/v1/accounts", exchange ->
			{
				byte[] body = "{\"accounts\":[{\"id\":18,\"email\":\"other@example.com\",\"verified\":true}]}".
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
				WorkOsDeviceAuthClient.Tokens tokens = new WorkOsDeviceAuthClient.Tokens("user_test", "access",
					"refresh");
				Files.createDirectories(codexHome.resolve("plugins/data/cat-cat"));
				new WorkOsSessionStore(codexHome.resolve("plugins/data/cat-cat/auth")).save(
					new WorkOsSessionStore.Session(base, "client_test", tokens, OptionalLong.of(17)));

				HookResult result = invokeRaw("{\"cwd\":\"" + project + "\"}", Map.of("PLUGIN_ROOT",
					plugin.toString(), "HOME", userHome.toString(), "CODEX_HOME", codexHome.toString()), project);

				requireThat(context(result), "context").doesNotContain("account-only rule");
				requireThat(readResponse(result).path("systemMessage").asString(), "recoveryMessage").
					contains("no longer linked");
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * Verifies unconditional rules are injected while path-scoped rules remain lazy.
	 */
	@Test
	public void injectsDirectAndPathScopedRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path rules = Files.createDirectories(project.resolve(".cat/rules/common"));
			Files.writeString(rules.resolve("z.md"), "z rule");
			Files.writeString(rules.resolve("a.md"), "---\npaths: [\"*.java\"]\n---\njava rule");
			HookResult result = invoke("{\"cwd\":\"" + project + "\"}", Map.of("PLUGIN_ROOT", project.toString()),
				Path.of("."));
			String context = context(result);
			requireThat(context, "context").contains("z rule").
				contains("Load `.cat/rules/common/a.md` only when an accessed file matches [*.java].").
				doesNotContain("java rule");
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies CAT installation variables must agree with Codex's native plugin root and cannot override it.
	 */
	@Test
	public void rejectsConflictingInstallationDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path conflictingPlugin = cleanup.add(Files.createTempDirectory("cat-conflicting-plugin-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("rule.md"), "rule");

			HookResult matching = invoke("{\"cwd\":\"" + project + "\"}", Map.of("PLUGIN_ROOT", plugin.toString(),
				"CAT_INSTALL_DIR", plugin.toString()), Path.of("."));
			HookResult conflicting = invoke("{\"cwd\":\"" + project + "\"}", Map.of("PLUGIN_ROOT", plugin.toString(),
				"CAT_INSTALL_DIR", conflictingPlugin.toString()), Path.of("."));
			HookResult unsupported = invoke("{\"cwd\":\"" + project + "\"}", Map.of("PLUGIN_ROOT", plugin.toString(),
				"CAT_PROJECT_DIR", project.toString()), Path.of("."));

			requireThat(context(matching), "matchingContext").contains("rule");
			requireThat(matching.error(), "matchingError").isEmpty();
			assertStopped(conflicting, "conflicting");
			requireThat(conflicting.error(), "conflictingError").contains("CAT_INSTALL_DIR conflicts with Codex PLUGIN_ROOT");
			assertStopped(unsupported, "unsupported");
			requireThat(unsupported.error(), "unsupportedError").contains("CAT_PROJECT_DIR conflicts with Codex PLUGIN_ROOT");
		}
	}

	/**
	 * Verifies bundled rules precede project rules in the harness-specific order.
	 */
	@Test
	public void injectsAllRuleLocationsInOrder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("a.md"), "plugin common");
			Files.writeString(Files.createDirectories(plugin.resolve("rules/codex")).resolve("a.md"), "plugin codex");
			Files.writeString(Files.createDirectories(project.resolve(".cat/rules/common")).resolve("a.md"),
				"project common");
			Files.writeString(Files.createDirectories(project.resolve(".cat/rules/codex")).resolve("a.md"), "project codex");
			String context = context(invoke("{\"cwd\":\"" + project + "\"}",
				Map.of("PLUGIN_ROOT", plugin.toString()), Path.of(".")));
			requireThat(context.indexOf("plugin common"), "pluginCommonIndex").
				isLessThan(context.indexOf("plugin codex"));
			requireThat(context.indexOf("plugin codex"), "pluginCodexIndex").
				isLessThan(context.indexOf("project common"));
			requireThat(context.indexOf("project common"), "projectCommonIndex").
				isLessThan(context.indexOf("project codex"));
		}
	}

	/**
	 * Verifies Codex injects a user rule derived from its native configuration directory without placing it in a project.
	 */
	@Test
	public void injectsUserRulesOutsideTheProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path userHome = cleanup.add(Files.createTempDirectory("cat-user-home-"));
			Path codexHome = userHome.resolve(".codex");
			Files.writeString(Files.createDirectories(userHome.resolve(".cat/rules/common")).resolve("personal.md"),
				"personal guidance");

			HookResult result = invoke("{\"cwd\":\"" + project + "\"}", Map.of("PLUGIN_ROOT", plugin.toString(),
				"CODEX_HOME", codexHome.toString(), "HOME", userHome.toString()), Path.of("."));

			requireThat(context(result), "context").contains("personal guidance");
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies path-scoped bundled rules remain lazy.
	 */
	@Test
	public void keepsPathScopedBundledRulesLazy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("path.md"),
				"---\npaths: [\"*.java\"]\n---\nbundled java rule");
			String context = context(invoke("{\"cwd\":\"" + project + "\"}",
				Map.of("PLUGIN_ROOT", plugin.toString()), Path.of(".")));
			requireThat(context, "context").contains("Load `rules/common/path.md` only when an accessed file matches " +
				"[*.java].").doesNotContain("bundled java rule");
		}
	}

	/**
	 * Verifies Codex receives a rule-load command for bundled lazy rules.
	 */
	@Test
	public void resolvesBundledLazyRuleReferences() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path lazyRule = plugin.resolve("rules/include/java/javadoc.md");
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("default-rules.md"),
				"Before editing Java, lazy load `../include/java/javadoc.md`.\n");
			Files.createDirectories(lazyRule.getParent());
			Files.writeString(lazyRule, "Javadoc guidance\n");

			String context = context(invoke("{\"cwd\":\"" + project + "\"}",
				Map.of("PLUGIN_ROOT", plugin.toString()), Path.of(".")));

			requireThat(context, "context").contains("`\"$CAT_PLUGIN_ROOT/client/bin/rule-load\" " +
				"include/java/javadoc.md`").doesNotContain("`" + lazyRule + "`").
				doesNotContain("`../include/java/javadoc.md`");
		}
	}

	/**
	 * Verifies native cwd takes precedence over the configured project directory.
	 */
	@Test
	public void prefersNativeCwd() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuredProject = cleanup.add(Files.createTempDirectory("cat-configured-project-"));
			Path nativeProject = cleanup.add(Files.createTempDirectory("cat-native-project-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.writeString(Files.createDirectories(configuredProject.resolve(".cat/rules/common")).resolve("rule.md"),
				"configured project");
			Files.writeString(Files.createDirectories(nativeProject.resolve(".cat/rules/common")).resolve("rule.md"),
				"native project");

			String context = context(invoke("{\"cwd\":\"" + nativeProject + "\"}", Map.of("PLUGIN_ROOT",
				plugin.toString()), Path.of(".")));

			requireThat(context, "context").contains("native project").doesNotContain("configured project");
		}
	}

	/**
	 * Verifies a blank hook input uses the process working directory.
	 */
	@Test
	public void acceptsBlankInput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.writeString(Files.createDirectories(project.resolve(".cat/rules/common")).resolve("rule.md"),
				"working directory");

			HookResult result = invoke("", Map.of("PLUGIN_ROOT", plugin.toString()), project);

			requireThat(context(result), "context").contains("working directory");
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies malformed input, missing installation directories, and invalid rules fail safely.
	 */
	@Test
	public void failsSafelyForInvalidInputsAndRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.writeString(Files.createDirectories(project.resolve(".cat/rules/common")).resolve("rule.md"),
				"---\npaths:");

			HookResult malformed = invoke("{", Map.of("PLUGIN_ROOT", plugin.toString()), project);
			HookResult missingInstallDirectory = invoke("{}", Map.of("CAT_INSTALL_DIR", project.toString()), project);
			HookResult invalidRule = invoke("{}", Map.of("PLUGIN_ROOT", plugin.toString()), project);
			HookResult oversized = invoke("x".repeat(1_048_577), Map.of("PLUGIN_ROOT", plugin.toString()),
				project);

			assertStopped(malformed, "malformed");
			assertStopped(missingInstallDirectory, "missingInstallDirectory");
			assertStopped(invalidRule, "invalidRule");
			assertStopped(oversized, "oversized");
			requireThat(malformed.error(), "malformedError").contains("CAT SessionStart:");
			requireThat(missingInstallDirectory.error(), "missingInstallDirectoryError").
				contains("Codex PLUGIN_ROOT is not set");
			requireThat(invalidRule.error(), "invalidRuleError").contains("Unterminated frontmatter");
			requireThat(oversized.error(), "oversizedError").contains("input is too large");
		}
	}

	/**
	 * Verifies invalid project roots and unsafe or oversized rule files fail safely.
	 */
	@Test
	public void failsForUnsafeRootsAndRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path outsideDirectory = cleanup.add(Files.createTempDirectory("cat-outside-rule-"));
			Path outside = outsideDirectory.resolve("outside.md");
			Files.writeString(outside, "outside rule");
			Path rules = Files.createDirectories(project.resolve(".cat/rules/common"));
			Files.createSymbolicLink(rules.resolve("unsafe.md"), outside);

			HookResult invalidProject = invoke("{\"cwd\":\"" + project.resolve("missing") + "\"}",
				Map.of("PLUGIN_ROOT", plugin.toString()), project);
			HookResult unsafeRule = invoke("{}", Map.of("PLUGIN_ROOT", plugin.toString()), project);
			Files.delete(rules.resolve("unsafe.md"));
			Files.writeString(rules.resolve("large.md"), "x".repeat(1_048_577));
			HookResult oversizedRule = invoke("{}", Map.of("PLUGIN_ROOT", plugin.toString()), project);

			assertStopped(invalidProject, "invalidProject");
			assertStopped(unsafeRule, "unsafeRule");
			assertStopped(oversizedRule, "oversizedRule");
			requireThat(invalidProject.error(), "invalidProjectError").contains("Project directory is not a directory");
			requireThat(unsafeRule.error(), "unsafeRuleError").
				contains("CAT rule must be a regular file, not a symbolic link").
				contains("Replace the symbolic link");
			requireThat(oversizedRule.error(), "oversizedRuleError").contains("CAT rule is too large");
		}
	}

	/**
	 * Verifies a failed SessionStart response stops the current turn and explains the failure.
	 *
	 * @param result the captured hook result
	 * @param name the assertion-name prefix
	 * @throws IOException if the response is not JSON
	 */
	private static void assertStopped(HookResult result, String name) throws IOException
	{
		JsonNode envelope = readResponse(result);
		requireThat(envelope.has("continue"), name + "ContinueField").isTrue();
		requireThat(envelope.path("continue").asBoolean(), name + "Continue").isFalse();
		requireThat(envelope.path("stopReason").asString(), name + "StopReason").isNotBlank();
		requireThat(envelope.path("systemMessage").asString(), name + "SystemMessage").isNotBlank();
	}

	/**
	 * Runs the SessionStart hook with a saved login authorized by a local account server.
	 *
	 * @param input the hook input
	 * @param environment the process environment
	 * @param workingDirectory the process working directory
	 * @return the captured standard streams
	 * @throws IOException if the test-owned login or local account server cannot be prepared
	 */
	private static HookResult invoke(String input, Map<String, String> environment, Path workingDirectory)
		throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-authorized-codex-home-"));
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
			server.createContext("/cat/v1/feature-invocations", exchange ->
			{
				exchange.getRequestBody().readAllBytes();
				exchange.sendResponseHeaders(204, -1);
				exchange.close();
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
				return invokeRaw(input, authorizedEnvironment, workingDirectory);
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * Runs the native SessionStart hook with the exact supplied login state.
	 *
	 * @param input native hook input
	 * @param environment process environment, including any saved login location
	 * @param workingDirectory hook process directory
	 * @return captured hook output and diagnostics
	 */
	private static HookResult invokeRaw(String input, Map<String, String> environment, Path workingDirectory)
	{
		assert input != null;
		assert environment != null;
		assert workingDirectory != null;
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		Map<String, String> nativeEnvironment = new HashMap<>(environment);
		nativeEnvironment.putIfAbsent("HOME", workingDirectory.resolve("home").toString());
		CodexSessionStartHook.run(new String[0], new ByteArrayInputStream(input.getBytes(UTF_8)), output,
			new PrintStream(error, true, UTF_8), nativeEnvironment, workingDirectory);
		return new HookResult(output.toString(UTF_8), error.toString(UTF_8));
	}

	/**
	 * Extracts the returned SessionStart context.
	 *
	 * @param result the captured hook result
	 * @return the additional context
	 * @throws IOException if the result is not JSON
	 */
	private static String context(HookResult result) throws IOException
	{
		assert result != null;
		JsonNode envelope = readResponse(result);
		return envelope.at("/hookSpecificOutput/additionalContext").asString();
	}

	/**
	 * Parses one native hook response within its bounded JSON operation.
	 *
	 * @param result the captured hook result
	 * @return the parsed response envelope
	 * @throws IOException if the response is not JSON
	 */
	private static JsonNode readResponse(HookResult result) throws IOException
	{
		assert result != null;
		try (ToolScope scope = new ToolScope())
		{
			return scope.json().readTree(result.output());
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
