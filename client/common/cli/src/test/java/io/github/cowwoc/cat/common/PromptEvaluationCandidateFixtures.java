package io.github.cowwoc.cat.common;

import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates retained evaluator outcomes used by candidate-policy tests.
 */
final class PromptEvaluationCandidateFixtures
{
	/**
	 * Prevents construction of test fixture support.
	 */
	private PromptEvaluationCandidateFixtures()
	{
	}

	/**
	 * Retains a passing static result except for the batch validator's edge-condition verdict.
	 *
	 * @param requests the observed typed requests
	 * @param request the evaluator request to retain
	 * @return the retained evaluator result
	 * @throws IOException if the test-owned evidence cannot be written
	 */
	static HarnessEvaluationExecution
		staticBoundaryFailure(List<HarnessEvaluationRequest> requests, HarnessEvaluationRequest request) throws IOException
	{
		HarnessEvaluationExecution execution =
			staticEvaluationResult(requests, request, "passed");
		Path verdictsPath = execution.result().retainedProject().resolve("verdicts.json");
		if (!Files.isRegularFile(verdictsPath))
			return execution;
		ObjectNode verdicts = PromptEvaluation.object(PromptEvaluation.read(verdictsPath), "validator verdicts");
		for (String key : verdicts.properties().stream().map(java.util.Map.Entry::getKey).toList())
		{
			if (!key.endsWith("/edge-condition"))
				continue;
			ObjectNode verdict = PromptEvaluation.object(verdicts.path(key), "edge-condition verdict");
			verdict.put("status", "failed");
			ObjectNode assertion = PromptEvaluation.object(verdict.path("assertions").path("edge-result"),
				"edge-condition assertion");
			assertion.put("status", "failed");
		}
		PromptEvaluation.write(verdictsPath, verdicts);
		return execution;
	}

	/**
	 * Retains a passing static result except for the requested-change verdict from trial two.
	 *
	 * @param requests the observed typed requests
	 * @param request the evaluator request to retain
	 * @return the retained evaluator result
	 * @throws IOException if the test-owned evidence cannot be written
	 */
	static HarnessEvaluationExecution
		staticStressFailure(List<HarnessEvaluationRequest> requests, HarnessEvaluationRequest request) throws IOException
	{
		HarnessEvaluationExecution execution =
			staticEvaluationResult(requests, request, "passed");
		Path verdictsPath = execution.result().retainedProject().resolve("verdicts.json");
		if (!Files.isRegularFile(verdictsPath))
			return execution;
		ObjectNode verdicts = PromptEvaluation.object(PromptEvaluation.read(verdictsPath), "validator verdicts");
		if (!verdicts.has("trial-2/requested-change"))
			return execution;
		ObjectNode verdict = PromptEvaluation.object(verdicts.path("trial-2/requested-change"), "stress verdict");
		verdict.put("status", "failed");
		ObjectNode assertion = PromptEvaluation.object(verdict.path("assertions").path("result"), "stress assertion");
		assertion.put("status", "failed");
		PromptEvaluation.write(verdictsPath, verdicts);
		return execution;
	}

	/**
	 * Retains passing calibration verdicts and failing certification verdicts.
	 *
	 * @param requests the observed typed requests
	 * @param request the evaluator request to retain
	 * @return the retained evaluator result
	 * @throws IOException if the test-owned evidence cannot be written
	 */
	static HarnessEvaluationExecution
		staticFormalFailure(List<HarnessEvaluationRequest> requests, HarnessEvaluationRequest request) throws IOException
	{
		String validatorStatus = "failed";
		String project = request.project().toString();
		if (project.contains("semantic-controls") || project.contains("/check/"))
			validatorStatus = "passed";
		return staticEvaluationResult(requests, request, validatorStatus);
	}

	/**
	 * Simulates an unavailable evaluator without starting a native process.
	 *
	 * @param request the evaluator request that cannot run
	 * @return no result because the evaluator is unavailable
	 * @throws IOException always, to report the unavailable evaluator
	 */
	static HarnessEvaluationExecution
		staticUnavailableEvaluator(HarnessEvaluationRequest request) throws IOException
	{
		throw new IOException("Static evaluator is unavailable for " + request.project());
	}
}
