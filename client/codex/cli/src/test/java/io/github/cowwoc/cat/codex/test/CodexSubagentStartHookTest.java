package io.github.cowwoc.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.codex.CodexSubagentStartHook;
import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.SessionDirectory;
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
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Verifies Codex SubagentStart rule injection.
 */
public final class CodexSubagentStartHookTest
{
	private static final String PARENT_ID = "01a147d8-bf56-7283-92b4-12dcaeb53738";
	private static final String AGENT_ID = "01a147d9-171e-7123-a7e9-d7fd43687c9a";

	/**
	 * Verifies the harness hook selects only rules addressed to the spawned Codex agent type.
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

			String reviewer = context(invoke(event(project, "cat:reviewer"), Map.of("PLUGIN_ROOT", plugin.toString()),
				Path.of(".")));
			String worker = context(invoke(event(project, "cat:worker"), Map.of("PLUGIN_ROOT", plugin.toString()),
				Path.of(".")));

			requireThat(reviewer, "reviewer").contains("subagent guidance").contains("reviewer guidance").
				doesNotContain("main guidance");
			requireThat(worker, "worker").contains("subagent guidance").doesNotContain("reviewer guidance").
				doesNotContain("main guidance");
		}
	}

	/**
	 * Verifies a subagent's rules state as {@code CAT_TEMP} the CAT session directory that its {@code agent_id} names,
	 * which is the thread ID that its own shells receive, and that the hook creates that directory and its marker.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void namesSubagentSessionDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-subagent-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/codex")).resolve("cat-env.md"),
				"`CAT_TEMP` is `${CAT_PROJECT_ROOT}/.cat/work/" + SessionDirectory.SESSION_ID_DIRECTIVE + "/temp`.\n");

			HookResult result = invoke(event(project, "cat:worker"), Map.of("PLUGIN_ROOT", plugin.toString()),
				Path.of("."));

			requireThat(result.error(), "error").isEmpty();
			requireThat(context(result), "context").contains("`CAT_TEMP` is `${CAT_PROJECT_ROOT}/.cat/work/" + AGENT_ID +
				"/temp`.");
			requireThat(project.resolve(".cat/work").resolve(AGENT_ID).resolve("temp"), "agentTemp").isDirectory();
			requireThat(project.resolve(".cat/work").resolve(AGENT_ID).resolve("session.json"), "agentMarker").
				isRegularFile();
			requireThat(Files.exists(project.resolve(".cat/work").resolve(PARENT_ID)), "parentDirectoryExists").isFalse();
		}
	}

	/**
	 * Verifies that a subagent receives the guidance on reading a file whole followed by a pointer to a rules file in the
	 * CAT session directory of its {@code agent_id}, which tells it not to read the rules file of the agent that started
	 * it, since that file states the other agent's {@code CAT_TEMP}.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void deliversRulesThroughSubagentFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-subagent-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path codexRules = Files.createDirectories(plugin.resolve("rules/codex"));
			Files.writeString(codexRules.resolve("read-file.md"), "# Reading Command Output\n\nRun `" +
				ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE + "/bin/run-part`.\n");
			Files.writeString(codexRules.resolve("cat-env.md"), "# CAT Environment\n\n`CAT_TEMP` is " +
				"`${CAT_PROJECT_ROOT}/.cat/work/" + SessionDirectory.SESSION_ID_DIRECTIVE + "/temp`.\n");
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("guidance.md"),
				"---\nagents: [\"subagents\"]\n---\nsubagent guidance");

			HookResult result = invoke(event(project, "cat:worker"), Map.of("PLUGIN_ROOT", plugin.toString()),
				Path.of("."));

			Path rulesFile = project.resolve(".cat/work").resolve(AGENT_ID).resolve("rules.md");
			Path parentRulesFile = project.resolve(".cat/work").resolve(PARENT_ID).resolve("rules.md");
			requireThat(context(result), "context").
				startsWith("# Reading Command Output\n\nRun `" + plugin.resolve("bin/run-part") + "`.\n\n# CAT Rules\n\n").
				contains("`" + parentRulesFile + "` belongs to the agent that started you: do not read it. CAT's rules " +
					"for this session are in `" + rulesFile + "`. Read that whole file, as described above, before you plan").
				doesNotContain("subagent guidance").doesNotContain("CAT Environment");
			requireThat(Files.readString(rulesFile, UTF_8), "rules").contains("subagent guidance").
				contains("`CAT_TEMP` is `${CAT_PROJECT_ROOT}/.cat/work/" + AGENT_ID + "/temp`.").
				doesNotContain("# Reading Command Output");
			requireThat(Files.exists(project.resolve(".cat/work").resolve(PARENT_ID)), "parentDirectoryExists").isFalse();
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies that an input without {@code agent_id} gives the subagent every rule in the response and leaves the rules
	 * file of the session's main agent unchanged, since the subagent then shares that agent's CAT session directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void inlinesRulesWithoutAgentId() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-subagent-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/codex")).resolve("read-file.md"),
				"# Reading Command Output\n");
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("guidance.md"),
				"---\nagents: [\"subagents\"]\n---\nsubagent guidance");
			Path parentRulesFile = Files.createDirectories(project.resolve(".cat/work").resolve(PARENT_ID)).
				resolve("rules.md");
			Files.writeString(parentRulesFile, "main agent rules");
			String input = "{\"session_id\":\"" + PARENT_ID + "\",\"cwd\":" + jsonString(project) +
				",\"agent_type\":\"cat:worker\"}";

			HookResult result = invoke(input, Map.of("PLUGIN_ROOT", plugin.toString()), Path.of("."));

			requireThat(context(result), "context").contains("# Reading Command Output").contains("subagent guidance");
			requireThat(Files.readString(parentRulesFile, UTF_8), "parentRules").isEqualTo("main agent rules");
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Returns a SubagentStart input of a subagent in a project directory.
	 *
	 * @param cwd the subagent's working directory
	 * @param agentType the subagent's type
	 * @return the input's JSON
	 */
	private static String event(Path cwd, String agentType)
	{
		return "{\"session_id\":\"" + PARENT_ID + "\",\"agent_id\":\"" + AGENT_ID + "\",\"cwd\":" + jsonString(cwd) +
			",\"agent_type\":\"" + agentType + "\"}";
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
		Map<String, String> harnessEnvironment = new HashMap<>(environment);
		harnessEnvironment.putIfAbsent(Platform.current().homeVariable(), workingDirectory.resolve("home").toString());
		CodexSubagentStartHook.run(new String[0], new ByteArrayInputStream(input.getBytes(UTF_8)), output,
			new PrintStream(error, true, UTF_8), harnessEnvironment, workingDirectory);
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
