package io.github.cowwoc.cat.claude.optimizer;

import io.github.cowwoc.cat.common.optimizer.adapter.HarnessEvents;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads what a session's Claude {@code stream-json} records say about the model, token usage, rate limits and
 * errors.
 * <p>
 * The parser follows the records that the runner captured (design &sect;11, H14 and H15):
 * <ul>
 *   <li>the {@code init} record names the resolved model, and its {@code effort} when it has one. Claude Code
 *   2.1.286 reports the effort as {@code null};</li>
 *   <li>{@code rate_limit_event} records carry the {@code five_hour} and {@code seven_day} windows, which are
 *   reported in the order that the records first named them;</li>
 *   <li>a {@code result} record's {@code modelUsage} is cumulative over the session and includes the usage of
 *   background children, unlike its per-reply {@code usage} object.</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class StreamJsonEvents extends HarnessEvents
{
	private static final String RATE_LIMITED_STATUS = "rejected";
	private static final int RATE_LIMITED_ERROR = 429;
	private final Map<String, RateLimitWindow> windows = new LinkedHashMap<>();
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
		String type = event.path("type").asString("");
		if (type.equals("system") && event.path("subtype").asString("").equals("init"))
			readInit(event);
		else if (type.equals("rate_limit_event"))
			readRateLimit(event.path("rate_limit_info"));
		else if (type.equals("result"))
			readResult(event);
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
