package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.ROOT_AGENT;

import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Assistant;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Entry;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Hook;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import tools.jackson.databind.JsonNode;

/**
 * Maps {@link AppServerEvents}' recognized activity into complete, ordered judge evidence. The parser owns access
 * to this mutable mapping.
 */
final class AppServerEvidence
{
	private static final Set<String> OUTPUT_FIELDS = Set.of("aggregatedOutput", "output", "result", "results",
		"contentItems", "agentsStates", "status", "success", "error");
	private static final Set<String> ITEM_METADATA = Set.of("type", "id", "tool", "durationMs", "pluginId",
		"scriptPath", "processId", "source", "commandActions", "senderThreadId");
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final Map<String, Integer> rootTurns = new HashMap<>();
	private final Map<String, Integer> childTurns = new HashMap<>();
	private final Map<String, Integer> responsePositions = new HashMap<>();
	private int currentTurn = 1;

	/**
	 * Creates an empty activity mapping.
	 */
	AppServerEvidence()
	{
	}

	/**
	 * Records the root turn's one-based position without counting a child's turn as another root turn.
	 *
	 * @param params the turn notification's parameters
	 * @param rootThread the root identifier, or {@code null} before it is known
	 */
	void readTurn(JsonNode params, String rootThread)
	{
		if (rootThread != null && rootThread.equals(params.path("threadId").asString("")))
			currentTurn = resolveTurn(params, rootThread);
	}

	/**
	 * Resolves a notification's root turn, retaining a child's relationship to the turn that started it.
	 *
	 * @param params the notification's parameters
	 * @param rootThread the root identifier
	 * @return the one-based root turn
	 */
	private int resolveTurn(JsonNode params, String rootThread)
	{
		String thread = params.path("threadId").asString("");
		if (!thread.equals(rootThread))
			return childTurns.getOrDefault(thread, currentTurn);
		String id = params.path("turnId").asString(params.path("turn").path("id").asString(""));
		if (id.isBlank())
			return currentTurn;
		return rootTurns.computeIfAbsent(id, _ -> rootTurns.size() + 1);
	}

	/**
	 * Records an assistant's position or a child's owning turn; prompt and reasoning items contribute no evidence.
	 *
	 * @param params the activity notification's parameters
	 * @param rootThread the root identifier
	 */
	void readActivity(JsonNode params, String rootThread)
	{
		JsonNode item = params.path("item");
		switch (item.path("type").asString(""))
		{
			case "subAgentActivity" ->
			{
				String child = item.path("agentThreadId").asString("");
				if (!child.isBlank() && item.path("kind").asString("").equals("started"))
					childTurns.put(child, resolveTurn(params, rootThread));
			}
			case "agentMessage" -> readAssistant(params, rootThread);
			default ->
			{
			}
		}
	}

	/**
	 * Retains one marker for each root response and the full text of each child response at its original position.
	 *
	 * @param params the assistant item's notification parameters
	 * @param rootThread the root identifier
	 */
	private void readAssistant(JsonNode params, String rootThread)
	{
		JsonNode item = params.path("item");
		String key = createKey(params, "item", item.path("id").asString(""));
		String actor = resolveActor(params, rootThread);
		int turn = resolveTurn(params, rootThread);
		int position;
		if (entries.get(key) instanceof Assistant previous)
			position = previous.position();
		else
			position = responsePositions.merge(turn + ":" + actor, 1, Integer::sum);
		String message = "";
		if (!actor.equals(ROOT_AGENT))
			message = item.path("text").asString("");
		entries.put(key, new Assistant(turn, actor, position, message));
	}

	/**
	 * Retains a hook's outcome at its original position, without context, diagnostics or other hook output.
	 *
	 * @param params the hook notification's parameters
	 * @param rootThread the root identifier, or {@code null} before it is known
	 */
	void readHook(JsonNode params, String rootThread)
	{
		if (rootThread == null || params.path("threadId").asString("").isBlank())
			return;
		JsonNode run = params.path("run");
		String name = run.path("eventName").asString("");
		String status = run.path("status").asString("");
		if (name.isBlank() || status.isBlank())
			return;
		entries.put(createKey(params, "hook", run.path("id").asString(name)),
			new Hook(resolveTurn(params, rootThread), resolveActor(params, rootThread), name, status));
	}

	/**
	 * Retains a recognized tool's complete inputs and outputs. The supplied call is the parser's authoritative tool
	 * classification, including its reported exit status.
	 *
	 * @param params the tool item's notification parameters
	 * @param rootThread the root identifier
	 * @param call the parser's recognized call
	 */
	void readTool(JsonNode params, String rootThread, ToolCall call)
	{
		JsonNode item = params.path("item");
		String input = switch (item.path("type").asString(""))
		{
			case "commandExecution" -> renderValue(item.path("command"));
			case "dynamicToolCall", "mcpToolCall" -> renderValue(item.path("arguments"));
			case "fileChange" -> renderValue(item.path("changes"));
			default -> selectFields(item, false);
		};
		String output;
		if (item.path("type").asString("").equals("commandExecution"))
			output = renderValue(item.path("aggregatedOutput"));
		else
			output = selectFields(item, true);
		entries.put(createKey(params, "item", item.path("id").asString("")),
			new Tool(resolveTurn(params, rootThread), resolveActor(params, rootThread), call.tool(), input,
				call.exitStatus(), output));
	}

	/**
	 * Selects a tool's input or result fields while omitting item metadata and telemetry.
	 *
	 * @param item the tool item
	 * @param output {@code true} for result fields, {@code false} for argument fields
	 * @return the complete selected values, in their recorded order
	 */
	private static String selectFields(JsonNode item, boolean output)
	{
		StringJoiner result = new StringJoiner("\n");
		for (Map.Entry<String, JsonNode> property : item.properties())
		{
			String name = property.getKey();
			JsonNode node = property.getValue();
			if (node.isNull() || ITEM_METADATA.contains(name) || OUTPUT_FIELDS.contains(name) != output)
				continue;
			result.add(name + ": " + renderValue(node));
		}
		return result.toString();
	}

	/**
	 * Returns text without quoting and structured arguments or results as complete JSON.
	 *
	 * @param node the reported value
	 * @return its text, or an empty string when absent
	 */
	private static String renderValue(JsonNode node)
	{
		if (node.isNull() || node.isMissingNode())
			return "";
		if (node.isString())
			return node.stringValue();
		return node.toString();
	}

	/**
	 * Names a root activity as {@code root} and a child activity by its thread identifier.
	 *
	 * @param params the notification's parameters
	 * @param rootThread the root identifier
	 * @return the actor name
	 */
	private static String resolveActor(JsonNode params, String rootThread)
	{
		String thread = params.path("threadId").asString(rootThread);
		if (thread.equals(rootThread))
			return ROOT_AGENT;
		return thread;
	}

	/**
	 * Separates item and hook identities across threads so completion updates their own original entry.
	 *
	 * @param params the notification's parameters
	 * @param kind the activity kind
	 * @param id the reported item or hook identifier
	 * @return the mapping key
	 */
	private static String createKey(JsonNode params, String kind, String id)
	{
		return params.path("threadId").asString("") + ":" + kind + ":" + id;
	}

	/**
	 * Returns an immutable view of every retained activity, in invocation order.
	 *
	 * @return the recorded trial activity
	 */
	SessionEvidence snapshot()
	{
		return new SessionEvidence(new ArrayList<>(entries.values()));
	}
}
