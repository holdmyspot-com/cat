package io.github.cowwoc.cat.codex;

import io.github.cowwoc.cat.common.ProjectRuleLoader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Implements the shared JSON boundary for Codex rule-injection hooks.
 */
final class CodexRuleInjectionHook
{
	private static final int MAXIMUM_INPUT_BYTES = 1_048_576;

	/**
	 * Prevents instantiation.
	 */
	private CodexRuleInjectionHook()
	{
	}

	/**
	 * Loads rules for one Codex hook event and emits its native JSON response.
	 *
	 * @param eventName the Codex hook event name
	 * @param args the hook command-line arguments
	 * @param input the native hook input
	 * @param output the native hook output
	 * @param error diagnostics for the host
	 * @param environment the hook environment
	 * @param workingDirectory the hook process directory
	 */

	static void run(String eventName, String[] args, InputStream input, OutputStream output, PrintStream error,
	Map<String, String> environment, Path workingDirectory)
{
	try (CodexHookScope scope = new CodexHookScope(environment))
	{
		try
		{
			JsonMapper json = scope.json();
			if (args.length != 0)
				throw new IllegalArgumentException(eventName + " accepts no arguments");
			byte[] bytes = input.readNBytes(MAXIMUM_INPUT_BYTES + 1);
			if (bytes.length > MAXIMUM_INPUT_BYTES)
				throw new IOException(eventName + " input is too large");
			JsonNode event = json.createObjectNode();
			if (bytes.length != 0)
				event = json.readTree(bytes);
			if (event == null || !event.isObject())
				throw new IOException(eventName + " input must be a JSON object");
			Path projectDir = scope.projectDir(event, workingDirectory);
			Path pluginRoot = scope.pluginRoot();
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
			String context;
			if (eventName.equals("SubagentStart"))
				context = loader.loadForSubagent(pluginRoot, projectDir, "codex", subagentType(event));
			else
				context = loader.loadForMainAgent(pluginRoot, projectDir, "codex");
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
	 * Reports a hook failure and writes the native failure response through the invocation-owned mapper.
	 *
	 * @param json the invocation-owned JSON mapper
	 * @param eventName the Codex hook event name
	 * @param output the native hook output
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
	 * @param output the native hook output
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

	/**
	 * Returns the host-provided subagent type without making malformed optional metadata a hook crash.
	 *
	 * @param event the native hook event
	 * @return the subagent type, or blank when it is absent or not textual
	 */
	private static String subagentType(JsonNode event)
	{
		assert event != null;
		JsonNode agentType = event.path("agent_type");
		if (!agentType.isString())
			return "";
		return agentType.stringValue();
	}
}
