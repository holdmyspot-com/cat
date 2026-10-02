package io.github.cowwoc.cat.codex.optimizer;

import io.github.cowwoc.cat.common.optimizer.adapter.HarnessEvents;
import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads what a session's Codex app-server notifications say about the model, token usage, rate limits, errors and
 * root tool calls.
 * <p>
 * The parser follows the notifications that the runner captured:
 * <ul>
 *   <li>the first {@code thread/started} notification names the root thread, its model and its reasoning effort;</li>
 *   <li>{@code thread/tokenUsage/updated} carries each thread's cumulative {@code total}, and the session's usage is
 *   the sum over every thread, so that child threads count;</li>
 *   <li>{@code account/rateLimits/updated} carries the {@code primary} and {@code secondary} windows, which are
 *   reported in that order;</li>
 *   <li>{@code item/started} records a root tool call's position, and {@code item/completed} updates that same
 *   item; command items report their command and numeric {@code exitCode}, while collaboration items name their
 *   tool and built-in non-command items use their reported type;</li>
 *   <li>a {@code turn/completed} notification of the root thread whose status is neither {@code completed} nor
 *   {@code interrupted} reports an error, without Codex's error text.</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class AppServerEvents extends HarnessEvents
{
	private static final double PERCENT = 100.0;
	private final Map<String, TokenUsage> totals = new HashMap<>();
	private final Map<String, ToolCall> calls = new LinkedHashMap<>();
	private String rootThread;

	/**
	 * Creates a parser that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 */
	AppServerEvents(JsonMapper json)
	{
		super(json);
	}

	@Override
	protected void read(JsonNode event)
	{
		JsonNode params = event.path("params");
		switch (event.path("method").asString(""))
		{
			case "thread/started" -> readThreadStarted(params.path("thread"));
			case "thread/tokenUsage/updated" -> readTokenUsage(params);
			case "account/rateLimits/updated" -> readRateLimits(params.path("rateLimits"));
			case "turn/completed" -> readTurnCompleted(params);
			case "item/started", "item/completed" -> readToolCall(params);
			default ->
			{
			}
		}
	}

	/**
	 * Records a root tool item once, updating its completion without changing its invocation order.
	 *
	 * @param params the notification's parameters, including the owning thread and tool item
	 */
	private void readToolCall(JsonNode params)
	{
		if (rootThread == null || !params.path("threadId").asString("").equals(rootThread))
			return;
		JsonNode item = params.path("item");
		String id = text(item.path("id"));
		if (id == null)
			return;
		String type = item.path("type").asString("");
		ToolCall call = switch (type)
		{
			case "commandExecution" -> commandCall(item);
			case "collabAgentToolCall", "dynamicToolCall", "mcpToolCall" ->
			{
				String tool = text(item.path("tool"));
				if (tool == null)
					yield null;
				yield new ToolCall(tool, null, null);
			}
			case "fileChange", "webSearch", "imageView", "sleep", "imageGeneration" ->
				new ToolCall(type, null, null);
			default -> null;
		};
		if (call == null)
			return;

		calls.put(id, call);
		setToolCalls(new ArrayList<>(calls.values()));
	}

	/**
	 * Reads a command item's reported command and structured exit code, retaining absent values as null.
	 *
	 * @param item the command-execution item
	 * @return the recorded call
	 */
	private static ToolCall commandCall(JsonNode item)
	{
		String command = text(item.path("command"));
		JsonNode code = item.path("exitCode");
		Integer status = null;
		if (command != null && code.isIntegralNumber() && code.canConvertToInt())
			status = code.intValue();
		return new ToolCall("commandExecution", command, status);
	}

	/**
	 * Reads a {@code thread/started} notification.
	 *
	 * @param thread the notification's thread object
	 */
	private void readThreadStarted(JsonNode thread)
	{
		if (rootThread != null)
			return;
		rootThread = thread.path("id").asString("");
		setModel(text(thread.path("model")));
		setReasoning(text(thread.path("reasoningEffort")));
	}

	/**
	 * Reads a {@code thread/tokenUsage/updated} notification.
	 *
	 * @param params the notification's parameters
	 */
	private void readTokenUsage(JsonNode params)
	{
		JsonNode total = params.path("tokenUsage").path("total");
		if (!total.isObject() || !params.path("threadId").isString())
			return;
		totals.put(params.path("threadId").stringValue(), new TokenUsage(total.path("inputTokens").asLong(0),
			total.path("cachedInputTokens").asLong(0), total.path("outputTokens").asLong(0),
			total.path("reasoningOutputTokens").asLong(0)));
		long input = 0;
		long cached = 0;
		long output = 0;
		long reasoningOutput = 0;
		for (TokenUsage thread : totals.values())
		{
			input += thread.input();
			cached += thread.cachedInput();
			output += thread.output();
			reasoningOutput += thread.reasoningOutput();
		}
		setUsage(new TokenUsage(input, cached, output, reasoningOutput));
	}

	/**
	 * Reads the {@code rateLimits} of an {@code account/rateLimits/updated} notification.
	 *
	 * @param limits the {@code rateLimits} object
	 */
	private void readRateLimits(JsonNode limits)
	{
		List<RateLimitWindow> latest = new ArrayList<>();
		Instant exhausted = null;
		for (String name : List.of("primary", "secondary"))
		{
			JsonNode window = limits.path(name);
			if (!window.path("usedPercent").isNumber() || !window.path("resetsAt").isNumber())
				continue;
			double used = window.path("usedPercent").asDouble();
			Instant resetsAt = Instant.ofEpochSecond(window.path("resetsAt").asLong());
			latest.add(new RateLimitWindow(Math.clamp(used / PERCENT, 0.0, 1.0), resetsAt));
			if (used >= PERCENT && (exhausted == null || resetsAt.isAfter(exhausted)))
				exhausted = resetsAt;
		}
		setWindows(latest);
		if (!limits.path("rateLimitReachedType").isNull() && !limits.path("rateLimitReachedType").isMissingNode())
			setRejection(new Rejection(exhausted));
	}

	/**
	 * Reads a {@code turn/completed} notification.
	 *
	 * @param params the notification's parameters
	 */
	private void readTurnCompleted(JsonNode params)
	{
		if (!params.path("threadId").asString("").equals(rootThread))
			return;
		String status = params.path("turn").path("status").asString("");
		if (!status.equals("completed") && !status.equals("interrupted"))
			setError("Codex turn finished with status " + status);
	}

	/**
	 * Returns a non-blank string value.
	 *
	 * @param node the JSON node
	 * @return the value, or {@code null} if the node is not a non-blank string
	 */
	private static String text(JsonNode node)
	{
		if (node.isString() && !node.stringValue().isBlank())
			return node.stringValue();
		return null;
	}
}
