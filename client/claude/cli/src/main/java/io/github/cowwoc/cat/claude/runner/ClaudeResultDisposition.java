package io.github.cowwoc.cat.claude.runner;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Classifies a native result without inferring whether a cancellation signal was delivered. */
enum ClaudeResultDisposition
{
	/** The native result explicitly reports successful completion. */
	COMPLETED,
	/** The native stream was aborted; interruption additionally requires the runner's signal acknowledgement. */
	ABORTED_STREAMING,
	/** The native result does not establish successful completion. */
	FAILED;

	/**
	 * Uses the native subtype, explicit error flag, and terminal reason for every result consumer.
	 * Identity, response, and supported-schema validation remain with the consuming observation boundary.
	 *
	 * @param result parsed native result object
	 * @return reported disposition without a dispatch, retry, or semantic-outcome claim
	 */
	static ClaudeResultDisposition read(JsonNode result)
	{
		Objects.requireNonNull(result, "result");
		JsonNode reason = result.path("terminal_reason");
		if (reason.asString().equals("aborted_streaming"))
			return ABORTED_STREAMING;
		JsonNode error = result.path("is_error");
		if (result.path("subtype").asString().equals("success") && error.isBoolean() && !error.asBoolean() &&
			(reason.isMissingNode() || reason.asString().equals("completed")))
			return COMPLETED;
		return FAILED;
	}
}
