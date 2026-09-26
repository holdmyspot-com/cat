package io.github.cowwoc.cat.codex;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads per-response output counts from validated Codex rollout versions.
 * The rollout format is internal to Codex, so another version is not accepted without a separate compatibility check.
 */
public final class CodexRolloutUsageReader
{
	private static final JsonFactory JSON = new JsonFactory();

	/**
	 * Reads the verified output available for one root turn from a Codex rollout stream.
	 * The stream is closed before this method returns. An absent usage record leaves the turn pending.
	 *
	 * @param rollout Codex JSONL rollout stream, starting with session metadata
	 * @param rootTurnId root turn whose completed response counts are requested
	 * @return cumulative output and response count, or empty when no response count is available
	 * @throws IOException if the rollout format is unsupported, malformed, or cannot be read
	 */
	public static Optional<TurnUsage> readTurn(InputStream rollout, UUID rootTurnId) throws IOException
	{
		Objects.requireNonNull(rollout, "rollout");
		Objects.requireNonNull(rootTurnId, "rootTurnId");
		Map<String, ResponseUsage> responses = new HashMap<>();
		Map<String, Long> turnTotals = new HashMap<>();
		long outputTokens = 0;
		String sessionId = null;
		CodexMeteringVersion harnessVersion = null;
		try (JsonParser parser = JSON.createParser(ObjectReadContext.empty(), rollout))
		{
			for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken())
			{
				if (token != JsonToken.START_OBJECT)
					throw new IOException("Codex rollout must contain JSON objects");
				RolloutRecord record = readRecord(parser);
				if (sessionId == null)
				{
					if (!"session_meta".equals(record.type()) || record.sessionId() == null ||
						record.sessionId().isBlank())
						throw new IOException("Codex rollout version or session metadata is unsupported");
					try
					{
						harnessVersion = CodexMeteringVersion.fromRuntime(record.cliVersion());
					}
					catch (IllegalArgumentException exception)
					{
						throw new IOException("Codex rollout version or session metadata is unsupported", exception);
					}
					sessionId = record.sessionId();
					continue;
				}
				if (!"token_usage_record".equals(record.type()))
					continue;
				if (!sessionId.equals(record.sessionId()) || record.rootTurnId() == null ||
					record.turnId() == null || record.turnId().isBlank() || record.responseId() == null ||
					record.responseId().isBlank() || record.outputTokens() < 0 || record.turnOutputTokens() < 0)
					throw new IOException("Codex rollout contains an incomplete response usage record");
				if (!rootTurnId.toString().equals(record.rootTurnId()))
					continue;
				ResponseUsage response = new ResponseUsage(record.turnId(), record.outputTokens());
				ResponseUsage previous = responses.putIfAbsent(record.responseId(), response);
				if (previous != null)
				{
					if (!previous.equals(response))
						throw new IOException("Codex rollout has conflicting counts for one response");
					continue;
				}
				try
				{
					long turnTotal = Math.addExact(turnTotals.getOrDefault(record.turnId(), 0L),
						record.outputTokens());
					if (turnTotal != record.turnOutputTokens())
						throw new IOException("Codex response counts do not match its turn total");
					turnTotals.put(record.turnId(), turnTotal);
					outputTokens = Math.addExact(outputTokens, record.outputTokens());
				}
				catch (ArithmeticException exception)
				{
					throw new IOException("Codex turn output exceeds the supported count range", exception);
				}
			}
		}
		if (sessionId == null)
			throw new IOException("Codex rollout has no session metadata");
		if (responses.isEmpty())
			return Optional.empty();
		return Optional.of(new TurnUsage(outputTokens, responses.size(), harnessVersion));
	}

	/**
	 * Reads only metadata and response usage fields from one rollout record.
	 *
	 * @param parser parser positioned at the record's opening object
	 * @return selected record fields
	 * @throws IOException if a required JSON object boundary is malformed
	 */
	private static RolloutRecord readRecord(JsonParser parser) throws IOException
	{
		String type = null;
		RolloutRecord payload = RolloutRecord.empty();
		for (JsonToken token = parser.nextToken(); token != JsonToken.END_OBJECT; token = parser.nextToken())
		{
			if (token != JsonToken.PROPERTY_NAME)
				throw new IOException("Codex rollout record has an invalid field");
			String name = parser.currentName();
			token = parser.nextToken();
			if ("type".equals(name) && token == JsonToken.VALUE_STRING)
				type = parser.getString();
			else if ("payload".equals(name))
			{
				if (type == null)
					throw new IOException("Codex rollout record has payload before its type");
				if (("session_meta".equals(type) || "token_usage_record".equals(type)) &&
					token == JsonToken.START_OBJECT)
					payload = readPayload(parser, type);
				else
					parser.skipChildren();
			}
			else
				parser.skipChildren();
		}
		return new RolloutRecord(type, payload.sessionId(), payload.cliVersion(), payload.rootTurnId(),
			payload.turnId(), payload.responseId(), payload.outputTokens(), payload.turnOutputTokens());
	}

	/**
	 * Reads the selected fields of a session or response-usage payload.
	 *
	 * @param parser parser positioned at the payload's opening object
	 * @param type outer record type
	 * @return selected payload fields
	 * @throws IOException if a required JSON object boundary is malformed
	 */
	private static RolloutRecord readPayload(JsonParser parser, String type) throws IOException
	{
		String sessionId = null;
		String cliVersion = null;
		String rootTurnId = null;
		String turnId = null;
		String responseId = null;
		long outputTokens = -1;
		long turnOutputTokens = -1;
		for (JsonToken token = parser.nextToken(); token != JsonToken.END_OBJECT; token = parser.nextToken())
		{
			if (token != JsonToken.PROPERTY_NAME)
				throw new IOException("Codex rollout payload has an invalid field");
			String name = parser.currentName();
			token = parser.nextToken();
			if ("session_id".equals(name) && token == JsonToken.VALUE_STRING)
				sessionId = parser.getString();
			else if ("cli_version".equals(name) && token == JsonToken.VALUE_STRING)
				cliVersion = parser.getString();
			else if ("root_turn_id".equals(name) && token == JsonToken.VALUE_STRING)
				rootTurnId = parser.getString();
			else if ("turn_id".equals(name) && token == JsonToken.VALUE_STRING)
				turnId = parser.getString();
			else if ("response_id".equals(name) && token == JsonToken.VALUE_STRING)
				responseId = parser.getString();
			else if ("token_usage_record".equals(type) && "usage".equals(name) && token == JsonToken.START_OBJECT)
				outputTokens = readOutputTokens(parser);
			else if ("token_usage_record".equals(type) && "turn_token_usage".equals(name) &&
				token == JsonToken.START_OBJECT)
				turnOutputTokens = readOutputTokens(parser);
			else
				parser.skipChildren();
		}
		return new RolloutRecord(type, sessionId, cliVersion, rootTurnId, turnId, responseId,
			outputTokens, turnOutputTokens);
	}

	/**
	 * Reads the integer output count without materializing unrelated usage fields.
	 *
	 * @param parser parser positioned at the usage object's opening token
	 * @return output count, or -1 when it is missing or not an integer
	 * @throws IOException if the usage object is malformed
	 */
	private static long readOutputTokens(JsonParser parser) throws IOException
	{
		long outputTokens = -1;
		for (JsonToken token = parser.nextToken(); token != JsonToken.END_OBJECT; token = parser.nextToken())
		{
			if (token != JsonToken.PROPERTY_NAME)
				throw new IOException("Codex response usage has an invalid field");
			String name = parser.currentName();
			token = parser.nextToken();
			if ("output_tokens".equals(name) && token == JsonToken.VALUE_NUMBER_INT)
				outputTokens = parser.getLongValue();
			else
				parser.skipChildren();
		}
		return outputTokens;
	}

	/**
	 * Cumulative generated output observed for one root turn.
	 *
	 * @param outputTokens sum of unique response output counts
	 * @param responseCount number of unique responses counted
	 * @param harnessVersion validated Codex runtime version
	 */
	public record TurnUsage(long outputTokens, int responseCount, CodexMeteringVersion harnessVersion)
	{
		/**
		 * Validates the count returned to the outbox.
		 *
		 * @param outputTokens sum of unique response output counts
		 * @param responseCount number of unique responses counted
		 * @param harnessVersion validated Codex runtime version
		 * @throws IllegalArgumentException if a count is invalid
		 */
		public TurnUsage
		{
			Objects.requireNonNull(harnessVersion, "harnessVersion");
			if (outputTokens < 0 || responseCount < 1)
				throw new IllegalArgumentException("Turn usage requires nonnegative output and a response");
		}
	}

	/**
	 * Fields used from one Codex rollout record.
	 *
	 * @param type outer record type
	 * @param sessionId Codex session ID
	 * @param cliVersion Codex CLI version for session metadata
	 * @param rootTurnId root turn ID for response usage
	 * @param turnId concrete turn ID for the response
	 * @param responseId unique model response ID
	 * @param outputTokens generated output count, or -1 if unavailable
	 * @param turnOutputTokens cumulative output for the concrete turn, or -1 if unavailable
	 */
	private record RolloutRecord(String type, String sessionId, String cliVersion, String rootTurnId,
		String turnId, String responseId, long outputTokens, long turnOutputTokens)
	{
		/**
		 * Creates an empty field selection for a record whose payload is irrelevant.
		 *
		 * @return empty selection
		 */
		private static RolloutRecord empty()
		{
			return new RolloutRecord(null, null, null, null, null, null, -1, -1);
		}
	}

	/**
	 * Identity and count of one response already observed in the rollout.
	 *
	 * @param turnId concrete turn ID
	 * @param outputTokens output count of that response
	 */
	private record ResponseUsage(String turnId, long outputTokens)
	{
	}

	/**
	 * Prevents construction of this utility class.
	 */
	private CodexRolloutUsageReader()
	{
	}
}
