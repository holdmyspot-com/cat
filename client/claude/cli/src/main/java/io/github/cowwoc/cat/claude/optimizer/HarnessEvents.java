package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads what a session's Claude harness events say about the model, token usage, rate limits and errors.
 * <p>
 * The parser follows the records that the runner captured (design &sect;11, H14 and H15):
 * <ul>
 *   <li>the {@code init} record names the resolved model, and its {@code effort} when it has one;</li>
 *   <li>{@code rate_limit_event} records carry the {@code five_hour} and {@code seven_day} windows;</li>
 *   <li>a {@code result} record's {@code modelUsage} is cumulative over the session and includes the usage of
 *   background children, unlike its per-reply {@code usage} object.</li>
 * </ul>
 * Unrecognized lines are ignored.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class HarnessEvents
{
	private static final String RATE_LIMITED_STATUS = "rejected";
	private static final int RATE_LIMITED_ERROR = 429;
	private final JsonMapper json;
	private final Object lock = new Object();
	private final Map<String, RateLimitWindow> windows = new LinkedHashMap<>();
	private String model;
	private String reasoning;
	private TokenUsage usage;
	private Instant lastResetsAt;
	private Rejection rejection;
	private String error;

	/**
	 * Creates a parser that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 */
	HarnessEvents(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
	}

	/**
	 * Absorbs one harness event line.
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
			String type = event.path("type").asString("");
			if (type.equals("system") && event.path("subtype").asString("").equals("init"))
				readInit(event);
			else if (type.equals("rate_limit_event"))
				readRateLimit(event.path("rate_limit_info"));
			else if (type.equals("result"))
				readResult(event);
		}
	}

	/**
	 * Returns the model that the session's {@code init} record named.
	 *
	 * @return the model, or {@code null} if no record named one
	 */
	String model()
	{
		synchronized (lock)
		{
			return model;
		}
	}

	/**
	 * Returns the reasoning effort that the session's {@code init} record named.
	 *
	 * @return the effort, or {@code null} if no record named one. Claude Code 2.1.286 reports it as {@code null}.
	 */
	String reasoning()
	{
		synchronized (lock)
		{
			return reasoning;
		}
	}

	/**
	 * Returns the session's cumulative token usage.
	 *
	 * @return the usage, or {@code null} if no {@code result} record reported any
	 */
	TokenUsage usage()
	{
		synchronized (lock)
		{
			return usage;
		}
	}

	/**
	 * Returns the latest rate-limit windows.
	 *
	 * @return the windows, in the order that the events first reported them
	 */
	List<RateLimitWindow> windows()
	{
		synchronized (lock)
		{
			return List.copyOf(windows.values());
		}
	}

	/**
	 * Returns the rate-limit rejection that the events reported.
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
	 * Returns the error that a {@code result} record reported, other than a rate-limit rejection.
	 *
	 * @return a description of the error that omits the result text, or {@code null} if no record reported an error
	 */
	String error()
	{
		synchronized (lock)
		{
			return error;
		}
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
			model = modelNode.stringValue();
		JsonNode effort = event.path("effort");
		if (effort.isString() && !effort.stringValue().isBlank())
			reasoning = effort.stringValue();
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
		}
		if (info.path("status").asString("").equals(RATE_LIMITED_STATUS))
			rejection = new Rejection(resetsAt);
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
			usage = new TokenUsage(input, cached, output, reasoningOutput);
		}
		if (!event.path("is_error").asBoolean(false))
			return;
		JsonNode status = event.path("api_error_status");
		if (status.isNumber() && status.asInt() == RATE_LIMITED_ERROR)
		{
			if (rejection == null)
				rejection = new Rejection(lastResetsAt);
			return;
		}
		String statusText = "none";
		if (!status.isNull() && !status.isMissingNode())
			statusText = status.asString("none");
		error = "Claude reported an error result (api_error_status=" + statusText + ", terminal_reason=" +
			event.path("terminal_reason").asString("none") + ")";
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

	/**
	 * A rate-limit rejection.
	 *
	 * @param resetsAt when the provider accepts requests again, or {@code null} if the events did not say
	 */
	record Rejection(Instant resetsAt)
	{
	}
}
