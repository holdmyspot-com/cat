package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Creates the JSON lines that Claude's stream-json CLI accepts as requests.
 */
public final class ClaudeStreamingProtocol
{
	/**
	 * Frames one user turn as a stream-json line.
	 *
	 * @param prompt the non-blank user prompt
	 * @return the JSONL request line
	 * @throws NullPointerException if {@code prompt} is null
	 * @throws IllegalArgumentException if {@code prompt} is blank
	 */
	public static String userTurn(String prompt)
	{
		requireThat(prompt, "prompt").isNotBlank();
		return "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"" +
			escape(prompt) + "\"}}\n";
	}

	/**
	 * Frames a request to interrupt the turn in progress as a stream-json line. Claude answers it with a
	 * {@code control_response} line that carries the same identifier.
	 *
	 * @param requestId the non-blank identifier of the request
	 * @return the JSONL request line
	 * @throws NullPointerException if {@code requestId} is null
	 * @throws IllegalArgumentException if {@code requestId} is blank
	 */
	public static String interrupt(String requestId)
	{
		requireThat(requestId, "requestId").isNotBlank();
		return "{\"type\":\"control_request\",\"request_id\":\"" + escape(requestId) +
			"\",\"request\":{\"subtype\":\"interrupt\"}}\n";
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private ClaudeStreamingProtocol()
	{
	}

	/**
	 * Escapes a JSON string value.
	 *
	 * @param value a non-null value
	 * @return the escaped value
	 */
	private static String escape(String value)
	{
		assert value != null;
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
	}
}
