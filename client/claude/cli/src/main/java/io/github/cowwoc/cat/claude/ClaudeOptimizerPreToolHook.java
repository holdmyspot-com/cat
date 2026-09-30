package io.github.cowwoc.cat.claude;

import io.github.cowwoc.cat.common.OptimizerToolCommand;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Binds a recognized Claude optimizer inspection command to its exact native tool identity.
 */
public final class ClaudeOptimizerPreToolHook
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Processes one Claude pre-tool event and reports failures to stderr.
	 *
	 * @param args no hook arguments
	 */
	public static void main(String[] args)
	{
		try
		{
			if (args.length != 0)
				throw new IllegalArgumentException("Optimizer pre-tool hook takes no arguments");
			run(System.in, System.out);
		}
		catch (IOException | IllegalArgumentException e)
		{
			System.err.println("CAT optimizer pre-tool hook: " + e.getMessage());
			System.exit(2);
		}
	}

	/**
	 * Rewrites a recognized command after resolving its model from the current transcript.
	 *
	 * @param input Claude hook event stream
	 * @param output optional hook response stream
	 * @throws IOException if the event is malformed or output cannot be written
	 */
	public static void run(InputStream input, OutputStream output) throws IOException
	{
		run(input, output, (transcript, session, toolUse) -> HostToolModel.awaitModel(transcript, session,
			toolUse, Clock.systemUTC(), Thread::sleep));
	}

	/**
	 * Processes a hook event with an injected transcript lookup.
	 *
	 * @param input Claude hook event stream
	 * @param output optional hook response stream
	 * @param lookup native transcript model lookup
	 * @throws IOException if the event is malformed or output cannot be written
	 */
	static void run(InputStream input, OutputStream output, HostToolModel.Lookup lookup) throws IOException
	{
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(output, "output");
		Objects.requireNonNull(lookup, "lookup");
		JsonNode event;
		try
		{
			event = JSON.readTree(input);
		}
		catch (JacksonException e)
		{
			throw new IOException("Optimizer pre-tool event is malformed", e);
		}
		if (event == null || !event.isObject())
			throw new IOException("Optimizer pre-tool hook requires an object event");
		if (!"PreToolUse".equals(text(event, "hook_event_name")) || !"Bash".equals(text(event, "tool_name")))
			return;
		JsonNode toolInput = event.path("tool_input");
		if (!toolInput.isObject())
			throw new IOException("Optimizer pre-tool event requires object tool_input");
		JsonNode commandNode = toolInput.path("command");
		if (!commandNode.isString())
			throw new IOException("Optimizer pre-tool event requires textual tool_input.command");
		OptimizerToolCommand inspection = OptimizerToolCommand.findInspectionCall(commandNode.asString()).orElse(null);
		if (inspection == null)
			return;
		ObjectNode updated;
		try
		{
			String session = requiredText(event, "session_id");
			String turn = requiredText(event, "prompt_id");
			String toolUse = requiredText(event, "tool_use_id");
			String transcript = requiredText(event, "transcript_path");
			String model = lookup.read(Path.of(transcript), session, toolUse);
			if (model == null || model.isBlank())
				throw new IOException("Transcript lookup returned no model for tool use " + toolUse);
			updated = (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(toolInput));
			updated.put("command", inspection.bindHostIdentity(session, turn, toolUse, model));
		}
		catch (IOException | IllegalArgumentException e)
		{
			output.write(JSON.writeValueAsBytes(Map.of("hookSpecificOutput", Map.of(
				"hookEventName", "PreToolUse", "permissionDecision", "deny",
				"permissionDecisionReason", "CAT could not bind this inspection command to its Claude session and " +
					"transcript: " +
					e.getMessage()))));
			return;
		}
		output.write(JSON.writeValueAsBytes(Map.of("hookSpecificOutput", Map.of(
			"hookEventName", "PreToolUse", "updatedInput", updated))));
	}

	/**
	 * Requires nonblank text from the event without coercion.
	 *
	 * @param event native hook event
	 * @param field required field name
	 * @return exact event value
	 * @throws IOException if the value is absent or invalid
	 */
	private static String requiredText(JsonNode event, String field) throws IOException
	{
		String value = text(event, field);
		if (value == null || value.isBlank())
			throw new IOException("Optimizer pre-tool event is missing nonblank " + field);
		return value;
	}

	/**
	 * Reads a textual field without coercing other JSON types.
	 *
	 * @param node JSON object
	 * @param field field name
	 * @return exact text, or null for another JSON type
	 */
	private static String text(JsonNode node, String field)
	{
		JsonNode value = node.path(field);
		if (!value.isString())
			return null;
		return value.asString();
	}

	/**
	 * Prevents construction.
	 */
	private ClaudeOptimizerPreToolHook()
	{
	}
}
