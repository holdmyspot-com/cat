package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.PromptEvaluation;
import io.github.cowwoc.cat.common.ToolScope;

import io.github.cowwoc.cat.common.evaluation.RoutingEvidence.CandidateRoute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retains executor and deterministic-check evidence for one claimed testcase.
 */
public final class Executor
{
	/**
	 * Prevents instantiation.
	 */
	private Executor()
	{
	}

	/**
	 * Executes or resumes one claimed testcase and records its executor-level terminal evidence.
	 *
	 * @param runPath the persistent evaluation record
	 * @param suitePath the immutable testcase suite
	 * @param testCase the selected testcase
	 * @param caseId the selected testcase identifier
	 * @param trial the claimed whole-prompt trial
	 * @param workspaceRoot the testcase workspace
	 * @param plan the parsed execution plan
	 * @param queuedAtNanos the time when the worker entered the executor queue
	 * @param canceledTrials the trials whose remaining work became unnecessary
	 * @return retained executor evidence, or {@code null} after recording a terminal failure
	 * @throws IOException if evidence cannot be retained
	 */
	public static ExecutorEvidence executeClaimed(Path runPath, Path suitePath, ObjectNode testCase,
		String caseId, int trial, Path workspaceRoot, ObjectNode plan, long queuedAtNanos, Set<Integer> canceledTrials)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return executeClaimed(runPath, suitePath, testCase, caseId, trial, workspaceRoot, plan, queuedAtNanos,
				canceledTrials, Execution.access().defaultRunner(scope.json()));
		}
	}

	/**
	 * Executes a claimed testcase through one executor and one validator and records the terminal outcome.
	 *
	 * @param json the mapper that creates the terminal execution summary
	 * @param runPath the persistent evaluation record
	 * @param caseId the declared testcase identifier
	 * @param trial the already claimed whole-prompt trial number
	 * @param workspaceRoot the initially absent owned workspace root
	 * @param planPath the typed execution plan
	 * @param runner the runner that retains executor and validator results
	 * @return the terminal status and retained evidence paths
	 * @throws IOException if the transaction cannot retain terminal evidence
	 */
	public static ObjectNode execute(JsonMapper json, Path runPath, String caseId, int trial, Path workspaceRoot,
		Path planPath,
		HarnessEvaluationRunner runner) throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		Path normalizedPlanPath = planPath.toAbsolutePath().normalize();
		ObjectNode run = PromptEvaluation.object(PromptEvaluation.read(normalizedRunPath), "run");
		Path suitePath = PromptEvaluation.path(PromptEvaluation.text(run, "suite", normalizedRunPath));
		ObjectNode suite = SuiteContract.read(suitePath);
		ObjectNode testCase = SuiteContract.testCase(suite, caseId, suitePath);
		Path prompt = SuiteContract.requiredFile(suitePath,
			PromptEvaluation.text(testCase, "prompt", suitePath), "Test prompt", caseId);
		Path executorWorkspace = normalizedWorkspaceRoot.resolve("executor-project");
		Path executorReceipt = normalizedWorkspaceRoot.resolve("executor.json");
		Path rawResult = normalizedWorkspaceRoot.resolve("executor-result.txt");
		Path validatorReceipt = normalizedWorkspaceRoot.resolve("validator.json");
		Path validatorResult = normalizedWorkspaceRoot.resolve("validator-result.txt");
		AttemptStatus status = AttemptStatus.BLOCKED;
		try
		{
			Execution.access().prepareEmptyWorkspace(normalizedWorkspaceRoot, "Execution");
			Execution.access().prepareTrialContents(suitePath, caseId, executorWorkspace);
			ObjectNode plan = PromptEvaluation.object(PromptEvaluation.read(normalizedPlanPath), "execution plan");
			HarnessEvaluationExecution executorExecution = PromptEvaluation.executeHarnessEvaluation(plan, "executor",
				executorWorkspace, Execution.access().bridgeInput(testCase, suitePath, prompt), rawResult, executorReceipt,
				List.of(), runner);
			io.github.cowwoc.cat.common.runner.HarnessEvaluationResult executorResult = executorExecution.result();
			Path retainedProject = executorResult.retainedProject();
			Path retainedRaw = executorResult.rawResult();
			rawResult = retainedRaw;
			Path validatorWorkspace = normalizedWorkspaceRoot.resolve("validator-project");
			Path context = PromptEvaluation.path(PromptEvaluation.text(run, "execution_context", normalizedRunPath));
			ObjectNode validatorPlan = PromptEvaluation.object(plan.path("validator"), "validator plan");
			boolean directEvidenceValidator = Execution.access().directEvidenceValidator(validatorPlan, normalizedPlanPath);
			if (!directEvidenceValidator)
			{
				Execution.access().prepareValidatorContents(retainedProject, retainedRaw, validatorWorkspace,
					Execution.access().validatorExcludedDirectories(plan, normalizedPlanPath));
			}
			else
				Files.createDirectories(validatorWorkspace);
			Path validatorRawResult = retainedRaw;
			Path validatorResponse;
			Path directEvidenceProject = null;
			if (directEvidenceValidator)
			{
				directEvidenceProject = validatorWorkspace.resolve("direct-evidence");
				Execution.access().stageDirectValidatorEvidence(retainedProject, retainedRaw, directEvidenceProject);
				validatorRawResult = Execution.access().directValidatorOutput(directEvidenceProject);
				validatorResponse = Execution.access().directValidatorResponse(directEvidenceProject);
			}
			else
				validatorResponse = validatorWorkspace.resolve(PromptEvaluation.VALIDATOR_FINAL_RESPONSE);
			if (!Files.isRegularFile(validatorResponse, java.nio.file.LinkOption.NOFOLLOW_LINKS))
			{
				throw new IOException("Executor evidence contains no final task response for semantic review: " + retainedRaw);
			}
			Path validatorPrompt = Execution.access().writeValidatorPrompt(context, prompt, testCase, validatorWorkspace,
				retainedProject, validatorRawResult, validatorResponse);
			Path validatorOutput = normalizedWorkspaceRoot.resolve("validator-output.txt");
			List<Path> readOnlyEvidence = List.of();
			if (directEvidenceValidator)
				readOnlyEvidence = List.of(directEvidenceProject);
			io.github.cowwoc.cat.common.runner.HarnessEvaluationResult validatorRun =
				PromptEvaluation.executeHarnessEvaluation(plan, "validator", validatorWorkspace,
					PromptEvaluation.bridgeInput(Files.readString(validatorPrompt, StandardCharsets.UTF_8)), validatorOutput,
					validatorReceipt, readOnlyEvidence, runner).result();
			Path retainedValidatorProject = validatorRun.retainedProject();
			validatorResult = retainedValidatorProject.resolve("verdict.txt");
			String verdict = Files.readString(validatorResult, StandardCharsets.UTF_8).strip();
			switch (verdict)
			{
				case "passed" -> status = AttemptStatus.PASSED;
				case "failed" -> status = AttemptStatus.FAILED;
				default -> throw new IOException("Validator must write exactly passed or failed");
			}
		}
		catch (IOException e)
		{
			if (!Files.exists(rawResult))
				Files.writeString(rawResult, e + System.lineSeparator());
			if (!Files.exists(validatorResult))
				Files.writeString(validatorResult, e + System.lineSeparator());
		}
		Path finalRawResult = rawResult;
		Path finalValidatorResult = validatorResult;
		AttemptStatus finalStatus = status;
		Execution.access().record(normalizedRunPath, caseId, trial, finalStatus, finalRawResult, finalValidatorResult);
		ObjectNode result = json.createObjectNode();
		result.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
		result.put("raw_result", rawResult.toString());
		result.put("validator_result", validatorResult.toString());
		return result;
	}

	/**
	 * Executes one testcase with the supplied typed harness runner and retains its executor-level terminal evidence.
	 *
	 * @param runPath the persistent evaluation record
	 * @param suitePath the immutable testcase suite
	 * @param testCase the selected testcase
	 * @param caseId the selected testcase identifier
	 * @param trial the claimed whole-prompt trial
	 * @param workspaceRoot the testcase workspace
	 * @param plan the parsed execution plan
	 * @param queuedAtNanos the time when the worker entered the executor queue
	 * @param canceledTrials the trials whose remaining work became unnecessary
	 * @param runner the runner that retains executor evidence
	 * @return retained executor evidence, or {@code null} after recording a terminal failure
	 * @throws IOException if evidence cannot be retained
	 */
	public static ExecutorEvidence executeClaimed(Path runPath, Path suitePath, ObjectNode testCase,
		String caseId, int trial, Path workspaceRoot, ObjectNode plan, long queuedAtNanos, Set<Integer> canceledTrials,
		HarnessEvaluationRunner runner)
		throws IOException
	{
		long executionStartedAtNanos = System.nanoTime();
		Path executorWorkspace = workspaceRoot.resolve("executor-project");
		Path executorReceipt = workspaceRoot.resolve("executor.json");
		Path rawResult = workspaceRoot.resolve("executor-result.txt");
		Path blockedResult = workspaceRoot.resolve("batch-validator-blocked.txt");
		Path prompt = SuiteContract.requiredFile(suitePath, PromptEvaluation.text(testCase, "prompt", suitePath),
			"Test prompt", caseId);
		try
		{
			ExecutorEvidence completed = completedEvidence(trial, caseId, testCase, prompt,
				executorWorkspace, executorReceipt, rawResult);
			if (completed != null)
				return checkedEvidence(runPath, suitePath, completed, workspaceRoot, null);
			if (Files.exists(workspaceRoot))
				throw new IOException("Interrupted executor workspace has no complete receipt: " + workspaceRoot);
			Execution.access().prepareEmptyWorkspace(workspaceRoot, "Execution");
			Execution.access().prepareTrialContents(suitePath, caseId, executorWorkspace);
			HarnessEvaluationExecution execution =
				PromptEvaluation.executeHarnessEvaluation(plan, "executor", executorWorkspace,
					Execution.access().bridgeInput(testCase, suitePath, prompt), rawResult, executorReceipt, List.of(), runner);
			WorkerTiming timing = new WorkerTiming(queuedAtNanos, executionStartedAtNanos,
				execution.startedAtNanos(), execution.completedAtNanos());
			io.github.cowwoc.cat.common.runner.HarnessEvaluationResult executorResult = execution.result();
			ExecutorEvidence evidence = new ExecutorEvidence(trial, caseId, testCase,
				prompt,
				executorResult.retainedProject(), executorResult.rawResult(), null, timing, false);
			return checkedEvidence(runPath, suitePath, evidence, workspaceRoot, timing);
		}
		catch (IOException e)
		{
			if (!Files.exists(rawResult))
				Files.writeString(rawResult, e + System.lineSeparator());
			if (rejectedRouteSelection(rawResult))
			{
				Execution.access().record(runPath, caseId, trial, AttemptStatus.FAILED, rawResult, rawResult);
				return null;
			}
			Files.writeString(blockedResult, e + System.lineSeparator());
			AttemptStatus status = AttemptStatus.BLOCKED;
			if (canceledTrials.contains(trial))
				status = AttemptStatus.CANCELED;
			AttemptStatus recordedStatus = status;
			Execution.access().record(runPath, caseId, trial, recordedStatus, rawResult, blockedResult);
			return null;
		}
	}

	/**
	 * Runs a completed executor result's deterministic check and records a failed check.
	 *
	 * @param runPath the persistent evaluation record
	 * @param suitePath the immutable testcase suite
	 * @param evidence the retained executor result
	 * @param workspaceRoot the testcase workspace
	 * @param timing the executor timing, or {@code null} for resumed work
	 * @return the executor result with its deterministic-check outcome
	 * @throws IOException if the check result cannot be retained or recorded
	 */
	private static ExecutorEvidence checkedEvidence(Path runPath, Path suitePath,
		ExecutorEvidence evidence, Path workspaceRoot, WorkerTiming timing)
		throws IOException
	{
		ObjectNode run = PromptEvaluation.object(PromptEvaluation.read(runPath), "evaluation record");
		CandidateRoute candidate = RoutingEvidence.candidateRoute(run, runPath);
		Path checkResult = Execution.access().runDeterministicCheck(evidence.testCase(), suitePath,
			evidence.retainedProject(), evidence.rawResult(), workspaceRoot, candidate);
		if (checkResult != null && Execution.access().deterministicCheckFailed(checkResult, evidence.testCase(),
			evidence.prompt()))
		{
			Execution.access().record(runPath, evidence.caseId(), evidence.trial(), AttemptStatus.FAILED,
				evidence.rawResult(), checkResult, checkResult);
			return new ExecutorEvidence(evidence.trial(), evidence.caseId(), evidence.testCase(),
				evidence.prompt(), evidence.retainedProject(), evidence.rawResult(), checkResult, timing, true);
		}
		return new ExecutorEvidence(evidence.trial(), evidence.caseId(), evidence.testCase(),
			evidence.prompt(), evidence.retainedProject(), evidence.rawResult(), checkResult, timing, false);
	}

	/**
	 * Indicates whether the executor output records a rejected route selection.
	 *
	 * @param rawResult the retained executor output
	 * @return whether the output records a rejected route selection
	 * @throws IOException if the output cannot be read
	 */
	private static boolean rejectedRouteSelection(Path rawResult) throws IOException
	{
		return Files.readString(rawResult, StandardCharsets.UTF_8).contains(
			"CAT direct rule delivery cannot find declared rule:");
	}

	/**
	 * Returns validated executor evidence from a retained completed receipt.
	 *
	 * @param trial the claimed whole-prompt trial
	 * @param caseId the selected testcase identifier
	 * @param testCase the selected testcase
	 * @param prompt the immutable testcase prompt
	 * @param executorWorkspace the executor workspace
	 * @param executorReceipt the retained executor receipt
	 * @param rawResult the retained executor output
	 * @return the retained evidence, or {@code null} when no receipt exists
	 * @throws IOException if the receipt is incomplete or names another workspace
	 */
	private static ExecutorEvidence completedEvidence(int trial, String caseId, ObjectNode testCase,
		Path prompt, Path executorWorkspace, Path executorReceipt, Path rawResult) throws IOException
	{
		if (!Files.exists(executorReceipt))
			return null;
		if (!Files.isRegularFile(executorReceipt) || !Files.isRegularFile(rawResult))
			throw new IOException("Interrupted executor receipt is incomplete: " + executorReceipt);
		io.github.cowwoc.cat.common.runner.HarnessEvaluationResult result =
			Execution.access().harnessResult(executorReceipt, "executor receipt");
		if (!result.retainedProject().startsWith(executorWorkspace) || !result.rawResult().equals(rawResult))
			throw new IOException("Interrupted executor receipt references a different workspace: " + executorReceipt);
		return new ExecutorEvidence(trial, caseId, testCase, prompt, result.retainedProject(), rawResult,
			null, null, false);
	}
}
