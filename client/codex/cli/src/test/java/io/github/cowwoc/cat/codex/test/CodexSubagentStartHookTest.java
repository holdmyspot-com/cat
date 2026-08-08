package io.github.cowwoc.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.codex.CodexSubagentStartHook;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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

			String reviewer = context(invoke("{\"cwd\":\"" + project +
				"\",\"agent_type\":\"cat:reviewer\"}", Map.of("PLUGIN_ROOT", plugin.toString()), Path.of(".")));
			String worker = context(invoke("{\"cwd\":\"" + project +
				"\",\"agent_type\":\"cat:worker\"}", Map.of("PLUGIN_ROOT", plugin.toString()), Path.of(".")));

			requireThat(reviewer, "reviewer").contains("subagent guidance").contains("reviewer guidance").
				doesNotContain("main guidance");
			requireThat(worker, "worker").contains("subagent guidance").doesNotContain("reviewer guidance").
				doesNotContain("main guidance");
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
		CodexSubagentStartHook.run(new String[0], new ByteArrayInputStream(input.getBytes(UTF_8)), output,
			new PrintStream(error, true, UTF_8), environment, workingDirectory);
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
