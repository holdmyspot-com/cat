package io.github.cowwoc.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.codex.CodexSessionStartHook;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Verifies Codex SessionStart rule injection.
 */
public final class CodexSessionStartHookTest
{
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
	 * Verifies CAT installation variables must agree with Codex's harness plugin root and cannot override it.
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
	 * Verifies Codex injects a user rule derived from its harness configuration directory without placing it in a
	 * project.
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
	 * Verifies Codex receives bundled lazy rule paths below the {@code ${CAT_PLUGIN_ROOT}} notation, which its skill
	 * environment rule resolves, instead of absolute paths or references relative to removed rule sources.
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

			requireThat(context, "context").contains("`${CAT_PLUGIN_ROOT}/rules/include/java/javadoc.md`").
				doesNotContain(lazyRule.toString()).doesNotContain("`../include/java/javadoc.md`");
		}
	}

	/**
	 * Verifies Codex receives the hook's plugin root wherever a bundled rule writes the plugin-root directive, while
	 * the {@code ${CAT_PLUGIN_ROOT}} notation stays as written.
	 */
	@Test
	public void statesPluginRootInBundledRules() throws IOException
	{
		// Requirement: R-I5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/codex")).resolve("skill-env.md"),
				"The plugin directory is `" + ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE + "`. Replace " +
				"`${CAT_PLUGIN_ROOT}` with it.\n");

			String context = context(invoke("{\"cwd\":\"" + project + "\"}",
				Map.of("PLUGIN_ROOT", plugin.toString()), Path.of(".")));

			requireThat(context, "context").contains("The plugin directory is `" + plugin + "`.").
				contains("`${CAT_PLUGIN_ROOT}`").doesNotContain(ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE);
		}
	}

	/**
	 * Verifies harness cwd takes precedence over the configured project directory.
	 */
	@Test
	public void prefersHarnessCwd() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuredProject = cleanup.add(Files.createTempDirectory("cat-configured-project-"));
			Path harnessProject = cleanup.add(Files.createTempDirectory("cat-harness-project-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.writeString(Files.createDirectories(configuredProject.resolve(".cat/rules/common")).resolve("rule.md"),
				"configured project");
			Files.writeString(Files.createDirectories(harnessProject.resolve(".cat/rules/common")).resolve("rule.md"),
				"harness project");

			String context = context(invoke("{\"cwd\":\"" + harnessProject + "\"}", Map.of("PLUGIN_ROOT",
				plugin.toString()), Path.of(".")));

			requireThat(context, "context").contains("harness project").doesNotContain("configured project");
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
	 * Runs the SessionStart hook with supplied input and process state, then captures its standard streams.
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
		Map<String, String> harnessEnvironment = new HashMap<>(environment);
		harnessEnvironment.putIfAbsent("HOME", workingDirectory.resolve("home").toString());
		CodexSessionStartHook.run(new String[0], new ByteArrayInputStream(input.getBytes(UTF_8)), output,
			new PrintStream(error, true, UTF_8), harnessEnvironment, workingDirectory);
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
	 * Parses one harness hook response within its bounded JSON operation.
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
