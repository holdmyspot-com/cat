package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.port.SessionResult;

import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Extracts the JSON object that a judge or contract author session returns. Claude was observed wrapping judge JSON in
 * Markdown code fences, while Codex returned bare objects.
 * <p>
 * The output is the session's final response when that response is exactly one JSON object, either bare or alone
 * inside one Markdown code fence. Any other final response has no output: prose around the object, two objects, an
 * object with a repeated property name, or a value that is not an object.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class JsonResponse
{
	private final JsonMapper json;

	/**
	 * Creates an extractor.
	 *
	 * @param json the operation-owned JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 */
	public JsonResponse(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
	}

	/**
	 * Returns the JSON object of a session's final response.
	 *
	 * @param result the session's result
	 * @return the object, or empty if the session gave no response or its final response is not exactly one JSON
	 *   object, bare or alone inside one Markdown code fence
	 * @throws NullPointerException if {@code result} is null
	 */
	public Optional<ObjectNode> parseFinalResponse(SessionResult.Completed result)
	{
		requireThat(result, "result").isNotNull();
		if (result.responses().isEmpty() || result.responses().getLast().isEmpty())
			return Optional.empty();
		String response = result.responses().getLast().getLast().strip();
		if (response.startsWith("{"))
			return parseObject(response);
		return fenceBody(response).flatMap(this::parseObject);
	}

	/**
	 * Returns the body of a response that is one Markdown code fence and nothing else.
	 *
	 * @param response the stripped response
	 * @return the text between the opening and closing fence lines, or empty if the response is not exactly one
	 *   fence
	 */
	private static Optional<String> fenceBody(String response)
	{
		int firstLineEnd = response.indexOf('\n');
		if (firstLineEnd == -1)
			return Optional.empty();
		int lastLineStart = response.lastIndexOf('\n');
		String opening = response.substring(0, firstLineEnd);
		char marker = opening.charAt(0);
		if (marker != '`' && marker != '~')
			return Optional.empty();
		int length = 0;
		while (length < opening.length() && opening.charAt(length) == marker)
			++length;
		if (length < 3)
			return Optional.empty();
		// A backtick fence's info string cannot contain a backtick
		if (marker == '`' && opening.indexOf('`', length) != -1)
			return Optional.empty();
		String closing = response.substring(lastLineStart + 1);
		if (closing.length() < length || !closing.chars().allMatch(c -> c == marker))
			return Optional.empty();
		if (lastLineStart == firstLineEnd)
			return Optional.of("");
		return Optional.of(response.substring(firstLineEnd + 1, lastLineStart));
	}

	/**
	 * Parses text that must be exactly one JSON object.
	 *
	 * @param text the text
	 * @return the object, or empty if the text is not exactly one JSON object or repeats a property name
	 */
	private Optional<ObjectNode> parseObject(String text)
	{
		try
		{
			JsonNode node = json.readerFor(JsonNode.class).
				with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
				with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).
				readValue(text);
			if (node instanceof ObjectNode object)
				return Optional.of(object);
			return Optional.empty();
		}
		catch (JacksonException _)
		{
			return Optional.empty();
		}
	}
}
