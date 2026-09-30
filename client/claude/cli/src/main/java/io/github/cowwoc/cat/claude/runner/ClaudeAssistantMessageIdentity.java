package io.github.cowwoc.cat.claude.runner;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Retains the conversation, message, and model identities reported by a parsed Claude assistant event.
 * A parent tool identifies a subagent message; these fields alone do not establish a completed turn or run profile.
 *
 * @param sessionId native conversation identity
 * @param messageId assistant message identity
 * @param modelId model identity reported by this message
 * @param parentToolUseId tool that started the subagent, absent for a parent-conversation message
 */
record ClaudeAssistantMessageIdentity(String sessionId, String messageId, String modelId,
	Optional<String> parentToolUseId)
{
	/**
	 * Requires nonblank reported identities.
	 *
	 * @param sessionId native conversation identity
	 * @param messageId assistant message identity
	 * @param modelId reported model identity
	 * @param parentToolUseId optional subagent parent tool
	 */
	ClaudeAssistantMessageIdentity
	{
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(messageId, "messageId");
		Objects.requireNonNull(modelId, "modelId");
		Objects.requireNonNull(parentToolUseId, "parentToolUseId");
		if (sessionId.isBlank() || messageId.isBlank() || modelId.isBlank() ||
			parentToolUseId.stream().anyMatch(String::isBlank))
			throw new IllegalArgumentException("Claude assistant identities must not be blank");
	}

	/**
	 * Maps an already parsed native event, preserving parent and subagent identity.
	 * The caller owns JSON parsing and duplicate-field rejection.
	 *
	 * @param event parsed native assistant event
	 * @return identities reported by this message
	 * @throws IOException if the event is not an assistant message or its identities are missing or malformed
	 */
	static ClaudeAssistantMessageIdentity read(JsonNode event) throws IOException
	{
		Objects.requireNonNull(event, "event");
		JsonNode message = event.path("message");
		if (!event.isObject() || !"assistant".equals(event.path("type").asString()) ||
			!message.isObject() || !"assistant".equals(message.path("role").asString()))
			throw new IOException("Claude native event is not an assistant message");
		JsonNode session = event.path("session_id");
		JsonNode id = message.path("id");
		JsonNode model = message.path("model");
		JsonNode parent = event.path("parent_tool_use_id");
		for (JsonNode value : List.of(session, id, model))
			if (!value.isString() || value.asString().isBlank())
				throw new IOException("Claude assistant message requires nonblank textual identities");
		if (!parent.isNull() && (!parent.isString() || parent.asString().isBlank()))
			throw new IOException("Claude assistant parent tool identity must be explicit null or nonblank text");
		Optional<String> parentTool = Optional.empty();
		if (!parent.isNull())
			parentTool = Optional.of(parent.asString());
		return new ClaudeAssistantMessageIdentity(session.asString(), id.asString(), model.asString(), parentTool);
	}
}
