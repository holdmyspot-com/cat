package io.github.cowwoc.cat.codex;

import io.github.cowwoc.cat.common.OptimizerToolCommand;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Binds the optimizer inspection launcher to the active Codex tool call.
 */
public final class CodexOptimizerPreToolHook
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Handles one Codex pre-tool hook event.
	 *
	 * @param args empty hook arguments
	 */
	public static void main(String[] args)
	{
		try
		{
			if (args.length != 0)
				throw new IllegalArgumentException("Optimizer pre-tool hook takes no arguments");
			run(System.in, System.out);
		}
		catch (IOException | IllegalArgumentException failure)
		{
			System.err.println("CAT optimizer pre-tool hook: " + failure.getMessage());
			System.exit(2);
		}
	}

	/**
	 * Handles one pending tool call.
	 *
	 * @param input Codex hook event
	 * @param output optional Codex tool rewrite
	 * @throws IOException if the hook event is invalid
	 */
	public static void run(InputStream input, OutputStream output) throws IOException
	{
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(output, "output");
		JsonNode event;
		try
		{
			event = JSON.readTree(input);
		}
		catch (JacksonException failure)
		{
			throw new IOException("Optimizer pre-tool event is malformed", failure);
		}
		if (event == null || !event.isObject())
			throw new IOException("Optimizer pre-tool hook requires an object event");
		if (!"PreToolUse".equals(event.path("hook_event_name").asString()) ||
			!"Bash".equals(event.path("tool_name").asString()))
			return;
		JsonNode inputNode = event.path("tool_input");
		JsonNode commandNode = inputNode.path("command");
		if (!inputNode.isObject() || !commandNode.isString())
			return;
		String command = commandNode.asString();
		OptimizerToolCommand inspectionCall = OptimizerToolCommand.findInspectionCall(command).orElse(null);
		if (inspectionCall == null)
			return;

		ObjectNode updated;
		try
		{
			String session = requiredText(event, "session_id");
			String turn = requiredText(event, "turn_id");
			String toolUse = requiredText(event, "tool_use_id");
			String model = requiredText(event, "model");
			updated = (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(inputNode));
			updated.put("command", inspectionCall.bindHostIdentity(session, turn, toolUse, model));
		}
		catch (IOException | IllegalArgumentException failure)
		{
			output.write(JSON.writeValueAsBytes(Map.of("hookSpecificOutput", Map.of(
				"hookEventName", "PreToolUse", "permissionDecision", "deny",
				"permissionDecisionReason", "CAT could not bind this inspection command to its Codex tool call: " +
					failure.getMessage()))));
			return;
		}

		output.write(JSON.writeValueAsBytes(Map.of("hookSpecificOutput", Map.of(
			"hookEventName", "PreToolUse", "permissionDecision", "allow", "updatedInput", updated))));
	}

	/**
	 * Reads a required current-tool identity without normalizing it.
	 *
	 * @param event current Codex tool event
	 * @param name required field
	 * @return exact non-blank identity
	 * @throws IOException if Codex omitted the identity
	 */
	private static String requiredText(JsonNode event, String name) throws IOException
	{
		JsonNode value = event.path(name);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Optimizer pre-tool event is missing " + name);
		return value.asString();
	}

	/** Prevents construction. */
	private CodexOptimizerPreToolHook()
	{
	}
}
