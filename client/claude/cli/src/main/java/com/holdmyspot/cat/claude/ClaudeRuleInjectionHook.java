package com.holdmyspot.cat.claude;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.optimizer.ClaudeRuleVariables;
import com.holdmyspot.cat.common.HomeProject;
import com.holdmyspot.cat.common.HookScope;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.RelativeRuleReference;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.SessionRules;
import com.holdmyspot.cat.common.Waiting;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	 * <p>
	 * At SessionStart, the response's {@code systemMessage}, which Claude Code shows the user rather than the agent,
	 * warns about each project or user rule reference that names a file relative to the rule's own directory and tells
	 * the user how to fix it, and the session's {@link SessionDirectory#relativeRuleReferencesFile()} lists every such
	 * reference. When the event created the session's CAT session directory, the {@code systemMessage} also tells the
	 * user that CAT added the CAT session directories to the project's {@code .gitignore}, or could not and why.
	 * <p>
	 * A session whose project directory is the user's home folder or contains it, which {@link HomeProject} refuses as
	 * a CAT project, gets no rules and no CAT session directory. Because the hook cannot stop the session, it reports
	 * the refusal on {@code error} as it reports every other failure, shows the user the reason through the response's
	 * {@code systemMessage}, and tells the agent through its {@code additionalContext} that CAT is not active in this
	 * session, why, and that the user should open a folder below the home folder.
	 *
	 * @param eventName the Claude hook event name
	 * @param args the hook command-line arguments
	 * @param input the harness hook input
	 * @param output the harness hook output
	 * @param error diagnostics for the host
	 * @param environment the hook environment
	 * @param workingDirectory the hook process directory
	 * @param waiting how the hook waits between the attempts of a retried operation
	 * @param git answers what Git ignores in the project when the event creates a CAT session directory
	 */
	static void run(String eventName, String[] args, InputStream input, OutputStream output, PrintStream error,
		Map<String, String> environment, Path workingDirectory, Waiting waiting, SessionIgnore.GitQueries git)
	{
		try (HookScope scope = ClaudeHookScope.open(environment, waiting, git))
		{
			JsonMapper json = scope.json();
			if (args.length != 0)
				throw new IllegalArgumentException(eventName + " accepts no arguments");
			JsonNode event = scope.readEvent(eventName, input);
			Path projectDir = ClaudeHookScope.projectDir(scope, event, environment, workingDirectory);
			Optional<String> homeRefusal = HomeProject.refusal(projectDir,
				Platform.current().homeDirectory(environment));
			if (homeRefusal.isPresent())
			{
				error.println("CAT " + eventName + ": " + homeRefusal.get());
				output.write(json.writeValueAsBytes(refusedProjectResponse(eventName, homeRefusal.get())));
				output.write('\n');
				return;
			}
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
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml(), Platform.current(),
				ClaudeRuleVariables.of(pluginRoot));
			String rules;
			if (eventName.equals("SubagentStart"))
			{
				rules = loader.loadForSubagent(pluginRoot, userRulesRoot, projectDir, HARNESS_ID, scope.subagentType(event));
				rules = SessionRules.withTimezone(rules, ZoneId.systemDefault());
			}
			else
				rules = loader.loadForMainAgent(pluginRoot, userRulesRoot, projectDir, HARNESS_ID);
			Files.createDirectories(rulesFile.getParent());
			Files.writeString(rulesFile, session.insertSessionId(rules), UTF_8);
			String context = CONTEXT_FILE_INSTRUCTION.formatted(eventName, rulesFile);
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("hookSpecificOutput", Map.of("hookEventName", eventName, "additionalContext", context));
			List<String> notices = new ArrayList<>();
			session.ignoreNotice().ifPresent(notice -> notices.add(notice.forUser()));
			if (!eventName.equals("SubagentStart"))
			{
				String warning = RelativeRuleReference.sessionWarning(userRulesRoot, projectDir,
					session.relativeRuleReferencesFile(), error);
				if (!warning.isEmpty())
					notices.add(warning);
			}
			if (!notices.isEmpty())
				response.put("systemMessage", String.join("\n\n", notices));
			output.write(json.writeValueAsBytes(response));
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
	 * Returns the response to an event whose project folder cannot be a CAT project.
	 *
	 * @param eventName the Claude hook event name
	 * @param refusal   why the project folder cannot be a CAT project, as {@link HomeProject} states it to the user
	 * @return a response that shows the user {@code refusal} and tells the agent that CAT is not active in this session
	 */
	private static Map<String, Object> refusedProjectResponse(String eventName, String refusal)
	{
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("hookSpecificOutput", Map.of("hookEventName", eventName, "additionalContext",
			"CAT is not active in this session, so no CAT rules apply. CAT showed the user why: " + refusal +
				" The user should open a folder below the home folder to use CAT."));
		response.put("systemMessage", "CAT is not active in this session. " + refusal);
		return response;
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
