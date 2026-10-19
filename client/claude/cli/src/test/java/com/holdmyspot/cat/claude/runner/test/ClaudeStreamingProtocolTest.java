package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeStreamingProtocol;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link ClaudeStreamingProtocol}.
 */
public final class ClaudeStreamingProtocolTest
{
	/**
	 * Verifies a user turn is framed as one JSONL request.
	 */
	@Test
	public void framesUserTurn()
	{
		requireThat(ClaudeStreamingProtocol.userTurn("hello"), "request").isEqualTo(
			"{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}}\n");
	}

	/**
	 * Verifies that a prompt holding control characters, such as the tabs that indent a Markdown list, is framed as
	 * valid JSON that carries the prompt unchanged.
	 */
	@Test
	public void framesControlCharacters()
	{
		String prompt = "- item\n\t- nested\r\n\u0001 \"quoted\" \\ end";
		String request = ClaudeStreamingProtocol.userTurn(prompt);

		requireThat(request, "request").endsWith("\n");
		JsonNode content = JsonMapper.builder().build().readTree(request).path("message").path("content");
		requireThat(content.asString(), "content").isEqualTo(prompt);
	}
}
