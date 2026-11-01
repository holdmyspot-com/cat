package com.holdmyspot.cat.codex;

import static com.holdmyspot.cat.codex.CodexPluginInfo.HARNESS_ID;

import com.holdmyspot.cat.common.HookScope;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.RelativeRuleReference;
import com.holdmyspot.cat.common.SessionRules;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.Waiting;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
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
	 * Tells a subagent to read its own rules file whole, given the rules file of the agent that started it and then its
	 * own. A subagent that receives the conversation of the agent that started it also receives that agent's pointer to
	 * its rules file, whose rules can differ, such as in the directory that {@code CAT_TEMP} names; Codex places this
	 * pointer after the copied one. The pointer names the other file, since the subagent cannot see whether its
	 * conversation was copied.
	 */
	private static final String SUBAGENT_RULES_FILE_POINTER = "`%s` belongs to the agent that started you: do not " +
		"read it. " + RULES_FILE_POINTER;

	/**
	 * Prevents instantiation.
	 */
	private CodexRuleInjectionHook()
	{
	}

	/**
	 * Loads rules for one Codex hook event and emits its harness JSON response.
	 * <p>
	 * The response holds only the guidance on reading a file whole and a pointer to the rules file of the agent's CAT
	 * session directory, which holds the agent's other rules; a plugin without that guidance sends every rule in the
	 * response, because the agent would have no instruction on how to read the file whole. SessionStart first moves the
	 * CAT session directories that earlier versions of CAT wrote to their current location, and then removes the CAT
	 * session directories of sessions that their harness no longer keeps. Its response's {@code systemMessage}, which
	 * Codex shows the user as a warning rather than giving it to the agent, warns about each project or user rule
	 * reference that names a file relative to the rule's own directory and tells the user how to fix it, and the
	 * session's {@link SessionDirectory#relativeRuleReferencesFile()} lists every such reference. When the SessionStart
	 * input's {@code source} is {@code compact}, the context ends with a section that lists, for each user message that
	 * the compaction kept, when it was sent and whether a later turn answered it, as the session log that the input's
	 * {@code transcript_path} names records it; a missing or unreadable log omits the section.
	 * <p>
	 * A Codex subagent is a thread whose shells receive its own thread ID, the input's {@code agent_id}, so its rules
	 * file lies in the CAT session directory of that ID rather than its parent's, and its pointer tells it not to read
	 * the parent's rules file, which the parent's copied messages may name. An input without {@code agent_id} leaves the
	 * subagent sharing its parent's CAT session directory, so the subagent receives every rule in the response, leaving
	 * the parent's rules file unchanged.
	 *
	 * @param eventName the Codex hook event name
	 * @param args the hook command-line arguments
	 * @param input the harness hook input
	 * @param output the harness hook output
	 * @param error diagnostics for the host
	 * @param environment the hook environment
	 * @param workingDirectory the hook process directory
	 * @param waiting how the hook waits between the attempts of a retried operation
	 */
	static void run(String eventName, String[] args, InputStream input, OutputStream output, PrintStream error,
		Map<String, String> environment, Path workingDirectory, Waiting waiting)
	{
		try (HookScope scope = CodexHookScope.open(environment, waiting))
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
				String warning = "";
				if (!eventName.equals("SubagentStart"))
				{
					// Moving before opening its own directory lets a resumed session keep the directory that it wrote
					SessionDirectory.moveFromFormerRoot(projectDir, error);
					SessionDirectory directory = scope.sessionDirectory(projectDir, event, "session_id");
					directory.sweep(Instant.now(), error);
					SessionRules session = loader.loadSessionForMainAgent(pluginRoot, userRulesRoot, projectDir,
						HARNESS_ID);
					context = context(directory, session, RULES_FILE_POINTER::formatted) +
						keptMessagesSection(json, event, error);
					warning = RelativeRuleReference.sessionWarning(userRulesRoot, projectDir,
						directory.relativeRuleReferencesFile(), error);
				}
				else if (event.path("agent_id").isString())
				{
					SessionDirectory directory = scope.sessionDirectory(projectDir, event, "agent_id");
					Path parentRulesFile = directory.rulesFileOf(event.path("session_id").asString());
					SessionRules session = loader.loadSessionForSubagent(pluginRoot, userRulesRoot, projectDir,
						HARNESS_ID, scope.subagentType(event));
					context = context(directory, session,
						rulesFile -> SUBAGENT_RULES_FILE_POINTER.formatted(parentRulesFile, rulesFile));
				}
				else
				{
					SessionDirectory directory = scope.sessionDirectory(projectDir, event, "session_id");
					context = directory.insertSessionId(loader.loadForSubagent(pluginRoot, userRulesRoot, projectDir,
						HARNESS_ID, scope.subagentType(event)));
				}
				Map<String, Object> response = new LinkedHashMap<>();
				response.put("hookSpecificOutput", Map.of("hookEventName", eventName, "additionalContext", context));
				if (!warning.isEmpty())
					response.put("systemMessage", warning);
				output.write(json.writeValueAsBytes(response));
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
	 * Writes an agent's rules, other than the guidance on reading a file whole, to the rules file of its CAT session
	 * directory and returns that guidance followed by a pointer to the file. Without that guidance, writes no file and
	 * returns every rule, because the agent would have no instruction on how to read the file whole.
	 *
	 * @param directory the agent's CAT session directory
	 * @param session the agent's rules
	 * @param pointer returns the instruction to read a rules file whole, given the file
	 * @return the agent's additional context
	 * @throws IOException if the rules file cannot be written
	 */
	private static String context(SessionDirectory directory, SessionRules session, Function<Path, String> pointer)
		throws IOException
	{
		if (session.readingGuidance().isEmpty())
			return directory.insertSessionId(session.rules());
		Path rulesFile = directory.rulesFile();
		Files.writeString(rulesFile, directory.insertSessionId(session.rules()), StandardCharsets.UTF_8);
		return directory.insertSessionId(session.readingGuidance()) + "\n\n# CAT Rules\n\n" + pointer.apply(rulesFile);
	}

	/**
	 * Returns the context section that lists the outcomes of the user messages that a compaction kept, when Codex
	 * starts the session again after compacting it.
	 *
	 * @param json the invocation-owned JSON mapper
	 * @param event the SessionStart input
	 * @param error diagnostics for the host
	 * @return the section after a blank line, or an empty string if the input's {@code source} is not
	 *   {@code compact}, the input names no session log, or the log cannot be read
	 */
	private static String keptMessagesSection(JsonMapper json, JsonNode event, PrintStream error)
	{
		// WORKAROUND: https://github.com/openai/codex/issues/42611
		if (!event.path("source").asString("").equals("compact"))
			return "";
		String transcript = event.path("transcript_path").asString("");
		if (transcript.isBlank())
			return "";
		try
		{
			String section = RolloutRequestStatus.read(json, Path.of(transcript)).keptSection();
			if (section.isEmpty())
				return "";
			return "\n\n" + section;
		}
		catch (IOException | InvalidPathException e)
		{
			// The kept messages' outcomes are an aid; the rules still reach the agent without them
			error.println("CAT SessionStart: Unable to read the session log " + transcript + ": " + e.getMessage());
			return "";
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
