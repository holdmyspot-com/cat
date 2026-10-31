package com.holdmyspot.cat.codex.optimizer;

import static com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.ROOT_AGENT;

import com.holdmyspot.cat.codex.optimizer.RolloutMessages.RolloutMessage;
import com.holdmyspot.cat.common.optimizer.contract.ToolCall;
import com.holdmyspot.cat.common.optimizer.evidence.CommandSummary;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.Assistant;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.ChildEvent;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.CommandEnd;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.Entry;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.Hook;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.Question;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.Tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import tools.jackson.databind.JsonNode;

/**
 * Maps {@link AppServerEvents}' recognized activity into complete, ordered judge evidence. The parser owns access
 * to this mutable mapping.
 * <p>
 * The app-server reports each child's start and end, but not what the root received from a child; the root's rollout
 * records that. Each message that the root received is placed after the root item that completed most recently before
 * it, so that it appears among the root's activity where the root received it.
 * <p>
 * The app-server reports a command when it starts and when it ends, but an agent can receive a result from the command
 * before it ends; the agent's rollout records that. Such a command is shown where it started with each result that its
 * agent received while it was running, and its end is shown where the app-server reported it.
 */
final class AppServerEvidence
{
	private static final Set<String> OUTPUT_FIELDS = Set.of("aggregatedOutput", "output", "result", "results",
		"contentItems", "agentsStates", "status", "success", "error");
	private static final Set<String> ITEM_METADATA = Set.of("type", "id", "tool", "durationMs", "pluginId",
		"scriptPath", "processId", "source", "commandActions", "senderThreadId");
	/**
	 * The line that ends the header of a result that Codex returns from a command, before the command's output.
	 */
	private static final String OUTPUT_HEADER = "Output:\n";
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final Map<String, Integer> rootTurns = new HashMap<>();
	private final Map<String, Integer> childTurns = new HashMap<>();
	private final Map<String, Integer> responsePositions = new HashMap<>();
	/**
	 * The thread of each child by the task path that its parent gave it.
	 */
	private final Map<String, String> childThreads = new HashMap<>();
	/**
	 * The turn of each completed root item and the number of entries when it completed, by the item's identifier.
	 */
	private final Map<String, Completion> completions = new HashMap<>();
	/**
	 * The messages that each thread received from other agents, by the thread's identifier.
	 */
	private final Map<String, List<RolloutMessage>> received;
	/**
	 * The results that each agent received from a command that was still running, by the command's item identifier.
	 */
	private final Map<String, List<String>> running;
	/**
	 * The end of each command whose agent received a result from it while it was still running, with the number of
	 * entries when it ended, by the command's entry key.
	 */
	private final Map<String, Placed> ends = new LinkedHashMap<>();
	/**
	 * How long each command ran, by the command's entry key; {@code null} until the app-server reports it.
	 */
	private final Map<String, Duration> durations = new HashMap<>();
	private int currentTurn = 1;

	/**
	 * Creates an empty activity mapping.
	 *
	 * @param received the messages that each thread received from other agents, in the order that it received them,
	 *                 by the thread's identifier
	 * @param running  the results that each agent received from a command that was still running, in the order that
	 *                 it received them, by the identifier of the command's item
	 */
	AppServerEvidence(Map<String, List<RolloutMessage>> received, Map<String, List<String>> running)
	{
		this.received = received;
		this.running = running;
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
	 * Records an assistant's position, a child's start or end, or a message to a child; prompt and reasoning items
	 * contribute no evidence.
	 *
	 * @param params the activity notification's parameters
	 * @param rootThread the root identifier
	 */
	void readActivity(JsonNode params, String rootThread)
	{
		JsonNode item = params.path("item");
		switch (item.path("type").asString(""))
		{
			case "subAgentActivity" -> readChild(params, rootThread);
			case "agentMessage" -> readAssistant(params, rootThread);
			default ->
			{
			}
		}
	}

	/**
	 * Records a child's start or end, or a message that an agent sent a child that it started, at its original
	 * position. Codex records the assignment that a child was started with, and every message sent through a
	 * collaboration tool, only in encrypted form, so a start or a sent message holds no text. A child's message to the
	 * agent that started it is recorded where that agent received the message instead.
	 *
	 * @param params the child activity's notification parameters
	 * @param rootThread the root identifier
	 */
	private void readChild(JsonNode params, String rootThread)
	{
		JsonNode item = params.path("item");
		String child = item.path("agentThreadId").asString("");
		if (child.isBlank())
			return;
		String path = item.path("agentPath").asString("");
		ChildEvent.Kind kind;
		switch (item.path("kind").asString(""))
		{
			case "started" ->
			{
				childTurns.put(child, resolveTurn(params, rootThread));
				if (!path.isBlank())
					childThreads.put(path, child);
				kind = ChildEvent.Kind.STARTED;
			}
			case "completed" -> kind = ChildEvent.Kind.ENDED;
			case "interacted" ->
			{
				// An interaction whose target is not a started child is a child's message to the agent that started it
				if (!childTurns.containsKey(child))
					return;
				kind = ChildEvent.Kind.SENT;
			}
			default ->
			{
				return;
			}
		}
		entries.put(createKey(params, "item", item.path("id").asString("")), new ChildEvent(
			resolveTurn(params, rootThread), resolveActor(params, rootThread), child, path, kind, ""));
	}

	/**
	 * Retains one marker for each root response and the full text of each child response at its original position,
	 * with the questions of a message that asked the user for input. Codex records such a message as an agent message
	 * whose {@code questions} holds a {@code title} and an {@code options} array, which is {@code null} when the agent
	 * offered no answers.
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
		entries.put(key, new Assistant(turn, actor, position, message, readQuestions(item.path("questions"))));
	}

	/**
	 * Reads the questions that an agent message asked the user.
	 *
	 * @param questions the message's {@code questions} value
	 * @return the questions in the order asked; empty if the message asked none
	 */
	private static List<Question> readQuestions(JsonNode questions)
	{
		List<Question> result = new ArrayList<>();
		for (JsonNode question : questions)
		{
			List<String> options = new ArrayList<>();
			for (JsonNode option : question.path("options"))
				options.add(option.asString(""));
			result.add(new Question(question.path("title").asString(""), options));
		}
		return result;
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
		String type = item.path("type").asString("");
		String input = switch (type)
		{
			case "commandExecution" -> renderValue(item.path("command"));
			case "dynamicToolCall", "mcpToolCall" -> renderValue(item.path("arguments"));
			case "fileChange" -> renderValue(item.path("changes"));
			default -> selectFields(item, false);
		};
		String output;
		List<String> receivedWhileRunning;
		if (type.equals("commandExecution"))
		{
			output = renderValue(item.path("aggregatedOutput"));
			receivedWhileRunning = running.getOrDefault(item.path("id").asString(""), List.of());
			String key = createKey(params, "item", item.path("id").asString(""));
			JsonNode duration = item.path("durationMs");
			if (duration.isIntegralNumber() && duration.longValue() >= 0)
				durations.put(key, Duration.ofMillis(duration.longValue()));
			else
				durations.putIfAbsent(key, null);
		}
		else
		{
			output = selectFields(item, true);
			receivedWhileRunning = List.of();
		}
		entries.put(createKey(params, "item", item.path("id").asString("")),
			new Tool(resolveTurn(params, rootThread), resolveActor(params, rootThread), call.tool(), input,
				type.equals("commandExecution"), call.exitStatus(), output, receivedWhileRunning));
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
	 * Records where a root item completed, so that a message that the root received after it can be placed there, and
	 * where a command whose agent received a result from it while it was still running ended.
	 *
	 * @param params the completion notification's parameters
	 * @param rootThread the root identifier, or {@code null} before it is known
	 */
	void readCompletion(JsonNode params, String rootThread)
	{
		if (rootThread == null)
			return;
		String id = params.path("item").path("id").asString("");
		if (id.isBlank())
			return;
		String key = createKey(params, "item", id);
		if (entries.get(key) instanceof Tool tool && tool.command() && !tool.running().isEmpty() &&
			!ends.containsKey(key))
		{
			ends.put(key, new Placed(entries.size(), new CommandEnd(tool.turn(), tool.agent(), tool.input(),
				tool.exitStatus(), tool.output())));
		}
		if (rootThread.equals(params.path("threadId").asString("")))
			completions.put(id, new Completion(resolveTurn(params, rootThread), entries.size()));
	}

	/**
	 * Returns an immutable view of every retained activity, in invocation order, with the end of each command whose
	 * agent received a result from it while it was still running where the command ended, and each message that the
	 * root received from a child after the root item that completed most recently before it. A message whose item the
	 * app-server has not reported follows the message before it, so the messages keep the order that the root
	 * received them in.
	 *
	 * @param rootThread the root identifier, or {@code null} before it is known
	 * @return the recorded trial activity
	 */
	SessionEvidence snapshot(String rootThread)
	{
		List<Placed> insertions = new ArrayList<>(ends.values());
		if (rootThread != null)
		{
			Completion previous = new Completion(1, 0);
			for (RolloutMessage message : received.getOrDefault(rootThread, List.of()))
			{
				Completion anchor = completions.getOrDefault(message.anchor(), previous);
				if (anchor.entries() < previous.entries())
					anchor = previous;
				String child = childThreads.getOrDefault(message.author(), message.author());
				insertions.add(new Placed(anchor.entries(), new ChildEvent(anchor.turn(), ROOT_AGENT, child,
					message.author(), ChildEvent.Kind.RECEIVED, message.text())));
				previous = anchor;
			}
		}
		// A stable sort keeps a command's end before a message placed at the same position, since the command ended
		// before the item that the message follows completed, and keeps the messages in the order received
		insertions.sort(Comparator.comparingInt(Placed::entries));
		List<Entry> activity = new ArrayList<>(entries.values());
		int inserted = 0;
		for (Placed placed : insertions)
		{
			activity.add(placed.entries() + inserted, placed.entry());
			inserted += 1;
		}
		return new SessionEvidence(activity, summarizeCommands());
	}

	/**
	 * Summarizes how the session's commands ran. A command that the app-server has not reported as ended has no known
	 * duration, so the longest is then unknown. A result that an agent received while its command was still running is
	 * empty if nothing follows the {@code Output:} line that ends its header, or if it is blank and has no header.
	 *
	 * @return the summary
	 */
	private CommandSummary summarizeCommands()
	{
		Duration slowest = Duration.ZERO;
		for (Duration duration : durations.values())
		{
			if (duration == null)
			{
				slowest = null;
				break;
			}
			if (duration.compareTo(slowest) > 0)
				slowest = duration;
		}
		int empty = 0;
		for (Entry entry : entries.values())
		{
			if (entry instanceof Tool tool && tool.command())
			{
				for (String result : tool.running())
				{
					if (isEmpty(result))
						empty += 1;
				}
			}
		}
		return new CommandSummary(slowest, empty);
	}

	/**
	 * Returns whether a result that an agent received from a running command held none of the command's output.
	 *
	 * @param result the result's text
	 * @return {@code true} if nothing follows the header's {@code Output:} line, or the result is blank and has no
	 *         header
	 */
	private static boolean isEmpty(String result)
	{
		int header = result.indexOf(OUTPUT_HEADER);
		if (header < 0)
			return result.isBlank();
		return result.substring(header + OUTPUT_HEADER.length()).isBlank();
	}

	/**
	 * Where a root item completed.
	 *
	 * @param turn    the one-based root turn of the item
	 * @param entries the number of entries when the item completed
	 */
	private record Completion(int turn, int entries)
	{
	}

	/**
	 * An entry that the app-server did not report as an item of its own, with where it belongs.
	 *
	 * @param entries the number of entries that precede it
	 * @param entry   the entry
	 */
	private record Placed(int entries, Entry entry)
	{
	}
}
