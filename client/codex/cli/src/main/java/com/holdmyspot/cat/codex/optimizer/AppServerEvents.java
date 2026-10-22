package com.holdmyspot.cat.codex.optimizer;

import com.holdmyspot.cat.common.optimizer.adapter.HarnessEvents;
import com.holdmyspot.cat.common.optimizer.contract.ToolCall;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;
import com.holdmyspot.cat.codex.secrets.AppServerEventsAccess;
import com.holdmyspot.cat.codex.secrets.AppServerEventsSecrets;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a session's recorded Codex app-server activity and its model, token usage, rate limits and errors.
 * <p>
 * The parser follows the notifications that the runner captured:
 * <ul>
 *   <li>{@code thread/started} names the root thread, its model and its reasoning effort; an archive without thread
 *   initialization names the root in its first {@code turn/started} notification;</li>
 *   <li>{@code thread/settings/updated} reports the model and reasoning effort that the thread's turn runs at,
 *   which can differ from the defaults in {@code thread/started}. For the root thread, each non-blank value
 *   replaces the one observed so far; the notification of another thread changes nothing. When no root is known
 *   yet, the notification names the root;</li>
 *   <li>{@code thread/tokenUsage/updated} carries each thread's cumulative {@code total}, and the session's usage is
 *   the sum over every thread, so that child threads count;</li>
 *   <li>{@code account/rateLimits/updated} carries the {@code primary} and {@code secondary} windows, which are
 *   reported in that order;</li>
 *   <li>{@code item/started} records an activity's position, and {@code item/completed} updates that same item;
 *   root and child tools retain their complete input and output, while root-call accounting excludes child calls;</li>
 *   <li>hook notifications retain only their status, and root assistant items retain only response positions;</li>
 *   <li>model output in a turn is a reasoning, reply or tool item, an {@code item/agentMessage/delta}, or reported
 *   token usage. A user-message item, a rate-limit update and a thread or turn status are not output. A
 *   {@code hook/started} notification marks a hook as running until its {@code hook/completed} notification;</li>
 *   <li>a {@code turn/completed} notification of the root thread whose status is neither {@code completed} nor
 *   {@code interrupted} reports an error, without Codex's error text;</li>
 *   <li>an {@code error} notification whose {@code willRetry} is {@code false} ends Codex's attempts at a request.
 *   When its {@code codexErrorInfo} holds {@code httpStatusCode} 403 under any key, the provider refused the request;
 *   when it holds 429, the provider rejected it because of a rate limit, without a reset time. Codex retries an error
 *   whose {@code willRetry} is {@code true}, so that error reports nothing.</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class AppServerEvents extends HarnessEvents
{
	static
	{
		AppServerEventsSecrets.setAccess(new AppServerEventsAccess()
		{
			@Override
			public AppServerEvents newAppServerEvents(JsonMapper json)
			{
				return new AppServerEvents(json);
			}
		});
	}

	private static final double PERCENT = 100.0;
	/**
	 * The HTTP status of a provider refusal.
	 */
	private static final int HTTP_FORBIDDEN = 403;
	/**
	 * The HTTP status of a rate-limit rejection.
	 */
	private static final int HTTP_TOO_MANY_REQUESTS = 429;
	/**
	 * The item types that only the model produces.
	 */
	private static final Set<String> MODEL_ITEMS = Set.of("reasoning", "agentMessage", "plan", "commandExecution",
		"collabAgentToolCall", "dynamicToolCall", "mcpToolCall", "fileChange", "webSearch", "imageView", "sleep",
		"imageGeneration");
	private final Map<String, TokenUsage> totals = new HashMap<>();
	private final Map<String, ToolCall> calls = new LinkedHashMap<>();
	private final AppServerEvidence evidence = new AppServerEvidence();
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
			case "thread/settings/updated" -> readThreadSettings(params);
			case "thread/tokenUsage/updated" -> readTokenUsage(params);
			case "account/rateLimits/updated" -> readRateLimits(params.path("rateLimits"));
			case "turn/started" -> readTurnStarted(params);
			case "turn/completed" -> readTurnCompleted(params);
			case "error" -> readError(params);
			case "item/started", "item/completed" -> readItem(params);
			case "item/agentMessage/delta" -> setOutputProduced();
			case "hook/started", "hook/completed" ->
			{
				readHookRunning(event.path("method").asString("").equals("hook/started"), params.path("run"));
				evidence.readHook(params, rootThread);
				setEvidence(evidence.snapshot());
			}
			default ->
			{
			}
		}
	}

	/**
	 * Records an activity item once, updating its completion without changing its invocation order. Only root tool
	 * calls contribute to the session's root-call accounting.
	 *
	 * @param params the notification's parameters, including the owning thread and tool item
	 */
	private void readItem(JsonNode params)
	{
		if (MODEL_ITEMS.contains(params.path("item").path("type").asString("")))
			setOutputProduced();
		if (rootThread == null || text(params.path("threadId")) == null)
			return;
		JsonNode item = params.path("item");
		String id = text(item.path("id"));
		if (id == null)
			return;
		String type = item.path("type").asString("");
		evidence.readActivity(params, rootThread);
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
		{
			setEvidence(evidence.snapshot());
			return;
		}

		evidence.readTool(params, rootThread, call);
		setEvidence(evidence.snapshot());
		if (params.path("threadId").asString("").equals(rootThread))
		{
			calls.put(id, call);
			setToolCalls(new ArrayList<>(calls.values()));
		}
	}

	/**
	 * Records that a hook started or completed.
	 *
	 * @param started {@code true} for a {@code hook/started} notification, {@code false} for a
	 *   {@code hook/completed} notification
	 * @param run the notification's {@code run} object
	 */
	private void readHookRunning(boolean started, JsonNode run)
	{
		String id = text(run.path("id"));
		if (id == null)
			id = text(run.path("eventName"));
		if (id == null)
			return;
		if (started)
			setHookStarted(id);
		else
			setHookCompleted(id);
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
		String id = text(thread.path("id"));
		if (id == null || rootThread != null && !rootThread.equals(id))
			return;
		rootThread = id;
		setModel(text(thread.path("model")));
		setReasoning(text(thread.path("reasoningEffort")));
		setModelProvider(text(thread.path("modelProvider")));
	}

	/**
	 * Reads a {@code thread/settings/updated} notification. The root thread's settings replace the model, reasoning
	 * effort and model provider that {@code thread/started} reported, because they are the values that the turn runs at.
	 *
	 * @param params the notification's parameters
	 */
	private void readThreadSettings(JsonNode params)
	{
		String id = text(params.path("threadId"));
		if (id == null)
			return;
		if (rootThread == null)
			rootThread = id;
		if (!rootThread.equals(id))
			return;
		JsonNode settings = params.path("threadSettings");
		String model = text(settings.path("model"));
		if (model != null)
			setModel(model);
		String effort = text(settings.path("effort"));
		if (effort != null)
			setReasoning(effort);
		String provider = text(settings.path("modelProvider"));
		if (provider != null)
			setModelProvider(provider);
	}

	/**
	 * Names the root from its first turn when the archive omits thread initialization, and records turn ordering.
	 *
	 * @param params the turn-start notification's parameters
	 */
	private void readTurnStarted(JsonNode params)
	{
		if (rootThread == null)
			rootThread = text(params.path("threadId"));
		evidence.readTurn(params, rootThread);
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
		setOutputProduced();
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
	 * Reads an {@code error} notification.
	 *
	 * @param params the notification's parameters
	 */
	private void readError(JsonNode params)
	{
		JsonNode willRetry = params.path("willRetry");
		if (!willRetry.isBoolean() || willRetry.booleanValue())
			return;
		// Codex names the failure kind as the key, such as httpConnectionFailed or responseStreamDisconnected
		for (JsonNode info : params.path("error").path("codexErrorInfo"))
		{
			switch (info.path("httpStatusCode").asInt(0))
			{
				case HTTP_FORBIDDEN -> setRefused();
				case HTTP_TOO_MANY_REQUESTS ->
				{
					if (rejection().isEmpty())
						setRejection(new Rejection(null));
				}
				default ->
				{
				}
			}
		}
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
