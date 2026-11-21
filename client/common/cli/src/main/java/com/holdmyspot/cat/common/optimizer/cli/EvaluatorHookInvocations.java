package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.port.HookInvocation;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The bounded response of the evaluator's read-only startup-hook inspection.
 */
final class EvaluatorHookInvocations
{
	private static final int MAXIMUM_BYTES = 1_048_576;
	// The Windows mapping supplies every home key the wire format allows, regardless of the current host.
	private static final Set<String> PROCESS_HOME =
		HarnessEnvironment.homeVariables(Path.of(""), Platform.WINDOWS_X64).keySet();
	private static final Set<String> CODEX_OVERLAY = Set.of("CODEX_HOME", "PLUGIN_ROOT");
	private static final Set<String> CLAUDE_OVERLAY = Set.of("CLAUDE_CONFIG_DIR", "CLAUDE_PROJECT_DIR",
		"CLAUDE_PLUGIN_ROOT");

	/**
	 * Writes the native observations in declaration order.
	 *
	 * @param json the protocol mapper
	 * @param out receives the report
	 * @param evaluator the evaluator producing the report
	 * @param hooks read-only descriptors of the native hooks
	 * @throws IOException if the report exceeds its bound or cannot be written
	 */
	static void write(JsonMapper json, PrintStream out, Harness evaluator, List<HookInvocation> hooks)
		throws IOException
	{
		ObjectNode report = json.createObjectNode().put("harness", evaluator.commandName());
		ArrayNode entries = report.putArray("hooks");
		for (HookInvocation hook : hooks)
		{
			checkOverlay(evaluator, hook.environment().keySet());
			ObjectNode entry = entries.addObject().put("name", hook.name()).put("input", hook.input()).
				put("timeout", hook.timeout().toString());
			ArrayNode command = entry.putArray("command");
			hook.command().forEach(command::add);
			ObjectNode environment = entry.putObject("environment");
			hook.environment().forEach(environment::put);
		}
		byte[] bytes = json.writeValueAsBytes(report);
		if (bytes.length >= MAXIMUM_BYTES)
			throw new IOException("Evaluator hook report exceeds its size limit");
		out.write(bytes);
		out.write('\n');
		if (out.checkError())
			throw new IOException("Cannot write evaluator hook report");
	}

	/**
	 * Reads the requested evaluator's report and closes the process output stream.
	 *
	 * @param json the protocol mapper
	 * @param input the process output
	 * @param evaluator the requested peer identity
	 * @return its startup-hook descriptors
	 * @throws IOException if the response exceeds its bound or does not identify the requested peer
	 */
	static List<HookInvocation> read(JsonMapper json, InputStream input, Harness evaluator) throws IOException
	{
		byte[] bytes = BoundedInput.readBytes(input, evaluator.productName(), MAXIMUM_BYTES, "Evaluator hook report");
		JsonNode report = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
			DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readValue(bytes);
		if (!evaluator.commandName().equals(EvaluatorPluginBases.text(report, "harness")))
			throw new IOException("The hook report identifies a different evaluator");
		JsonNode hooks = report.path("hooks");
		if (!hooks.isArray())
			throw new IOException("Expected evaluator startup-hook descriptors");
		List<HookInvocation> result = new ArrayList<>();
		for (JsonNode hook : hooks)
			result.add(readHook(hook, evaluator));
		return List.copyOf(result);
	}

	/**
	 * Copies one typed hook descriptor out of the wire response.
	 *
	 * @param hook the serialized descriptor
	 * @param evaluator the native adapter that supplied it
	 * @return immutable native hook data
	 * @throws IOException if a command or environment value has the wrong type
	 */
	private static HookInvocation readHook(JsonNode hook, Harness evaluator) throws IOException
	{
		JsonNode command = hook.path("command");
		JsonNode environment = hook.path("environment");
		if (!command.isArray() || !environment.isObject())
			throw new IOException("Invalid evaluator hook descriptor");
		List<String> arguments = new ArrayList<>();
		for (JsonNode argument : command)
		{
			if (!argument.isString())
				throw new IOException("Expected an evaluator hook argument");
			arguments.add(argument.stringValue());
		}
		Map<String, String> overlay = new HashMap<>();
		for (Map.Entry<String, JsonNode> variable : environment.properties())
		{
			if (!variable.getValue().isString())
				throw new IOException("Expected an evaluator hook environment value");
			overlay.put(variable.getKey(), variable.getValue().stringValue());
		}
		checkOverlay(evaluator, overlay.keySet());
		return new HookInvocation(EvaluatorPluginBases.text(hook, "name"), arguments, overlay,
			EvaluatorPluginBases.text(hook, "input"), Duration.parse(EvaluatorPluginBases.text(hook, "timeout")));
	}

	/**
	 * Rejects any variable outside the path-only environment defined for the native adapter.
	 *
	 * @param evaluator which adapter generated the keys
	 * @param names the descriptor's environment keys
	 * @throws IOException if the overlay includes data outside the descriptor contract
	 */
	private static void checkOverlay(Harness evaluator, Set<String> names) throws IOException
	{
		Set<String> nativeOverlay = switch (evaluator)
		{
			case CODEX -> CODEX_OVERLAY;
			case CLAUDE -> CLAUDE_OVERLAY;
		};
		for (String name : names)
		{
			if (!PROCESS_HOME.contains(name) && !nativeOverlay.contains(name))
				throw new IOException("Unexpected variable in evaluator hook environment overlay");
		}
	}

	/**
	 * Prevents construction.
	 */
	private EvaluatorHookInvocations()
	{
	}
}
