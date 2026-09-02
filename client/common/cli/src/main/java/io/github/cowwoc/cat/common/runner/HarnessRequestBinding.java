package io.github.cowwoc.cat.common.runner;

import java.io.IOException;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies that a harness-specific request factory preserved operation-owned model input.
 */
public final class HarnessRequestBinding
{
	/**
	 * Requires a request to contain exactly one supplied prompt turn followed by completion.
	 *
	 * @param json the operation-owned JSON codec
	 * @param request the request returned by a harness-specific factory
	 * @param prompt the operation-owned prompt
	 * @param operationName the diagnostic name of the owning operation
	 * @throws IOException if the request substitutes, adds, or removes model input
	 */
	public static void requireExactPrompt(JsonMapper json, HarnessEvaluationRequest request, String prompt,
		String operationName) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(prompt, "prompt must not be null");
		Objects.requireNonNull(operationName, "operationName must not be null");
		if (!request.input().equals(PromptInput.forPrompt(json, prompt)))
			throw new IOException(operationName + " request changed the trusted prompt input");
	}

	/**
	 * Prevents construction of this static request-binding owner.
	 */
	private HarnessRequestBinding()
	{
	}
}
