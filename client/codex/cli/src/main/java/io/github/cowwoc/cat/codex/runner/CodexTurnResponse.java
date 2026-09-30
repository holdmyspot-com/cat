package io.github.cowwoc.cat.codex.runner;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Selects the final answer published in a native Codex turn. */
final class CodexTurnResponse
{
	/**
	 * Reads the last final answer, preserving the native producer's empty response when none is published.
	 *
	 * @param turn native turn object from a turn/completed notification
	 * @return published final answer or an empty string
	 * @throws NullPointerException if the turn is null
	 */
	static String read(JsonNode turn)
	{
		Objects.requireNonNull(turn, "turn");
		String response = "";
		for (JsonNode item : turn.path("items"))
		{
			if ("agentMessage".equals(item.path("type").asString()) &&
				"final_answer".equals(item.path("phase").asString()))
				response = item.path("text").asString();
		}
		return response;
	}

	/** Prevents construction of utility class instances. */
	private CodexTurnResponse()
	{
	}
}
