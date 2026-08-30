package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.PromptEvaluation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders packaged independent-validator instructions from immutable context and evaluator evidence.
 */
public final class ValidatorPrompt
{
	/**
	 * The packaged template for one validator response.
	 */
	public static final String SINGLE_TEMPLATE = "/prompts/prompt-evaluation/single-validator.md";
	/**
	 * The packaged template for one batched validator response.
	 */
	public static final String BATCH_TEMPLATE = "/prompts/prompt-evaluation/batch-validator.md";
	private static final String SEMANTIC_REVIEW_CONTRACT = "/prompts/prompt-evaluation/semantic-review-contract.md";
	private static final String CONTEXT_MARKER = "{{CONTEXT}}";
	private static final String EVALUATION_DATA_MARKER = "{{EVALUATION_DATA}}";
	private static final String SEMANTIC_REVIEW_CONTRACT_MARKER = "{{SEMANTIC_REVIEW_CONTRACT}}";

	/**
	 * Prevents construction of this static prompt owner.
	 */
	private ValidatorPrompt()
	{
	}

	/**
	 * Renders a packaged independent-review instruction with the declared context and evaluator evidence.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param templateResource the packaged reviewer instruction
	 * @param context the versioned evaluation-specific review contract
	 * @param evaluationData the evaluator-produced case and evidence data
	 * @return the complete reviewer instruction
	 * @throws IOException if a package resource cannot be read or a marker is absent or repeated
	 */
	public static String render(JsonMapper json, String templateResource, Path context, JsonNode evaluationData)
		throws IOException
	{
		String template = resource(templateResource);
		String withContext = replace(template, CONTEXT_MARKER, Files.readString(context, StandardCharsets.UTF_8),
			templateResource);
		String withContract = replace(withContext, SEMANTIC_REVIEW_CONTRACT_MARKER,
			resource(SEMANTIC_REVIEW_CONTRACT), templateResource);
		return replace(withContract, EVALUATION_DATA_MARKER,
			json.writerWithDefaultPrettyPrinter().writeValueAsString(evaluationData), templateResource);
	}


	/**
	 * Writes the single-review instruction beside immutable executor evidence.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param context supplies the versioned semantic-review contract
	 * @param taskPrompt identifies the prompt given to the executor
	 * @param testCase supplies the scenario assertions to review
	 * @param workspace receives the completed validator instruction
	 * @param retainedProject identifies the executor's retained project
	 * @param rawResult identifies the retained executor result
	 * @param finalResponse identifies the extracted executor response
	 * @return the validator-instruction path
	 * @throws IOException if evaluator evidence or the packaged instruction cannot be read or written
	 */
	public static Path write(JsonMapper json, Path context, Path taskPrompt, ObjectNode testCase, Path workspace,
		Path retainedProject, Path rawResult, Path finalResponse) throws IOException
	{
		Path destination = workspace.resolve("validator-prompt.md");
		ObjectNode evaluationData = json.createObjectNode();
		evaluationData.put("task_prompt", Files.readString(taskPrompt, StandardCharsets.UTF_8));
		evaluationData.put("source_evidence", retainedProject.toString());
		evaluationData.put("executor_output", rawResult.toString());
		evaluationData.put("executor_response", finalResponse.toString());
		evaluationData.put("executor_response_sha256", PromptEvaluation.sha256(finalResponse));
		ArrayNode assertions = evaluationData.putArray("assertions");
		for (JsonNode assertion : PromptEvaluation.array(testCase.path("assertions"), "assertions", taskPrompt))
		{
			ObjectNode assertionObject = PromptEvaluation.object(assertion, "assertion");
			if (EvaluationKind.parse(PromptEvaluation.text(assertionObject, "evaluation_kind", taskPrompt)) !=
				EvaluationKind.SEMANTIC)
			{
				continue;
			}
			ObjectNode evaluatedAssertion = assertions.addObject();
			evaluatedAssertion.put("id", PromptEvaluation.text(assertionObject, "id", taskPrompt));
			evaluatedAssertion.put("instruction", PromptEvaluation.text(assertionObject, "instruction", taskPrompt));
		}
		Files.writeString(destination, render(json, SINGLE_TEMPLATE, context, evaluationData), StandardCharsets.UTF_8);
		return destination;
	}

	/**
	 * Reads one packaged reviewer instruction.
	 *
	 * @param resource the absolute classpath resource name
	 * @return the prompt text
	 * @throws IOException if the packaged resource is unavailable
	 */
	private static String resource(String resource) throws IOException
	{
		try (InputStream input = ValidatorPrompt.class.getResourceAsStream(resource))
		{
			if (input == null)
				throw new IOException("Packaged reviewer prompt is absent: " + resource);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Replaces exactly one required marker in a packaged reviewer instruction.
	 *
	 * @param template the prompt template
	 * @param marker the required placeholder
	 * @param value the deterministic replacement
	 * @param resource the template resource used in diagnostics
	 * @return the completed prompt
	 * @throws IOException if the marker is absent or repeated
	 */
	private static String replace(String template, String marker, String value, String resource) throws IOException
	{
		int first = template.indexOf(marker);
		if (first < 0 || first != template.lastIndexOf(marker))
			throw new IOException("Packaged reviewer prompt must contain exactly one " + marker + ": " + resource);
		return template.substring(0, first) + value + template.substring(first + marker.length());
	}
}
