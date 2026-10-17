package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.cat.codex.CodexPluginInfo.HARNESS_ID;

import io.github.cowwoc.cat.common.HookScope;
import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.SessionRules;
import io.github.cowwoc.cat.common.SessionWorkDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Implements the shared JSON boundary for Codex rule-injection hooks.
 */
final class CodexRuleInjectionHook
{
	/**
	 * Tells the primary agent to read the session's rules file whole, as the reading guidance before it describes.
	 */
	private static final String RULES_FILE_POINTER = "CAT's rules for this session are in `%s`. Read that whole " +
		"file, as described above, before you plan, run another command, or respond, even when you could answer the " +
		"request without it, then apply every instruction in it for the rest of this conversation. Do not search it, " +
		"list its headings, or read only part of it: guidance that you skip still applies.";

	/**
	 * Precedes a subagent's rules. A subagent that receives the conversation of the agent that started it also receives
	 * that agent's pointer to its rules file, whose rules can differ, such as in the directory that {@code CAT_TEMP}
	 * names.
	 */
	private static final String SUBAGENT_PREFACE = "The CAT rules below are yours. Earlier messages in this " +
		"conversation may come from the agent that started you and name a file of CAT rules for that agent; do not " +
		"read that file, because its rules, such as the directory that `CAT_TEMP` names, differ from yours.\n\n";

	/**
	 * Prevents instantiation.
	 */
	private CodexRuleInjectionHook()
	{
	}

	/**
	 * Loads rules for one Codex hook event and emits its harness JSON response.
	 * <p>
	 * A subagent receives its rules in the response. For SessionStart, the response holds only the guidance on reading
	 * a file whole and a pointer to the rules file of the session's work directory, which holds the session's other
	 * rules; a plugin without that guidance sends every rule in the response, because the agent would have no
	 * instruction on how to read the file whole. SessionStart then removes the work directories of sessions that their
	 * harness no longer keeps.
	 * <p>
	 * A Codex subagent is a thread whose shells receive its own thread ID, the input's {@code agent_id}, so its rules
	 * name the work directory of that ID rather than its parent's, and begin by telling it not to read a rules file that
	 * its parent's messages name.
	 *
	 * @param eventName the Codex hook event name
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
		try (HookScope scope = CodexHookScope.open(environment))
		{
			try
			{
				JsonMapper json = scope.json();
				if (args.length != 0)
					throw new IllegalArgumentException(eventName + " accepts no arguments");
				JsonNode event = scope.readEvent(eventName, input);
				Path projectDir = scope.projectDir(event, workingDirectory);
				Path pluginRoot = scope.pluginRoot();
				Path userRulesRoot = scope.userRulesRoot();
				ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
				String context;
				if (eventName.equals("SubagentStart"))
				{
					String idProperty = "agent_id";
					if (!event.path(idProperty).isString())
						idProperty = "session_id";
					SessionWorkDirectory directory = scope.sessionWorkDirectory(projectDir, event, idProperty);
					context = SUBAGENT_PREFACE + directory.insertSessionId(loader.loadForSubagent(pluginRoot, userRulesRoot,
						projectDir, HARNESS_ID, scope.subagentType(event)));
				}
				else
				{
					SessionWorkDirectory directory = scope.sessionWorkDirectory(projectDir, event, "session_id");
					directory.sweep(Instant.now(), error);
					SessionRules session = loader.loadSessionForMainAgent(pluginRoot, userRulesRoot, projectDir, HARNESS_ID);
					if (session.readingGuidance().isEmpty())
						context = directory.insertSessionId(session.rules());
					else
					{
						Path rulesFile = directory.rulesFile();
						Files.writeString(rulesFile, directory.insertSessionId(session.rules()), StandardCharsets.UTF_8);
						context = directory.insertSessionId(session.readingGuidance()) + "\n\n# CAT Rules\n\n" +
							RULES_FILE_POINTER.formatted(rulesFile);
					}
				}
				output.write(json.writeValueAsBytes(Map.of("hookSpecificOutput", Map.of("hookEventName", eventName,
					"additionalContext", context))));
				output.write('\n');
			}
			catch (IOException | IllegalArgumentException | JacksonException e)
			{
				writeFailure(scope.json(), eventName, output, error, e);
			}
		}
		catch (IOException e)
		{
			writeScopeFailure(eventName, output, error, e);
		}
	}

	/**
	 * Reports a hook failure and writes the harness failure response through the invocation-owned mapper.
	 *
	 * @param json the invocation-owned JSON mapper
	 * @param eventName the Codex hook event name
	 * @param output the harness hook output
	 * @param error diagnostics for the host
	 * @param failure the hook failure
	 */
	private static void writeFailure(JsonMapper json, String eventName, OutputStream output, PrintStream error,
		Exception failure)
	{
		error.println("CAT " + eventName + ": " + failure.getMessage());
		try
		{
			output.write(json.writeValueAsBytes(Map.of("continue", false, "stopReason",
				"CAT could not load its " + eventName + " rules.", "systemMessage", "CAT " + eventName +
					" failed: " + failure.getMessage())));
			output.write('\n');
		}
		catch (IOException responseFailure)
		{
			error.println("CAT " + eventName + ": Unable to return the failure response: " +
				responseFailure.getMessage());
		}
	}

	/**
	 * Reports a failure that prevented hook-scope construction and writes Codex's mandatory stop response.
	 *
	 * @param eventName the Codex hook event name
	 * @param output the harness hook output
	 * @param error diagnostics for the host
	 * @param failure the scope-construction failure
	 */
	private static void writeScopeFailure(String eventName, OutputStream output, PrintStream error, IOException failure)
	{
		error.println("CAT " + eventName + ": " + failure.getMessage());
		try
		{
			String response = "{\"continue\":false,\"stopReason\":\"CAT could not load its rules.\"," +
				"\"systemMessage\":\"CAT hook scope failed.\"}\n";
			output.write(response.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException responseFailure)
		{
			error.println("CAT " + eventName + ": Unable to return the failure response: " +
				responseFailure.getMessage());
		}
	}
}
