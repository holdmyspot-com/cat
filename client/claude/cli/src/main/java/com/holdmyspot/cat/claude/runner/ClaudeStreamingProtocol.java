package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates the JSON lines that Claude's stream-json CLI accepts as requests.
 */
public final class ClaudeStreamingProtocol
{
	/**
	 * Writes each request line, escaping every character that a JSON string cannot hold as is, such as a tab.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

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
		ObjectNode request = JSON.createObjectNode().put("type", "user");
		request.putObject("message").put("role", "user").put("content", prompt);
		return JSON.writeValueAsString(request) + "\n";
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
		ObjectNode request = JSON.createObjectNode().put("type", "control_request").put("request_id", requestId);
		request.putObject("request").put("subtype", "interrupt");
		return JSON.writeValueAsString(request) + "\n";
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private ClaudeStreamingProtocol()
	{
	}
}
