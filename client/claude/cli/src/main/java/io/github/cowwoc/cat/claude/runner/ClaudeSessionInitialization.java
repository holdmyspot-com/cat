package io.github.cowwoc.cat.claude.runner;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the session and model announced by one retained Claude initialization event.
 * Initialization does not establish the model or reasoning settings effective during later turns.
 *
 * @param sessionId native conversation identity
 * @param modelId model announced at session initialization
 * @param nativeVersion optional native CLI version announced by the event
 */
record ClaudeSessionInitialization(String sessionId, String modelId, Optional<String> nativeVersion)
{
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();

	/**
	 * Validates the observed initialization metadata.
	 *
	 * @param sessionId nonblank native conversation identity
	 * @param modelId nonblank announced model identity
	 * @param nativeVersion nonblank native CLI version when available
	 */
	ClaudeSessionInitialization
	{
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(modelId, "modelId");
		Objects.requireNonNull(nativeVersion, "nativeVersion");
		if (sessionId.isBlank() || modelId.isBlank() || nativeVersion.stream().anyMatch(String::isBlank))
			throw new IllegalArgumentException("Claude initialization identity must not be blank");
	}

	/**
	 * Decodes one initialization record, retaining absence of the optional native version.
	 *
	 * @param event exact retained native JSON record
	 * @return native metadata without substituting requested launch settings
	 * @throws IOException if the record is malformed, ambiguous, or not a complete initialization
	 */
	static ClaudeSessionInitialization read(byte[] event) throws IOException
	{
		Objects.requireNonNull(event, "event");
		JsonNode node;
		try
		{
			node = JSON.readTree(event);
		}
		catch (JacksonException exception)
		{
			throw new IOException("Claude initialization JSON is malformed or ambiguous", exception);
		}
		if (node == null || !node.isObject() || !"system".equals(text(node, "type")) ||
			!"init".equals(text(node, "subtype")))
			throw new IOException("Claude native event is not a session initialization");
		Optional<String> version = Optional.empty();
		if (!node.path("claude_code_version").isMissingNode())
			version = Optional.of(text(node, "claude_code_version"));
		return new ClaudeSessionInitialization(text(node, "session_id"), text(node, "model"), version);
	}

	/**
	 * Requires an observed textual identity without coercing another JSON value.
	 *
	 * @param node parsed initialization object
	 * @param field native field to inspect
	 * @return nonblank observed text
	 * @throws IOException if the field is absent, nontextual, or blank
	 */
	private static String text(JsonNode node, String field) throws IOException
	{
		JsonNode value = node.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Claude initialization requires nonblank text for " + field);
		return value.asString();
	}
}
