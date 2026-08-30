package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PromptEvaluation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
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
	 * Creates complete JSONL input for one testcase, including declared steering, interruption, and resumed turns.
	 *
	 * @param json the lifecycle-owned JSON mapper that constructs and serializes bridge messages
	 * @param testCase the validated testcase contract
	 * @param suitePath the testcase-suite path
	 * @param prompt the initial prompt path
	 * @return declared messages followed by explicit bridge completion
	 * @throws IOException if a declared prompt cannot be read or input cannot be serialized
	 */
	public static String forTestCase(JsonMapper json, ObjectNode testCase, Path suitePath, Path prompt)
		throws IOException
	{
		requireThat(json, "json").isNotNull();

		StringBuilder result = new StringBuilder();
		ObjectNode turn = json.createObjectNode();
		turn.put("type", "turn");
		String task = Files.readString(prompt, StandardCharsets.UTF_8);
		if (testCase.path("scenario").asBoolean(false))
			task = scenarioTask(task, prompt);
		if (testCase.path("routing_diagnostic").asBoolean(false))
			turn.put("routing_only", true);
		turn.put("prompt", task);
		JsonNode routePreparation = testCase.path("route_preparation");
		if (!routePreparation.isMissingNode())
			turn.set("route_preparation", routePreparation);
		append(json, result, turn);
		JsonNode declaredMessages = testCase.path("turns");
		if (!declaredMessages.isMissingNode())
			for (JsonNode message : PromptEvaluation.array(declaredMessages, "turns", suitePath))
			{
				ObjectNode declared = PromptEvaluation.object(message, "test turn");
				ObjectNode bridgeMessage = json.createObjectNode();
				String type = PromptEvaluation.text(declared, "type", suitePath);
				bridgeMessage.put("type", type);
				if (type.equals("turn") || type.equals("steer"))
				{
					Path messagePrompt = SuiteContract.requiredFile(suitePath,
						PromptEvaluation.text(declared, "prompt", suitePath), "Test turn prompt",
						PromptEvaluation.text(testCase, "id", suitePath));
					bridgeMessage.put("prompt", Files.readString(messagePrompt, StandardCharsets.UTF_8));
				}
				if (declared.has("after"))
					bridgeMessage.put("after", PromptEvaluation.text(declared, "after", suitePath));
				append(json, result, bridgeMessage);
			}
		append(json, result, json.createObjectNode().put("type", "complete"));
		return result.toString();
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
	 * Returns the runner-visible task portion of one scenario document.
	 *
	 * @param contents the complete scenario document
	 * @param scenarioPath the document used in diagnostics
	 * @return the scenario task without evaluator assertions
	 * @throws IOException if the document has no valid scenario boundary
	 */
	public static String scenarioTask(String contents, Path scenarioPath) throws IOException
	{
		return ScenarioDocument.parse(contents, scenarioPath).task();
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
