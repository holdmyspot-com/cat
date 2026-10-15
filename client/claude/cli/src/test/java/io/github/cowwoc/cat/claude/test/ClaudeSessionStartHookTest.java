package io.github.cowwoc.cat.claude.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.claude.ClaudeSessionEndHook;
import io.github.cowwoc.cat.claude.ClaudeSessionStartHook;
import io.github.cowwoc.cat.claude.ClaudeSubagentStartHook;
import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests {@link ClaudeSessionStartHook}.
 */
public final class ClaudeSessionStartHookTest
{
	/**
	 * Verifies Claude receives a short instruction that locates the complete main-agent rules in CAT's temporary root and
	 * requires reading that whole file rather than a searched subset.
	 */
	@Test
	public void injectsClaudeRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/common.md", "plugin common");
			write(plugin, "rules/claude/claude.md", "plugin claude");
			write(plugin, "rules/codex/codex.md", "plugin codex");
			write(project, ".cat/rules/common/common.md", "project common");
			write(project, ".cat/rules/claude/claude.md", "project claude");
			write(project, ".cat/rules/codex/codex.md", "project codex");

			HookResult result = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()),
				project);
			String instruction = context(result);
			Path rules = referencedFile(instruction);

			requireThat(instruction, "instruction").startsWith("# CAT SessionStart Context").
				contains("Read the whole file").contains("with the Read tool").
				contains("Do not search it, list its headings, or read only part of it").doesNotContain("plugin common").
				doesNotContain("plugin claude").doesNotContain("project common").doesNotContain("project claude").
				doesNotContain("plugin codex");
			try (ToolScope scope = ToolScope.forWorkingDirectory(project))
			{
				requireThat(rules.startsWith(scope.catTemp()), "rulesLocation").isTrue();
			}
			try
			{
				String completeRules = Files.readString(rules);
				requireThat(completeRules, "completeRules").contains("plugin common").contains("plugin claude").
					contains("project common").contains("project claude").doesNotContain("plugin codex");
			}
			finally
			{
				Files.deleteIfExists(rules);
			}
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies Claude injects a user rule derived from its harness configuration directory without placing it in a
	 * project.
	 */
	@Test
	public void injectsUserRulesOutsideTheProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path userHome = cleanup.add(Files.createTempDirectory("cat-user-home-"));
			Path configuration = userHome.resolve(".claude");
			write(userHome, ".cat/rules/common/personal.md", "personal guidance");

			HookResult result = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CLAUDE_CONFIG_DIR", configuration.toString(), Platform.current().homeVariable(), userHome.toString()),
				project);

			requireThat(completeContext(result), "context").contains("personal guidance");
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies CAT installation variables must agree with Claude's harness plugin root and cannot override it.
	 */
	@Test
	public void rejectsConflictingInstallationDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path conflictingPlugin = cleanup.add(Files.createTempDirectory("cat-conflicting-plugin-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/rule.md", "rule");

			HookResult matching = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CAT_INSTALL_DIR", plugin.toString()), project);
			HookResult conflicting = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CAT_INSTALL_DIR", conflictingPlugin.toString()), project);
			HookResult unsupported = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CAT_PROJECT_DIR", project.toString()), project);

			requireThat(completeContext(matching), "matchingContext").contains("rule");
			requireThat(matching.error(), "matchingError").isEmpty();
			requireThat(conflicting.output(), "conflictingOutput").isEqualTo("{}\n");
			requireThat(conflicting.error(), "conflictingError").
				contains("CAT_INSTALL_DIR conflicts with Claude CLAUDE_PLUGIN_ROOT");
			requireThat(unsupported.output(), "unsupportedOutput").isEqualTo("{}\n");
			requireThat(unsupported.error(), "unsupportedError").
				contains("CAT_PROJECT_DIR conflicts with Claude CLAUDE_PLUGIN_ROOT");
		}
	}

	/**
	 * Verifies SessionEnd removes the rule context owned by its session.
	 */
	@Test
	public void deletesContextWhenSessionEnds() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/rules.md", "rules");
			Path context = referencedFile(context(invoke(cwdInput(project),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project)));

			String sessionId = context.getFileName().toString().replace(".md", "");
			String error = invokeSessionEnd("{\"session_id\":\"" + sessionId + "\"}",
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);

			requireThat(Files.exists(context), "contextExists").isFalse();
			requireThat(error, "error").isEmpty();
		}
	}

	/**
	 * Verifies one resumable session retains its context until it ends and that ending another session cannot remove it.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void preservesOtherResumableSessionContext() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/rules.md", "rules");
			String firstSession = UUID.randomUUID().toString();
			String secondSession = UUID.randomUUID().toString();
			Map<String, String> environment = Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString());
			Path first = referencedFile(context(invoke("{\"cwd\":" + jsonString(project) + ",\"session_id\":\"" +
				firstSession + "\"}", environment, project)));
			Path second = referencedFile(context(invoke("{\"cwd\":" + jsonString(project) + ",\"session_id\":\"" +
				secondSession + "\"}", environment, project)));

			requireThat(Files.exists(first), "firstBeforeSessionEnd").isTrue();
			requireThat(Files.exists(second), "secondBeforeSessionEnd").isTrue();
			String error = invokeSessionEnd("{\"session_id\":\"" + firstSession + "\"}", environment, project);

			requireThat(Files.exists(first), "firstAfterSessionEnd").isFalse();
			requireThat(Files.exists(second), "secondAfterOtherSessionEnd").isTrue();
			requireThat(error, "error").isEmpty();
		}
	}

	/**
	 * Verifies SessionEnd also removes context owned by a subagent in its session.
	 */
	@Test
	public void deletesSubagentContextWhenSessionEnds() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/rules.md", "rules");
			Path context = referencedFile(context(invokeSubagent(cwdInput(project),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project)));
			String suffix = "-agent-test.md";
			String sessionId = context.getFileName().toString().replace(suffix, "");

			String error = invokeSessionEnd("{\"session_id\":\"" + sessionId + "\"}",
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);

			requireThat(Files.exists(context), "contextExists").isFalse();
			requireThat(error, "error").isEmpty();
		}
	}

	/**
	 * Verifies path-scoped rules remain lazy and the hook event's directory takes precedence over configuration.
	 */
	@Test
	public void keepsRulesLazyAndPrefersHarnessCwd() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuredProject = cleanup.add(Files.createTempDirectory("cat-configured-project-"));
			Path harnessProject = cleanup.add(Files.createTempDirectory("cat-harness-project-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			write(configuredProject, ".cat/rules/common/rule.md", "configured project");
			write(harnessProject, ".cat/rules/claude/path.md", "---\npaths: [\"*.java\"]\n---\nproject Java rule");
			write(harnessProject, ".cat/rules/claude/rule.md", "harness project");

			String context = completeContext(invoke(cwdInput(harnessProject),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), harnessProject));

			requireThat(context, "context").contains("harness project").
				contains("Load `" + harnessProject.resolve(".cat/rules/claude/path.md") +
					"` only when you read or edit a file inside the project `${CAT_PROJECT_ROOT}" +
					"` whose path relative to it matches [*.java].").
				doesNotContain("configured project").
				doesNotContain("project Java rule");
		}
	}

	/**
	 * Verifies the harness SubagentStart hook injects only rules addressed to the spawned agent type.
	 */
	@Test
	public void injectsRulesForSubagentAudience() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			write(plugin, "rules/common/main.md", "---\nagents: [\"main\"]\n---\nmain guidance");
			write(plugin, "rules/common/subagents.md", "---\nagents: [\"subagents\"]\n---\nsubagent guidance");
			write(plugin, "rules/common/reviewer.md", "---\nagents: [\"cat:reviewer\"]\n---\nreviewer guidance");

			String reviewer = completeContext(invokeSubagent("{\"cwd\":" + jsonString(project) +
				",\"agent_type\":\"cat:reviewer\"}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project));
			String explorer = completeContext(invokeSubagent("{\"cwd\":" + jsonString(project) +
				",\"agent_type\":\"Explore\"}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project));

			requireThat(reviewer, "reviewer").contains("subagent guidance").contains("reviewer guidance").
				doesNotContain("main guidance");
			requireThat(explorer, "explorer").contains("subagent guidance").doesNotContain("reviewer guidance").
				doesNotContain("main guidance");
		}
	}

	/**
	 * Verifies Claude receives bundled lazy rule paths below the {@code ${CAT_PLUGIN_ROOT}} notation, which its skill
	 * environment rule resolves, instead of absolute paths or references relative to removed rule sources.
	 */
	@Test
	public void resolvesBundledLazyRuleReferences() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path lazyRule = plugin.resolve("rules/include/java/javadoc.md");
			write(plugin, "rules/common/default-rules.md", "Before editing Java, lazy load `../include/java/javadoc.md`.\n");
			write(plugin, "rules/include/java/javadoc.md", "Javadoc guidance\n");

			String context = completeContext(invoke(cwdInput(project),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project));

			requireThat(context, "context").contains("`${CAT_PLUGIN_ROOT}/rules/include/java/javadoc.md`").
				doesNotContain(lazyRule.toString()).doesNotContain("`../include/java/javadoc.md`");
		}
	}

	/**
	 * Verifies the hook creates {@code .cat/work/temp} in the session's own directory, which the injected rules name
	 * as the project directory, even when an enclosing directory is a CAT project.
	 */
	@Test
	public void createsCatTempInSessionDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.createDirectories(parent.resolve(".cat"));
			Path session = Files.createDirectories(parent.resolve("module"));

			HookResult result = invoke(cwdInput(session),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), session);

			requireThat(result.error(), "error").isEmpty();
			requireThat(Files.isDirectory(session.resolve(".cat/work/temp")), "catTempExists").isTrue();
		}
	}

	/**
	 * Verifies that the directory Claude started in stays the project directory after the agent changes into a
	 * subdirectory, so the hooks neither write a context file into the subdirectory nor miss it when the session ends.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void keepsLaunchDirectoryAfterCd() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			write(project, ".cat/rules/common/rule.md", "launch project");
			Path module = Files.createDirectories(project.resolve("module"));
			Map<String, String> environment = Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CLAUDE_PROJECT_DIR", project.toString());

			HookResult result = invoke(cwdInput(module), environment, module);
			Path context = referencedFile(context(result));

			requireThat(result.error(), "error").isEmpty();
			requireThat(Files.readString(context), "context").contains("launch project");
			try (ToolScope scope = ToolScope.forWorkingDirectory(project))
			{
				requireThat(context.startsWith(scope.catTemp()), "contextInLaunchDirectory").isTrue();
			}
			requireThat(Files.exists(module.resolve(".cat")), "moduleCatExists").isFalse();

			String sessionId = context.getFileName().toString().replace(".md", "");
			String error = invokeSessionEnd("{\"cwd\":" + jsonString(module) + ",\"session_id\":\"" + sessionId + "\"}",
				environment, module);

			requireThat(error, "sessionEndError").isEmpty();
			requireThat(Files.exists(context), "contextExists").isFalse();
		}
	}

	/**
	 * Verifies that a {@code CLAUDE_PROJECT_DIR} that is not a directory fails the hook safely.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsMissingLaunchDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));

			HookResult result = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CLAUDE_PROJECT_DIR", project.resolve("missing").toString()), project);

			requireThat(result.output(), "output").isEqualTo("{}\n");
			requireThat(result.error(), "error").contains("CLAUDE_PROJECT_DIR is not a directory");
			requireThat(Files.exists(project.resolve(".cat")), "projectCatExists").isFalse();
		}
	}

	/**
	 * Verifies Claude receives the hook's plugin root wherever a bundled rule writes the plugin-root directive, while
	 * the {@code ${CAT_PLUGIN_ROOT}} notation stays as written.
	 */
	@Test
	public void statesPluginRootInBundledRules() throws IOException
	{
		// Requirement: R-I5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			write(plugin, "rules/claude/cat-env.md", "The plugin directory is `" +
				ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE + "`. Replace `${CAT_PLUGIN_ROOT}` with it.\n");

			String context = completeContext(invoke(cwdInput(project),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project));

			requireThat(context, "context").contains("The plugin directory is `" + plugin + "`.").
				contains("`${CAT_PLUGIN_ROOT}`").doesNotContain(ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE);
		}
	}

	/**
	 * Verifies malformed input and unavailable installation directories fail without emitting context.
	 */
	@Test
	public void failsSafelyForInvalidSessionStart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			HookResult malformed = invoke("{", Map.of("CLAUDE_PLUGIN_ROOT", project.toString()), project);
			HookResult missingInstallDirectory = invoke("{}", Map.of("CAT_INSTALL_DIR", project.toString()), project);

			requireThat(malformed.output(), "malformedOutput").isEqualTo("{}\n");
			requireThat(missingInstallDirectory.output(), "missingInstallDirectoryOutput").isEqualTo("{}\n");
			requireThat(malformed.error(), "malformedError").contains("CAT SessionStart:");
			requireThat(missingInstallDirectory.error(), "missingInstallDirectoryError").
				contains("Claude CLAUDE_PLUGIN_ROOT is not set");
		}
	}

	/**
	 * Verifies blank input uses the working directory.
	 */
	@Test
	public void resolvesWorkingProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workingProject = cleanup.add(Files.createTempDirectory("cat-working-project-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			write(workingProject, ".cat/rules/common/rule.md", "working project");

			String workingContext = completeContext(invoke("", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()),
				workingProject));

			requireThat(workingContext, "workingContext").contains("working project");
		}
	}

	/**
	 * Verifies unavailable project directories and unsafe or oversized rules fail safely.
	 */
	@Test
	public void failsForInvalidProjectAndRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path outsideDirectory = cleanup.add(Files.createTempDirectory("cat-outside-rule-"));
			Path outside = outsideDirectory.resolve("outside.md");
			Path rules = Files.createDirectories(project.resolve(".cat/rules/common"));
			Files.writeString(outside, "outside rule");
			Files.createSymbolicLink(rules.resolve("unsafe.md"), outside);

			HookResult invalidProject = invoke(cwdInput(project.resolve("missing")),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);
			HookResult unsafeRule = invoke("{}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);
			Files.delete(rules.resolve("unsafe.md"));
			Files.writeString(rules.resolve("large.md"), "x".repeat(1_048_577));
			HookResult oversizedRule = invoke("{}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);

			requireThat(invalidProject.output(), "invalidProjectOutput").isEqualTo("{}\n");
			requireThat(unsafeRule.output(), "unsafeRuleOutput").isEqualTo("{}\n");
			requireThat(oversizedRule.output(), "oversizedRuleOutput").isEqualTo("{}\n");
			requireThat(invalidProject.error(), "invalidProjectError").contains("Project directory is not a directory");
			requireThat(unsafeRule.error(), "unsafeRuleError").
				contains("CAT rule must be a regular file, not a symbolic link").
				contains("Replace the symbolic link");
			requireThat(oversizedRule.error(), "oversizedRuleError").contains("CAT rule is too large");
		}
	}

	/**
	 * Verifies oversized input and command-line arguments fail safely.
	 */
	@Test
	public void failsForOversizedInputAndArguments() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			HookResult oversized = invoke("x".repeat(1_048_577), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);
			HookResult arguments = invoke(new String[]{"unexpected"}, "{}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()),
				project);

			requireThat(oversized.output(), "oversizedOutput").isEqualTo("{}\n");
			requireThat(arguments.output(), "argumentsOutput").isEqualTo("{}\n");
			requireThat(oversized.error(), "oversizedError").contains("input is too large");
			requireThat(arguments.error(), "argumentsError").contains("accepts no arguments");
		}
	}

	/**
	 * Writes a UTF-8 rule below a root directory.
	 *
	 * @param root the rule root
	 * @param relativePath the relative rule path
	 * @param content the rule content
	 * @throws IOException if the rule cannot be written
	 */
	private static void write(Path root, String relativePath, String content) throws IOException
	{
		requireThat(root, "root").isNotNull();
		requireThat(relativePath, "relativePath").isNotBlank();
		requireThat(content, "content").isNotNull();
		Path file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	/**
	 * Runs the SessionStart hook without arguments and captures its standard streams.
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
		return invoke(new String[0], input, environment, workingDirectory);
	}

	/**
	 * Runs the SessionStart hook with supplied arguments and captures its standard streams.
	 *
	 * @param args the command-line arguments
	 * @param input the hook input
	 * @param environment the process environment
	 * @param workingDirectory the process working directory
	 * @return the captured standard streams
	 */
	private static HookResult invoke(String[] args, String input, Map<String, String> environment, Path workingDirectory)
	{
		assert args != null;
		assert input != null;
		assert environment != null;
		assert workingDirectory != null;
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		Map<String, String> harnessEnvironment = new HashMap<>(environment);
		harnessEnvironment.putIfAbsent(Platform.current().homeVariable(), workingDirectory.resolve("home").toString());
		ClaudeSessionStartHook.run(args, new ByteArrayInputStream(sessionInput(input).getBytes(UTF_8)), output,
			new PrintStream(error, true, UTF_8), harnessEnvironment, workingDirectory);
		return new HookResult(output.toString(UTF_8), error.toString(UTF_8));
	}

	/**
	 * Runs the SubagentStart hook and captures its standard streams.
	 *
	 * @param input the hook input
	 * @param environment the process environment
	 * @param workingDirectory the process working directory
	 * @return the captured standard streams
	 */
	private static HookResult invokeSubagent(String input, Map<String, String> environment, Path workingDirectory)
	{
		assert input != null;
		assert environment != null;
		assert workingDirectory != null;
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		Map<String, String> harnessEnvironment = new HashMap<>(environment);
		harnessEnvironment.putIfAbsent(Platform.current().homeVariable(), workingDirectory.resolve("home").toString());
		ClaudeSubagentStartHook.run(new String[0], new ByteArrayInputStream(sessionInput(input).getBytes(UTF_8)), output,
			new PrintStream(error, true, UTF_8), harnessEnvironment, workingDirectory);
		return new HookResult(output.toString(UTF_8), error.toString(UTF_8));
	}

	/**
	 * Runs the SessionEnd hook and captures its diagnostic output.
	 *
	 * @param input the hook input
	 * @param environment the process environment
	 * @param workingDirectory the process working directory
	 * @return the diagnostic output
	 */
	private static String invokeSessionEnd(String input, Map<String, String> environment, Path workingDirectory)
	{
		assert input != null;
		assert environment != null;
		assert workingDirectory != null;
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		Map<String, String> harnessEnvironment = new HashMap<>(environment);
		harnessEnvironment.putIfAbsent(Platform.current().homeVariable(), workingDirectory.resolve("home").toString());
		ClaudeSessionEndHook.run(new String[0], new ByteArrayInputStream(input.getBytes(UTF_8)),
			new PrintStream(error, true, UTF_8), harnessEnvironment, workingDirectory);
		return error.toString(UTF_8);
	}

	/**
	 * Adds the common Claude harness session metadata to a valid test event.
	 *
	 * @param input the hook input
	 * @return the input with session metadata when it is a complete JSON object
	 */
	private static String sessionInput(String input)
	{
		assert input != null;
		String sessionId = UUID.randomUUID().toString();
		if (input.isEmpty())
			return "{\"session_id\":\"" + sessionId + "\",\"agent_id\":\"agent-test\"}";
		if (!input.startsWith("{") || !input.endsWith("}") || input.contains("session_id"))
			return input;
		String separator = ",";
		if (input.equals("{}"))
			separator = "";
		return input.substring(0, input.length() - 1) + separator + "\"session_id\":\"" + sessionId +
			"\",\"agent_id\":\"agent-test\"}";
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
		try (ToolScope scope = new ToolScope())
		{
			JsonNode envelope = scope.json().readTree(result.output());
			return envelope.at("/hookSpecificOutput/additionalContext").asString();
		}
	}

	/**
	 * Reads the complete rules from the file named by a successful hook response.
	 *
	 * @param result the hook response
	 * @return the complete rules
	 * @throws IOException if the referenced file cannot be read
	 */
	private static String completeContext(HookResult result) throws IOException
	{
		assert result != null;
		Path file = referencedFile(context(result));
		try
		{
			return Files.readString(file);
		}
		finally
		{
			Files.deleteIfExists(file);
		}
	}

	/**
	 * Extracts the file path enclosed in the instruction's first Markdown code span.
	 *
	 * @param instruction the hook instruction
	 * @return the referenced file
	 */
	private static Path referencedFile(String instruction)
	{
		assert instruction != null;
		int start = instruction.indexOf('`');
		int end = instruction.indexOf('`', start + 1);
		if (start == -1 || end == -1)
			throw new AssertionError("Expected a path in a Markdown code span: " + instruction);
		return Path.of(instruction.substring(start + 1, end));
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

	/**
	 * Returns hook input that names only the session's working directory.
	 *
	 * @param cwd the session's working directory
	 * @return the JSON input
	 */
	private static String cwdInput(Path cwd)
	{
		return "{\"cwd\":" + jsonString(cwd) + "}";
	}

	/**
	 * Returns a path as a JSON string, escaping the separators of a Windows path.
	 *
	 * @param path a path
	 * @return the JSON string, including its quotes
	 */
	private static String jsonString(Path path)
	{
		return "\"" + path.toString().replace("\\", "\\\\") + "\"";
	}
}
