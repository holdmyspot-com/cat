package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads what a session's native Codex events say about the model, token usage, rate limits and errors.
 * <p>
 * The parser follows the notifications that the runner captured (design &sect;11, H14 and H15):
 * <ul>
 *   <li>the first {@code thread/started} notification names the root thread, its model and its reasoning effort;</li>
 *   <li>{@code thread/tokenUsage/updated} carries each thread's cumulative {@code total}, and the session's usage is
 *   the sum over every thread, so that child threads count;</li>
 *   <li>{@code account/rateLimits/updated} carries the {@code primary} and {@code secondary} windows.</li>
 * </ul>
 * Unrecognized lines are ignored.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class NativeEvents
{
	private static final double PERCENT = 100.0;
	private final JsonMapper json;
	private final Object lock = new Object();
	private final Map<String, TokenUsage> totals = new HashMap<>();
	private List<RateLimitWindow> windows = List.of();
	private String rootThread;
	private String model;
	private String reasoning;
	private Rejection rejection;
	private String error;

	/**
	 * Creates a parser that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 */
	NativeEvents(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
	}

	/**
	 * Absorbs one native event line.
	 *
	 * @param line the line. A line that is not a JSON object is ignored.
	 * @throws NullPointerException if {@code line} is null
	 */
	void accept(String line)
	{
		requireThat(line, "line").isNotNull();
		if (!line.startsWith("{"))
			return;
		JsonNode event;
		try
		{
			event = json.readTree(line);
		}
		catch (JacksonException _)
		{
			return;
		}
		if (!event.isObject())
			return;
		synchronized (lock)
		{
			JsonNode params = event.path("params");
			switch (event.path("method").asString(""))
			{
				case "thread/started" -> readThreadStarted(params.path("thread"));
				case "thread/tokenUsage/updated" -> readTokenUsage(params);
				case "account/rateLimits/updated" -> readRateLimits(params.path("rateLimits"));
				case "turn/completed" -> readTurnCompleted(params);
				default ->
				{
				}
			}
		}
	}

	/**
	 * Returns the model that the root thread started with.
	 *
	 * @return the model, or {@code null} if no notification named one
	 */
	String model()
	{
		synchronized (lock)
		{
			return model;
		}
	}

	/**
	 * Returns the reasoning effort that the root thread started with.
	 *
	 * @return the effort, or {@code null} if no notification named one
	 */
	String reasoning()
	{
		synchronized (lock)
		{
			return reasoning;
		}
	}

	/**
	 * Returns the session's token usage.
	 *
	 * @return the usage summed over every thread, or {@code null} if no notification reported any
	 */
	TokenUsage usage()
	{
		synchronized (lock)
		{
			if (totals.isEmpty())
				return null;
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
			return new TokenUsage(input, cached, output, reasoningOutput);
		}
	}

	/**
	 * Returns the latest rate-limit windows.
	 *
	 * @return the windows, {@code primary} first
	 */
	List<RateLimitWindow> windows()
	{
		synchronized (lock)
		{
			return windows;
		}
	}

	/**
	 * Returns the rate-limit rejection that the notifications reported.
	 *
	 * @return the rejection, or empty if none was reported
	 */
	Optional<Rejection> rejection()
	{
		synchronized (lock)
		{
			return Optional.ofNullable(rejection);
		}
	}

	/**
	 * Returns the error that the root thread's turn ended with.
	 *
	 * @return a description of the error that omits Codex's error text, or {@code null} if no turn failed
	 */
	String error()
	{
		synchronized (lock)
		{
			return error;
		}
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
		model = text(thread.path("model"));
		reasoning = text(thread.path("reasoningEffort"));
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
		windows = List.copyOf(latest);
		if (!limits.path("rateLimitReachedType").isNull() && !limits.path("rateLimitReachedType").isMissingNode())
			rejection = new Rejection(exhausted);
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
			error = "Codex turn finished with status " + status;
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

	/**
	 * A rate-limit rejection.
	 *
	 * @param resetsAt when the provider accepts requests again, or {@code null} if the events did not say
	 */
	record Rejection(Instant resetsAt)
	{
	}
}
