package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.PromptEvaluation;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

/**
 * Calibrates the independent validator with the suite's declared semantic-control fixtures.
 */
public final class Calibration
{
	/**
	 * Prevents instantiation.
	 */
	private Calibration()
	{
	}

	/**
	 * Verifies or reuses the semantic-control calibration for one prepared evaluation.
	 *
	 * @param json the mapper used to render the validator request
	 * @param runPath the prepared evaluation record
	 * @param workspaceRoot the evaluation's retained workspace
	 * @param planPath the immutable execution plan
	 * @param runner the runner that retains validator evidence
	 * @throws IOException if a declared semantic control does not receive its expected independent verdict
	 */
	public static void verify(JsonMapper json, Path runPath, Path workspaceRoot, Path planPath,
		HarnessEvaluationRunner runner)
		throws IOException
	{
		ObjectNode run = PromptEvaluation.object(PromptEvaluation.read(runPath), "run");
		Path suitePath = PromptEvaluation.path(PromptEvaluation.text(run, "suite", runPath));
		Path receipt = workspaceRoot.resolve("semantic-control-check.json");
		if (Files.isRegularFile(receipt, NOFOLLOW_LINKS))
		{
			CalibrationReceipt.verify(receipt, suitePath, planPath);
			return;
		}

		ObjectNode suite = SuiteContract.read(suitePath);
		Path validatorWorkspace = workspaceRoot.resolve("semantic-controls/validator");
		ArrayNode controls = stageControls(suite, suitePath, validatorWorkspace);
		if (controls.isEmpty())
			return;

		Path verdictPath = runValidator(json, run, runPath, planPath, validatorWorkspace, controls, runner);
		ObjectNode verdicts = PromptEvaluation.object(PromptEvaluation.read(verdictPath), "semantic-control verdicts");
		CalibrationReceipt.validate(controls, verdicts, verdictPath);
		writeReceipt(receipt, suitePath, planPath, workspaceRoot, verdictPath);
	}

	/**
	 * Stages fixed control responses and their requested assertions in the validator project.
	 *
	 * @param suite the evaluation suite
	 * @param suitePath the suite manifest path
	 * @param validatorWorkspace the validator's isolated project
	 * @return the controls supplied to the independent validator
	 * @throws IOException if a declared control fixture is invalid or cannot be staged
	 */
	private static ArrayNode stageControls(ObjectNode suite, Path suitePath, Path validatorWorkspace) throws IOException
	{
		ArrayNode controls = PromptEvaluation.newArray();
		Path evidenceRoot = validatorWorkspace.resolve("evidence");
		for (JsonNode node : PromptEvaluation.array(suite.path("cases"), "cases", suitePath))
		{
			ObjectNode testCase = PromptEvaluation.object(node, "test case");
			String caseId = PromptEvaluation.text(testCase, "id", suitePath);
			Path taskPrompt = SuiteContract.requiredFile(suitePath,
				PromptEvaluation.text(testCase, "prompt", suitePath), "Test prompt", caseId);
			Set<String> semanticAssertions = SuiteContract.assertionIds(testCase, taskPrompt,
				EvaluationKind.SEMANTIC);
			JsonNode declared = testCase.path("semantic_controls");
			if (declared.isMissingNode())
				continue;

			for (JsonNode controlNode : PromptEvaluation.array(declared, "semantic controls", suitePath))
			{
				ObjectNode control = PromptEvaluation.object(controlNode, "semantic control");
				String controlId = PromptEvaluation.text(control, "id", suitePath);
				Path source = SuiteContract.requiredFile(suitePath,
					PromptEvaluation.text(control, "response", suitePath), "Semantic control response", caseId);
				Path responseDirectory = evidenceRoot.resolve(caseId).resolve(controlId);
				Path response = responseDirectory.resolve(PromptEvaluation.VALIDATOR_FINAL_RESPONSE);
				Files.createDirectories(response.getParent());
				Files.copy(source, response, StandardCopyOption.REPLACE_EXISTING);
				ObjectNode item = controls.addObject();
				item.put("id", semanticControlKey(caseId, controlId));
				item.put("task_prompt", Files.readString(taskPrompt, StandardCharsets.UTF_8));
				item.put("source_evidence", validatorWorkspace.relativize(response).toString());
				item.put("executor_output", "This is a fixed reviewer-control response; no executor protocol is evaluated.");
				item.put("executor_response", validatorWorkspace.relativize(response).toString());
				item.put("executor_response_sha256", PromptEvaluation.sha256(response));
				appendAssertions(item, testCase, taskPrompt, semanticAssertions);
				item.set("expected_assertions", control.path("expected_assertions").deepCopy());
			}
		}
		return controls;
	}

	/**
	 * Adds each semantic assertion the validator must evaluate for one fixed control response.
	 *
	 * @param control the evaluator-generated control item
	 * @param testCase the owning testcase
	 * @param taskPrompt the testcase prompt
	 * @param semanticAssertions the assertion identifiers assigned to independent review
	 * @throws IOException if a declared assertion is invalid
	 */
	private static void appendAssertions(ObjectNode control, ObjectNode testCase, Path taskPrompt,
		Set<String> semanticAssertions) throws IOException
	{
		ArrayNode assertions = control.putArray("assertions");
		for (JsonNode assertionNode : PromptEvaluation.array(testCase.path("assertions"), "assertions", taskPrompt))
		{
			ObjectNode assertion = PromptEvaluation.object(assertionNode, "assertion");
			String assertionId = PromptEvaluation.text(assertion, "id", taskPrompt);
			if (!semanticAssertions.contains(assertionId))
				continue;
			ObjectNode reviewAssertion = assertions.addObject();
			reviewAssertion.put("id", assertionId);
			reviewAssertion.put("instruction", PromptEvaluation.text(assertion, "instruction", taskPrompt));
		}
	}

	/**
	 * Runs the batched validator and returns its copied-project verdict map.
	 *
	 * @param json the mapper used to render the validator request
	 * @param run the prepared evaluation record
	 * @param runPath the prepared evaluation record path
	 * @param planPath the immutable execution plan
	 * @param validatorWorkspace the validator's isolated project
	 * @param controls the staged control cases
	 * @param runner the selected harness runner
	 * @return the verdict map in the runner's retained project
	 * @throws IOException if the validator cannot produce retained verdicts
	 */
	private static Path runValidator(JsonMapper json, ObjectNode run, Path runPath, Path planPath,
		Path validatorWorkspace,
		ArrayNode controls, HarnessEvaluationRunner runner) throws IOException
	{
		Files.createDirectories(validatorWorkspace);
		writeVerdictSkeleton(validatorWorkspace.resolve("verdicts.json"), controls);
		Path context = PromptEvaluation.path(PromptEvaluation.text(run, "execution_context", runPath));
		Path validatorPrompt = validatorWorkspace.resolve("validator-prompt.md");
		String prompt = ValidatorPrompt.render(json, ValidatorPrompt.BATCH_TEMPLATE, context, controls);
		Files.writeString(validatorPrompt, prompt, StandardCharsets.UTF_8);
		ObjectNode plan = PromptEvaluation.object(PromptEvaluation.read(planPath), "execution plan");
		Path validatorReceipt = validatorWorkspace.resolve("validator.json");
		Path validatorOutput = validatorWorkspace.resolve("validator-output.txt");
		HarnessEvaluationExecution execution = PromptEvaluation.executeHarnessEvaluation(plan, "validator_batch",
			validatorWorkspace, PromptEvaluation.bridgeInput(prompt), validatorOutput, validatorReceipt, List.of(), runner);
		HarnessEvaluationResult result = execution.result();
		return result.retainedProject().resolve("verdicts.json");
	}

	/**
	 * Creates the verdict file that the validator completes in its copied project.
	 *
	 * @param destination the initially absent validator verdict file
	 * @param controls the declared semantic-control cases
	 * @throws IOException if a control has duplicate verdict or assertion identifiers
	 */
	private static void writeVerdictSkeleton(Path destination, ArrayNode controls) throws IOException
	{
		ObjectNode verdicts = PromptEvaluation.newObject();
		for (JsonNode node : controls)
		{
			ObjectNode control = PromptEvaluation.object(node, "semantic control");
			String controlId = PromptEvaluation.text(control, "id", destination);
			if (verdicts.has(controlId))
				throw new IOException("Semantic controls have duplicate verdict key: " + controlId);
			ObjectNode verdict = verdicts.putObject(controlId);
			verdict.put("status", "");
			ObjectNode assertions = verdict.putObject("assertions");
			for (JsonNode assertionNode : PromptEvaluation.array(control.path("assertions"),
				"semantic control assertions", destination))
			{
				ObjectNode assertion = PromptEvaluation.object(assertionNode, "semantic control assertion");
				String assertionId = PromptEvaluation.text(assertion, "id", destination);
				if (assertions.has(assertionId))
					throw new IOException("Semantic control has duplicate assertion id: " + assertionId);
				ObjectNode result = assertions.putObject(assertionId);
				result.put("status", "");
				result.put("evidence", "");
			}
		}
		PromptEvaluation.write(destination, verdicts);
	}

	/**
	 * Writes the retained receipt that binds a successful calibration to its immutable inputs and verdict map.
	 *
	 * @param receipt the calibration receipt path
	 * @param suitePath the suite manifest path
	 * @param planPath the immutable execution plan
	 * @param workspaceRoot the retained evaluation workspace
	 * @param verdictPath the validator's retained verdict map
	 * @throws IOException if the receipt cannot be persisted
	 */
	private static void writeReceipt(Path receipt, Path suitePath, Path planPath, Path workspaceRoot, Path verdictPath)
		throws IOException
	{
		ObjectNode completed = PromptEvaluation.newObject();
		completed.put("suite_sha256", PromptEvaluation.suiteFingerprint(suitePath));
		completed.put("execution_plan_sha256", PromptEvaluation.sha256(planPath));
		completed.put("verdicts", workspaceRoot.relativize(verdictPath).toString());
		completed.put("verdicts_sha256", PromptEvaluation.sha256(verdictPath));
		completed.put("status", "passed");
		PromptEvaluation.write(receipt, completed);
	}

	/**
	 * Returns the validator-verdict key for one testcase control.
	 *
	 * @param caseId the durable testcase identifier
	 * @param controlId the testcase-local control identifier
	 * @return a key that cannot collide with an executor trial verdict
	 */
	private static String semanticControlKey(String caseId, String controlId)
	{
		return "semantic-control/" + caseId + "/" + controlId;
	}
}
