package io.github.cowwoc.cat.claude;

import static io.github.cowwoc.cat.claude.ClaudePluginInfo.HARNESS_ID;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.HookScope;
import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.SessionDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

		Read the whole file `%s` with the Read tool before planning, using a project tool, or responding, then apply every
		instruction in it for the rest of this conversation. Do not search it, list its headings, or read only part of it:
		guidance that you skip still applies.
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
			Path projectDir = ClaudeHookScope.projectDir(scope, event, environment, workingDirectory);
			// SessionStart moves the CAT session directories of earlier versions before it opens its own, so a resumed
			// session keeps the directory that it wrote
			if (!eventName.equals("SubagentStart"))
				SessionDirectory.moveFromFormerRoot(projectDir, error);
			// A Claude Code subagent's input carries its parent's session_id, and its shells the parent's
			// CLAUDE_CODE_SESSION_ID, so the subagent shares its parent's CAT session directory
			SessionDirectory session = scope.sessionDirectory(projectDir, event, "session_id");
			Path rulesFile;
			if (eventName.equals("SubagentStart"))
				rulesFile = session.subagentRulesFile(agentId(event));
			else
			{
				session.sweep(Instant.now(), error);
				rulesFile = session.rulesFile();
			}
			Path pluginRoot = scope.pluginRoot();
			Path userRulesRoot = scope.userRulesRoot();
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
			String rules;
			if (eventName.equals("SubagentStart"))
				rules = loader.loadForSubagent(pluginRoot, userRulesRoot, projectDir, HARNESS_ID, scope.subagentType(event));
			else
				rules = loader.loadForMainAgent(pluginRoot, userRulesRoot, projectDir, HARNESS_ID);
			Files.createDirectories(rulesFile.getParent());
			Files.writeString(rulesFile, session.insertSessionId(rules), UTF_8);
			String context = CONTEXT_FILE_INSTRUCTION.formatted(eventName, rulesFile);
			output.write(json.writeValueAsBytes(Map.of("hookSpecificOutput", Map.of("hookEventName", eventName,
				"additionalContext", context))));
			output.write('\n');
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

	/**
	 * Returns the harness's ID of the subagent that a SubagentStart input names.
	 *
	 * @param event the SubagentStart input
	 * @return the subagent's ID
	 * @throws IOException if the input lacks {@code agent_id}
	 */
	private static String agentId(JsonNode event) throws IOException
	{
		JsonNode value = event.path("agent_id");
		if (!value.isString())
			throw new IOException("The SubagentStart input lacks agent_id");
		return value.stringValue();
	}
}
