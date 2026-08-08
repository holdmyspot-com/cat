package io.github.cowwoc.cat.claude.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.claude.runner.ClaudeStreamingProtocol;
import org.testng.annotations.Test;

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
}
