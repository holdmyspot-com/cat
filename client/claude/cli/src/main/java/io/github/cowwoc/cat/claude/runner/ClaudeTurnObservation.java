package io.github.cowwoc.cat.claude.runner;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Joins parent-conversation messages with one terminal Claude result.
 *
 * @param sessionId native parent conversation
 * @param resultId native terminal result identity
 * @param terminalEventOrdinal one-based terminal position within this turn's records
 * @param status observed native settlement
 * @param reportedModelId model reported by parent assistant messages, when available
 * @param response successful terminal response, absent for a failure
 */
record ClaudeTurnObservation(String sessionId, String resultId, long terminalEventOrdinal, Status status,
	Optional<String> reportedModelId, Optional<String> response)
{
	/** Native settlement established by a result record. */
	enum Status
	{
		COMPLETED,
		FAILED
	}

	/**
	 * Observes one turn from records after initialization, without substituting launch settings.
	 * The caller owns parsing, duplicate-field rejection, record retention, and turn-file boundaries.
	 *
	 * @param initialization decoded native session initialization
	 * @param events parsed records for one turn, in native order, excluding initialization
	 * @return parent message identity joined to the terminal native result
	 * @throws IOException if identity or settlement cannot be established
	 */
	static ClaudeTurnObservation read(ClaudeSessionInitialization initialization, List<JsonNode> events)
		throws IOException
	{
		Objects.requireNonNull(initialization, "initialization");
		events = List.copyOf(events);
		String model = null;
		ClaudeTurnObservation terminal = null;
		for (int index = 0; index < events.size(); ++index)
		{
			if (terminal != null)
				throw new IOException("Claude turn contains records after its terminal result");
			JsonNode event = events.get(index);
			if (!event.isObject() || !event.path("type").isString() || event.path("type").asString().isBlank())
				throw new IOException("Claude native event requires an object with a textual type");
			switch (event.path("type").asString())
			{
				case "assistant" ->
				{
					ClaudeAssistantMessageIdentity identity = ClaudeAssistantMessageIdentity.read(event);
					if (identity.parentToolUseId().isEmpty())
					{
						if (!identity.sessionId().equals(initialization.sessionId()))
							throw new IOException("Claude parent message belongs to another native session");
						if (model != null && !model.equals(identity.modelId()))
							throw new IOException("Claude parent messages report different models within one turn");
						model = identity.modelId();
					}
				}
				case "result" -> terminal = readResult(event, initialization.sessionId(), index + 1L,
					Optional.ofNullable(model));
				case "system" ->
				{
					if (event.path("subtype").asString().equals("init"))
						throw new IOException("Claude turn records must follow their decoded initialization");
				}
				default ->
				{
					// Other native records remain with the caller; they do not establish model identity or settlement.
				}
			}
		}
		if (terminal == null)
			throw new IOException("Claude turn has no terminal result");
		return terminal;
	}

	/**
	 * Requires a terminal result in the same parent session and classifies its published native subtype.
	 *
	 * @param event native result record
	 * @param sessionId initialized parent session
	 * @param ordinal one-based record position
	 * @param model model reported by preceding parent messages
	 * @return terminal observation
	 * @throws IOException if identity, status, or a successful response is unproven
	 */
	private static ClaudeTurnObservation readResult(JsonNode event, String sessionId, long ordinal,
		Optional<String> model) throws IOException
	{
		JsonNode resultId = event.path("uuid");
		JsonNode session = event.path("session_id");
		JsonNode subtype = event.path("subtype");
		JsonNode error = event.path("is_error");
		for (JsonNode value : List.of(resultId, session, subtype))
			if (!value.isString() || value.asString().isBlank())
				throw new IOException("Claude terminal result requires nonblank textual identities and subtype");
		if (!session.asString().equals(sessionId))
			throw new IOException("Claude terminal result belongs to another native session");
		if (!error.isBoolean())
			throw new IOException("Claude terminal result requires a boolean error flag");
		Status status = switch (subtype.asString())
		{
			case "success" -> Status.COMPLETED;
			case "error_during_execution", "error_max_turns", "error_max_budget_usd",
				"error_max_structured_output_retries" -> Status.FAILED;
			default -> throw new IOException("Claude terminal result has an unsupported subtype: " + subtype.asString());
		};
		if (ClaudeResultDisposition.read(event) != ClaudeResultDisposition.COMPLETED)
			status = Status.FAILED;
		Optional<String> response = Optional.empty();
		if (status == Status.COMPLETED)
		{
			if (model.isEmpty() || !event.path("result").isString())
				throw new IOException("Successful Claude turn requires parent model and textual response evidence");
			response = Optional.of(event.path("result").asString());
		}
		return new ClaudeTurnObservation(sessionId, resultId.asString(), ordinal, status, model, response);
	}
}
