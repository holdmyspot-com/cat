package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materializes the JSONL prompt input consumed by a typed harness runner.
 */
public final class PromptInput
{
	/**
	 * Prevents construction of this static input materializer.
	 */
	private PromptInput()
	{
	}

	/**
	 * Creates one prompt turn followed by explicit completion for a validator or reviewer.
	 *
	 * @param json the lifecycle-owned JSON mapper that constructs and serializes bridge messages
	 * @param prompt the complete prompt
	 * @return the JSONL input
	 * @throws IOException if input cannot be serialized
	 */
	public static String forPrompt(JsonMapper json, String prompt) throws IOException
	{
		requireThat(json, "json").isNotNull();

		StringBuilder result = new StringBuilder();
		append(json, result, json.createObjectNode().put("type", "turn").put("prompt", prompt));
		append(json, result, json.createObjectNode().put("type", "complete"));
		return result.toString();
	}

	/**
	 * Appends one serialized bridge message.
	 *
	 * @param json the lifecycle-owned JSON mapper that serializes the bridge message
	 * @param target the JSONL target
	 * @param message the bridge message
	 * @throws IOException if serialization fails
	 */
	private static void append(JsonMapper json, StringBuilder target, ObjectNode message) throws IOException
	{
		target.append(json.writeValueAsString(message)).append(System.lineSeparator());
	}
}
