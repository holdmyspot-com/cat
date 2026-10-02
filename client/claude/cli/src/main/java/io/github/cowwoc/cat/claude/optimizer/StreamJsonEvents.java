package io.github.cowwoc.cat.claude.optimizer;

import io.github.cowwoc.cat.common.optimizer.adapter.HarnessEvents;
import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads what a session's Claude {@code stream-json} records say about the model, token usage, rate limits,
 * errors and root tool calls.
 * <p>
 * The parser follows the records that the runner captured:
 * <ul>
 *   <li>the {@code init} record names the resolved model, and its {@code effort} when it has one. Claude Code
 *   2.1.286 reports the effort as {@code null};</li>
 *   <li>{@code rate_limit_event} records carry the {@code five_hour} and {@code seven_day} windows, which are
 *   reported in the order that the records first named them;</li>
 *   <li>a {@code result} record's {@code modelUsage} is cumulative over the session and includes the usage of
 *   background children, unlike its per-reply {@code usage} object;</li>
 *   <li>a root {@code user} record answers a {@code Bash} call with a {@code tool_result} and a
 *   {@code tool_use_result}. Claude Code 2.1.287 reports no exit status as a field. A result that is not an error
 *   means exit status 0, except that a {@code returnCodeInterpretation} such as {@code No matches found} means
 *   exit status 1, which Claude treats as success for commands such as {@code grep} and {@code diff}. An error
 *   result's {@code tool_use_result} starts with {@code Error: Exit code N} when the command ran. A background
 *   command, an interrupted command, or an error in any other form, such as a command that Claude never ran, has no
 *   exit status.</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class StreamJsonEvents extends HarnessEvents
{
	private static final String RATE_LIMITED_STATUS = "rejected";
	private static final int RATE_LIMITED_ERROR = 429;
	private static final Pattern EXIT_CODE = Pattern.compile("Error: Exit code (\\d{1,9})(?:\n|$)");
	private final Map<String, RateLimitWindow> windows = new LinkedHashMap<>();
	private final Map<String, ToolCall> calls = new LinkedHashMap<>();
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
				if (event.path("subtype").asString("").equals("init"))
					readInit(event);
			}
			case "assistant" -> readToolCalls(event);
			case "user" -> readToolResults(event);
			case "rate_limit_event" -> readRateLimit(event.path("rate_limit_info"));
			case "result" -> readResult(event);
			default ->
			{
			}
		}
	}

	/**
	 * Records root tool-use blocks without mistaking reply text or child calls for tool calls.
	 *
	 * @param event the assistant message event
	 */
	private void readToolCalls(JsonNode event)
	{
		JsonNode parent = event.path("parent_tool_use_id");
		if (!parent.isNull() && !parent.isMissingNode())
			return;
		JsonNode content = event.path("message").path("content");
		if (!content.isArray())
			return;
		for (JsonNode block : content)
		{
			if (!block.path("type").asString("").equals("tool_use"))
				continue;
			String id = block.path("id").asString("");
			String tool = block.path("name").asString("");
			if (id.isBlank() || tool.isBlank())
				continue;
			String command = null;
			JsonNode input = block.path("input").path("command");
			if (tool.equals("Bash") && input.isString() && !input.stringValue().isBlank())
				command = input.stringValue();
			calls.putIfAbsent(id, new ToolCall(tool, command, null));
		}
		setToolCalls(calls.values().stream().toList());
	}

	/**
	 * Records the exit status of each root command that a {@code tool_result} answers.
	 *
	 * @param event the user message event
	 */
	private void readToolResults(JsonNode event)
	{
		JsonNode parent = event.path("parent_tool_use_id");
		if (!parent.isNull() && !parent.isMissingNode())
			return;
		JsonNode content = event.path("message").path("content");
		if (!content.isArray())
			return;
		boolean changed = false;
		for (JsonNode block : content)
		{
			if (!block.path("type").asString("").equals("tool_result"))
				continue;
			String id = block.path("tool_use_id").asString("");
			ToolCall call = calls.get(id);
			if (call == null || call.command() == null || call.exitStatus() != null)
				continue;
			Integer exitStatus = exitStatus(block.path("is_error").asBoolean(false), event.path("tool_use_result"));
			if (exitStatus == null)
				continue;
			calls.put(id, new ToolCall(call.tool(), call.command(), exitStatus));
			changed = true;
		}
		if (changed)
			setToolCalls(calls.values().stream().toList());
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
		JsonNode models = event.path("modelUsage");
		if (models.isObject() && !models.isEmpty())
		{
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
