package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.ROOT_AGENT;

import io.github.cowwoc.cat.common.optimizer.adapter.HarnessEvents;
import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.claude.secrets.StreamJsonEventsAccess;
import io.github.cowwoc.cat.claude.secrets.StreamJsonEventsSecrets;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads what a session's Claude {@code stream-json} records say about the model, token usage, rate limits,
 * errors, root tool calls and ordered trial activity.
 * <p>
 * The parser follows the records that the runner captured:
 * <ul>
 *   <li>the {@code init} record names the resolved model, and its {@code effort} when it has one. Claude Code
 *   2.1.286 reports the effort as {@code null};</li>
 *   <li>{@code rate_limit_event} records carry the {@code five_hour} and {@code seven_day} windows, which are
 *   reported in the order that the records first named them;</li>
 *   <li>a {@code result} record's {@code modelUsage} is cumulative over the session and includes the usage of
 *   background children, unlike its per-reply {@code usage} object;</li>
 *   <li>an error {@code result} record whose {@code api_error_status} is 429 is a rate-limit rejection, one whose
 *   status is 403 is a refusal of the request by the provider, and any other is an error;</li>
 *   <li>a root {@code user} record answers a {@code Bash} call with a {@code tool_result} and a
 *   {@code tool_use_result}. Claude Code 2.1.287 reports no exit status as a field. A result that is not an error
 *   means exit status 0, except that a {@code returnCodeInterpretation} such as {@code No matches found} means
 *   exit status 1, which Claude treats as success for commands such as {@code grep} and {@code diff}. An error
 *   result's {@code tool_use_result} starts with {@code Error: Exit code N} when the command ran. A background
 *   command, an interrupted command, or an error in any other form, such as a command that Claude never ran, has no
 *   exit status;</li>
 *   <li>model output in a turn is an {@code assistant} record, a {@code stream_event} record, or a {@code result}
 *   record that reports usage. A {@code user} record, a {@code rate_limit_event} record and a {@code system} record
 *   other than a hook are not output. A {@code hook_started} record marks a hook as running until its
 *   {@code hook_response} record.</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class StreamJsonEvents extends HarnessEvents
{
	static
	{
		StreamJsonEventsSecrets.setAccess(new StreamJsonEventsAccess()
		{
			@Override
			public StreamJsonEvents newStreamJsonEvents(JsonMapper json)
			{
				return new StreamJsonEvents(json);
			}
		});
	}

	private static final String RATE_LIMITED_STATUS = "rejected";
	private static final int RATE_LIMITED_ERROR = 429;
	private static final int REFUSED_ERROR = 403;
	private static final Pattern EXIT_CODE = Pattern.compile("Error: Exit code (\\d{1,9})(?:\n|$)");
	private final Map<String, RateLimitWindow> windows = new LinkedHashMap<>();
	private final Map<String, ToolCall> calls = new LinkedHashMap<>();
	private final List<SessionEvidence.Entry> entries = new ArrayList<>();
	/**
	 * The content of each {@code tool_result}, which is what the agent received; Claude Code can shorten it below the
	 * tool's complete output.
	 */
	private final List<String> received = new ArrayList<>();
	private final Map<String, Integer> toolEntries = new HashMap<>();
	private final Map<String, Integer> hookEntries = new HashMap<>();
	private final Map<String, Integer> messageEntries = new HashMap<>();
	private final Map<String, String> messageTexts = new HashMap<>();
	private final Map<String, Integer> assistantCounts = new HashMap<>();
	private final Map<String, String> lastTexts = new HashMap<>();
	private int turn = 1;
	private long anonymousMessage;
	private boolean turnCompleted;
	private Instant lastResetsAt;

	/**
	 * Creates a parser that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 */
	StreamJsonEvents(JsonMapper json)
	{
		super(json);
	}

	@Override
	protected void read(JsonNode event)
	{
		switch (event.path("type").asString(""))
		{
			case "system" ->
			{
				switch (event.path("subtype").asString(""))
				{
					case "init" -> readInit(event);
					case "hook_started", "hook_response" -> readHook(event);
					default ->
					{
					}
				}
			}
			case "assistant" ->
			{
				setOutputProduced();
				readToolCalls(event);
			}
			case "stream_event" -> setOutputProduced();
			case "user" -> readToolResults(event);
			case "rate_limit_event" -> readRateLimit(event.path("rate_limit_info"));
			case "result" -> readResult(event);
			default ->
			{
			}
		}
	}

	/**
	 * Records tool-use blocks for root checks and trial evidence, interleaving assistant positions.
	 *
	 * @param event the assistant message event
	 */
	private void readToolCalls(JsonNode event)
	{
		JsonNode content = event.path("message").path("content");
		if (!content.isArray())
			return;
		String agent = agent(event);
		if (agent.equals(ROOT_AGENT))
			beginTurn();
		int eventTurn = turnFor(agent);
		String message = event.path("message").path("id").asString("");
		if (message.isBlank())
			message = event.path("uuid").asString("");
		if (message.isBlank())
		{
			anonymousMessage += 1;
			message = "anonymous-" + anonymousMessage;
		}
		int blockIndex = 0;
		for (JsonNode block : content)
		{
			switch (block.path("type").asString(""))
			{
				case "tool_use" -> readToolCall(block, eventTurn, agent);
				case "text" -> recordAssistant(eventTurn, agent, message + ":" + blockIndex,
					block.path("text").asString(""));
				default ->
				{
				}
			}
			blockIndex += 1;
		}
		setToolCalls(calls.values().stream().toList());
		setEvidence(new SessionEvidence(entries, received));
	}

	/**
	 * Records a tool invocation once, sharing command extraction with root tool-call checks.
	 *
	 * @param block the tool-use block
	 * @param eventTurn the root turn that owns the call
	 * @param agent the root name or child identity
	 */
	private void readToolCall(JsonNode block, int eventTurn, String agent)
	{
		String id = block.path("id").asString("");
		String tool = block.path("name").asString("");
		if (id.isBlank() || tool.isBlank() || toolEntries.containsKey(id))
			return;
		String command = null;
		JsonNode input = block.path("input");
		JsonNode commandNode = input.path("command");
		if (tool.equals("Bash") && commandNode.isString() && !commandNode.stringValue().isBlank())
			command = commandNode.stringValue();
		if (agent.equals(ROOT_AGENT))
			calls.put(id, new ToolCall(tool, command, null));
		String arguments = command;
		if (arguments == null)
			arguments = contentText(input);
		toolEntries.put(id, entries.size());
		entries.add(new SessionEvidence.Tool(eventTurn, agent, tool, arguments, command != null, null, ""));
	}

	/**
	 * Records tool outputs, the exit status of each command that a {@code tool_result} answers, and the content of each
	 * {@code tool_result} as received.
	 *
	 * @param event the user message event
	 */
	private void readToolResults(JsonNode event)
	{
		JsonNode content = event.path("message").path("content");
		if (!content.isArray())
			return;
		for (JsonNode block : content)
		{
			if (!block.path("type").asString("").equals("tool_result"))
				continue;
			received.add(contentText(block.path("content")));
			String id = block.path("tool_use_id").asString("");
			Integer index = toolEntries.get(id);
			if (index == null)
				continue;
			SessionEvidence.Tool tool = (SessionEvidence.Tool) entries.get(index);
			JsonNode result = event.path("tool_use_result");
			Integer status = null;
			if (tool.tool().equals("Bash"))
				status = exitStatus(block.path("is_error").asBoolean(false), result);
			if (status == null)
				status = tool.exitStatus();
			entries.set(index, new SessionEvidence.Tool(tool.turn(), tool.agent(), tool.tool(), tool.input(),
				tool.command(), status, toolOutput(tool.tool(), result, block.path("content"))));

			ToolCall call = calls.get(id);
			if (call != null && call.command() != null && call.exitStatus() == null && status != null)
				calls.put(id, new ToolCall(call.tool(), call.command(), status));
		}
		setToolCalls(calls.values().stream().toList());
		setEvidence(new SessionEvidence(entries, received));
	}

	/**
	 * Selects complete command output or tool content while omitting metadata outside the result's content. Claude Code
	 * reports a result as a string only for an error, such as a failed or rejected call; the string is its own wording
	 * of the error rather than fuller output, so the output is the content shown to the assistant.
	 *
	 * @param tool the tool name
	 * @param result the record's structured result
	 * @param content the tool-result content shown to the assistant
	 * @return the complete reported output
	 */
	private static String toolOutput(String tool, JsonNode result, JsonNode content)
	{
		if (result.isString())
			return contentText(content);
		if (tool.equals("Bash") && result.isObject() && !result.has("backgroundTaskId") &&
			(result.has("stdout") || result.has("stderr")))
		{
			return result.path("stdout").asString("") + result.path("stderr").asString("");
		}
		JsonNode reportedContent = result.path("content");
		if (!reportedContent.isMissingNode() && !reportedContent.isNull())
			return contentText(reportedContent);
		return contentText(content);
	}

	/**
	 * Converts tool content without limiting text or dropping non-text content blocks.
	 *
	 * @param content the reported content
	 * @return its text, or JSON for non-text content
	 */
	private static String contentText(JsonNode content)
	{
		if (content.isString())
			return content.stringValue();
		if (content.isMissingNode() || content.isNull())
			return "";
		if (!content.isArray())
			return content.toString();
		StringJoiner result = new StringJoiner("\n");
		for (JsonNode block : content)
		{
			if (block.path("type").asString("").equals("text") && block.path("text").isString())
				result.add(block.path("text").stringValue());
			else
				result.add(block.toString());
		}
		return result.toString();
	}

	/**
	 * Records an assistant position once and replaces cumulative text with its complete form.
	 *
	 * @param eventTurn the root turn that owns the message
	 * @param agent the root name or child identity
	 * @param message the message and content-block identity
	 * @param text the assistant text
	 */
	private void recordAssistant(int eventTurn, String agent, String message, String text)
	{
		if (text.isBlank())
			return;
		String actorTurn = eventTurn + ":" + agent;
		String key = actorTurn + ":" + message;
		String previous = messageTexts.get(key);
		if (previous != null && previous.startsWith(text))
			return;
		String evidenceText = text;
		if (agent.equals(ROOT_AGENT))
			evidenceText = "";
		if (previous != null && text.startsWith(previous))
		{
			int index = messageEntries.get(key);
			SessionEvidence.Assistant assistant = (SessionEvidence.Assistant) entries.get(index);
			entries.set(index, new SessionEvidence.Assistant(eventTurn, agent, assistant.position(), evidenceText));
		}
		else
		{
			int position = assistantCounts.merge(actorTurn, 1, Integer::sum);
			messageEntries.put(key, entries.size());
			entries.add(new SessionEvidence.Assistant(eventTurn, agent, position, evidenceText));
		}
		messageTexts.put(key, text);
		lastTexts.put(actorTurn, text);
	}

	/**
	 * Records a hook's status, replacing an unreported outcome when its response arrives.
	 *
	 * @param event the hook event
	 */
	private void readHook(JsonNode event)
	{
		String hook = event.path("hook_name").asString("");
		String runningId = event.path("hook_id").asString(hook);
		if (!runningId.isBlank())
		{
			if (event.path("subtype").asString("").equals("hook_started"))
				setHookStarted(runningId);
			else
				setHookCompleted(runningId);
		}
		if (hook.isBlank())
			return;
		String agent = agent(event);
		if (agent.equals(ROOT_AGENT) && event.path("hook_event").asString("").equals("SessionStart"))
			beginTurn();
		int eventTurn = turnFor(agent);
		String id = event.path("hook_id").asString(hook);
		String key = eventTurn + ":" + agent + ":" + id;
		Integer index = hookEntries.get(key);
		if (index != null && event.path("subtype").asString("").equals("hook_started"))
			return;
		String status = event.path("outcome").asString("");
		if (status.isBlank())
			status = event.path("status").asString("not reported");
		if (status.isBlank())
			status = "not reported";
		SessionEvidence.Hook entry = new SessionEvidence.Hook(eventTurn, agent, hook, status);
		if (index == null)
		{
			hookEntries.put(key, entries.size());
			entries.add(entry);
		}
		else
			entries.set(index, entry);
		setEvidence(new SessionEvidence(entries, received));
	}

	/**
	 * Returns the root name or the parent tool-use identity that identifies a child's records.
	 *
	 * @param event the harness record
	 * @return the actor
	 */
	private static String agent(JsonNode event)
	{
		String parent = event.path("parent_tool_use_id").asString("");
		if (parent.isBlank())
			return ROOT_AGENT;
		return parent;
	}

	/**
	 * Returns the turn that spawned a child, or the current turn for a root or an unobserved parent call.
	 *
	 * @param agent the root name or parent tool-use identity
	 * @return the owning root turn
	 */
	private int turnFor(String agent)
	{
		Integer parent = toolEntries.get(agent);
		if (parent == null)
			return turn;
		return entries.get(parent).turn();
	}

	/**
	 * Advances to the next root turn after a completed result, without advancing for background child records.
	 */
	private void beginTurn()
	{
		if (!turnCompleted)
			return;
		turn += 1;
		turnCompleted = false;
	}

	/**
	 * Returns the exit status that a {@code Bash} tool result reports.
	 *
	 * @param error  {@code true} if Claude marked the result as an error
	 * @param result the record's {@code tool_use_result}
	 * @return the exit status, or {@code null} if the result does not report one
	 */
	private static Integer exitStatus(boolean error, JsonNode result)
	{
		if (error)
		{
			if (!result.isString())
				return null;
			Matcher matcher = EXIT_CODE.matcher(result.stringValue());
			if (!matcher.lookingAt())
				return null;
			return Integer.valueOf(matcher.group(1));
		}
		if (!result.isObject() || result.path("interrupted").asBoolean(false) || result.has("backgroundTaskId"))
			return null;
		if (result.has("returnCodeInterpretation"))
			return 1;
		return 0;
	}

	/**
	 * Reads a session's {@code init} record.
	 *
	 * @param event the record
	 */
	private void readInit(JsonNode event)
	{
		if (agent(event).equals(ROOT_AGENT))
			beginTurn();
		JsonNode modelNode = event.path("model");
		if (modelNode.isString() && !modelNode.stringValue().isBlank())
			setModel(modelNode.stringValue());
		JsonNode effort = event.path("effort");
		if (effort.isString() && !effort.stringValue().isBlank())
			setReasoning(effort.stringValue());
	}

	/**
	 * Reads the {@code rate_limit_info} of a {@code rate_limit_event} record.
	 *
	 * @param info the {@code rate_limit_info} object
	 */
	private void readRateLimit(JsonNode info)
	{
		Instant resetsAt = epochSeconds(info.path("resetsAt"));
		if (resetsAt != null)
			lastResetsAt = resetsAt;
		JsonNode unified = info.path("unifiedWindows");
		if (unified.isObject())
		{
			for (Map.Entry<String, JsonNode> entry : unified.properties())
			{
				JsonNode window = entry.getValue();
				Instant windowReset = epochSeconds(window.path("resetsAt"));
				if (!window.path("utilization").isNumber() || windowReset == null)
					continue;
				double utilization = Math.clamp(window.path("utilization").asDouble(), 0.0, 1.0);
				windows.put(entry.getKey(), new RateLimitWindow(utilization, windowReset));
			}
			setWindows(windows.values().stream().toList());
		}
		if (info.path("status").asString("").equals(RATE_LIMITED_STATUS))
			setRejection(new Rejection(resetsAt));
	}

	/**
	 * Reads a {@code result} record.
	 *
	 * @param event the record
	 */
	private void readResult(JsonNode event)
	{
		String agent = agent(event);
		int eventTurn = turnFor(agent);
		String text = event.path("result").asString("");
		if (!text.isBlank() && !text.equals(lastTexts.get(eventTurn + ":" + agent)))
			recordAssistant(eventTurn, agent, "result", text);
		if (agent.equals(ROOT_AGENT))
			turnCompleted = true;
		setEvidence(new SessionEvidence(entries, received));

		JsonNode models = event.path("modelUsage");
		if (models.isObject() && !models.isEmpty())
		{
			setOutputProduced();
			long input = 0;
			long cached = 0;
			long output = 0;
			long reasoningOutput = 0;
			for (Map.Entry<String, JsonNode> entry : models.properties())
			{
				JsonNode used = entry.getValue();
				long cacheRead = used.path("cacheReadInputTokens").asLong(0);
				input += used.path("inputTokens").asLong(0) + cacheRead + used.path("cacheCreationInputTokens").asLong(0);
				cached += cacheRead;
				output += used.path("outputTokens").asLong(0);
				reasoningOutput += used.path("thinkingTokens").asLong(0);
			}
			setUsage(new TokenUsage(input, cached, output, reasoningOutput));
		}
		if (!event.path("is_error").asBoolean(false))
			return;
		JsonNode status = event.path("api_error_status");
		if (status.isNumber() && status.asInt() == RATE_LIMITED_ERROR)
		{
			if (rejection().isEmpty())
				setRejection(new Rejection(lastResetsAt));
			return;
		}
		if (status.isNumber() && status.asInt() == REFUSED_ERROR)
		{
			setRefused();
			return;
		}
		String statusText = "none";
		if (!status.isNull() && !status.isMissingNode())
			statusText = status.asString("none");
		setError("Claude reported an error result (api_error_status=" + statusText + ", terminal_reason=" +
			event.path("terminal_reason").asString("none") + ")");
	}

	/**
	 * Converts a JSON number of epoch seconds to an instant.
	 *
	 * @param node the JSON node
	 * @return the instant, or {@code null} if the node is not a number
	 */
	private static Instant epochSeconds(JsonNode node)
	{
		if (!node.isNumber())
			return null;
		return Instant.ofEpochSecond(node.asLong());
	}
}
