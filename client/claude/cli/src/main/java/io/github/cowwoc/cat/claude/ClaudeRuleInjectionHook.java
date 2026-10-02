package io.github.cowwoc.cat.claude;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.HookScope;
import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Implements the shared JSON boundary for Claude rule-injection hooks.
 */
final class ClaudeRuleInjectionHook
{
	private static final String CONTEXT_FILE_INSTRUCTION = """
		# CAT %s Context

		First read and apply the instructions in `%s` before planning, using a project tool, or responding. They govern the
		rest of this conversation.
		""";

	/**
	 * Prevents instantiation.
	 */
	private ClaudeRuleInjectionHook()
	{
	}

	/**
	 * Loads rules for one Claude hook event and emits its harness JSON response.
	 *
	 * @param eventName the Claude hook event name
	 * @param args the hook command-line arguments
	 * @param input the harness hook input
	 * @param output the harness hook output
	 * @param error diagnostics for the host
	 * @param environment the hook environment
	 * @param workingDirectory the hook process directory
	 */
	static void run(String eventName, String[] args, InputStream input, OutputStream output, PrintStream error,
		Map<String, String> environment, Path workingDirectory)
	{
		try (HookScope scope = ClaudeHookScope.open(environment))
		{
			JsonMapper json = scope.json();
			if (args.length != 0)
				throw new IllegalArgumentException(eventName + " accepts no arguments");
			JsonNode event = scope.readEvent(eventName, input);
			Path projectDir = scope.projectDir(event, workingDirectory);
			Path pluginRoot = scope.pluginRoot();
			Path userRulesRoot = scope.userRulesRoot();
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
			String rules = loader.loadForMainAgent(pluginRoot, userRulesRoot, projectDir, "claude");
			if (eventName.equals("SubagentStart"))
				rules = loader.loadForSubagent(pluginRoot, userRulesRoot, projectDir, "claude", scope.subagentType(event));
			try (ToolScope toolScope = ToolScope.forWorkingDirectory(projectDir))
			{
				Path contextFile = ClaudeSessionContext.write(toolScope.catTemp(), event, eventName, rules);
				String context = CONTEXT_FILE_INSTRUCTION.formatted(eventName, contextFile);
				output.write(json.writeValueAsBytes(Map.of("hookSpecificOutput", Map.of("hookEventName", eventName,
					"additionalContext", context))));
				output.write('\n');
			}
		}
		catch (IOException | IllegalArgumentException | JacksonException e)
		{
			error.println("CAT " + eventName + ": " + e.getMessage());
			try
			{
				output.write("{}\n".getBytes(UTF_8));
			}
			catch (IOException _)
			{
				// The host owns the output stream and no recovery is possible.
			}
		}
	}
}
