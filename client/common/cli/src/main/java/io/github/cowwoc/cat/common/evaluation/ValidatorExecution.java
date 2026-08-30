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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Invokes the native validator runner for opaque evaluation evidence.
 */
public final class ValidatorExecution
{
	/**
	 * Prevents instantiation.
	 */
	private ValidatorExecution()
	{
	}

	/**
	 * Adapts coordinator-private executor evidence to the generic validator lifecycle.
	 *
	 * @param json the mapper that constructs batched validator prompts
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the evaluator-owned workspace
	 * @param planPath the typed execution plan
	 * @param plan the parsed execution plan
	 * @param runner the runner that retains validator results
	 * @return the opaque-evidence access seam
	 */
	public static ValidatorLifecycle.Access<ExecutorEvidence> access(JsonMapper json, Path runPath, Path workspaceRoot,
		Path planPath, ObjectNode plan, HarnessEvaluationRunner runner)
	{
		return new ValidatorLifecycle.Access<>()
		{
			@Override
			public boolean requiresSemanticReview(ExecutorEvidence evidence) throws IOException
			{
				return Execution.access().hasSemanticAssertions(evidence) &&
					Execution.access().deterministicAssertionsPassed(evidence);
			}

			@Override
			public void recordDeterministicVerdict(ExecutorEvidence evidence) throws IOException
			{
				Execution.access().recordDeterministicCheckVerdict(runPath, evidence);
			}

			@Override
			public String key(ExecutorEvidence evidence)
			{
				return Execution.access().verdictKey(evidence);
			}

			@Override
			public Set<String> assertionIds(ExecutorEvidence evidence) throws IOException
			{
				return SuiteContract.assertionIds(evidence.testCase(), evidence.prompt(),
					EvaluationKind.SEMANTIC);
			}

			@Override
			public void publishActiveGroup(int ordinal) throws IOException
			{
				Execution.access().publishActiveStatus(workspaceRoot.getParent(), "validating_group_" + ordinal);
			}

			@Override
			public ValidatorLifecycle.GroupResult<ExecutorEvidence> execute(
				ValidatorSchedule.Group<ExecutorEvidence> group, Path validatorWorkspace) throws IOException
			{
				return executeGroup(json, runPath, group, validatorWorkspace, planPath, plan, runner);
			}

			@Override
			public void recordFailure(List<ExecutorEvidence> evidence, Path validatorWorkspace,
				IOException failure) throws IOException
			{
				recordGroupFailure(runPath, evidence, validatorWorkspace, failure);
			}

			@Override
			public void recordVerdict(ExecutorEvidence evidence, ObjectNode verdicts,
				Path validatorResult, long validationStartedAtNanos, long validationCompletedAtNanos, long mergedAtNanos)
				throws IOException
			{
				WorkerTiming timing = evidence.timing();
				if (timing != null)
					timing = timing.completed(validationStartedAtNanos, validationCompletedAtNanos, mergedAtNanos);
				Execution.access().recordCrossTrialVerdict(runPath, evidence, verdicts, validatorResult, timing);
			}
		};
	}

	/**
	 * Runs one validator group with the supplied typed runner and retains its exact verdict map.
	 *
	 * @param json the mapper that constructs the validator prompt
	 * @param runPath the persistent evaluation record
	 * @param group the assigned validator input group
	 * @param validatorWorkspace the initially absent validator workspace
	 * @param planPath the execution-plan path
	 * @param plan the parsed execution plan
	 * @param runner the runner that retains the validator result
	 * @return the complete retained group result
	 * @throws IOException if the group cannot retain an exact verdict map
	 */
	public static ValidatorLifecycle.GroupResult<ExecutorEvidence> executeGroup(JsonMapper json, Path runPath,
		ValidatorSchedule.Group<ExecutorEvidence> group, Path validatorWorkspace,
		Path planPath, ObjectNode plan, HarnessEvaluationRunner runner) throws IOException
	{
		List<ExecutorEvidence> evidence = group.evidence();
		Path validatorReceipt = validatorWorkspace.resolve("validator.json");
		Path validatorOutput = validatorWorkspace.resolve("validator-output.txt");
		Set<String> exclusions = Execution.access().validatorExcludedDirectories(plan, planPath);
		ObjectNode batchValidatorPlan = PromptEvaluation.object(plan.path("validator_batch"), "validator_batch");
		boolean directEvidenceValidator = Execution.access().directEvidenceValidator(batchValidatorPlan, planPath);
		Map<ExecutorEvidence, Path> directEvidenceProjects = Map.of();
		Files.createDirectories(validatorWorkspace);
		PromptEvaluation.write(validatorWorkspace.resolve("verdicts.json"), ValidatorLifecycle.newVerdicts(evidence,
			item -> Execution.access().verdictKey(item), item -> SuiteContract.assertionIds(item.testCase(), item.prompt(),
				EvaluationKind.SEMANTIC)));
		if (!directEvidenceValidator)
			for (ExecutorEvidence item : evidence)
				Execution.access().prepareValidatorContents(item.retainedProject(), item.rawResult(),
					validatorWorkspace.resolve("trial-" + item.trial()).resolve(item.caseId()), exclusions);
		else
		{
			directEvidenceProjects = new HashMap<>();
			for (ExecutorEvidence item : evidence)
			{
				Path directEvidenceProject = validatorWorkspace.resolve("direct-evidence").resolve("trial-" + item.trial()).
					resolve(item.caseId());
				Execution.access().stageDirectValidatorEvidence(item.retainedProject(), item.rawResult(),
					directEvidenceProject);
				directEvidenceProjects.put(item, directEvidenceProject);
			}
		}
		Path context = PromptEvaluation.path(PromptEvaluation.text(PromptEvaluation.object(PromptEvaluation.read(runPath),
			"run"), "execution_context", runPath));
		Path validatorPrompt = writePrompt(json, context, evidence, validatorWorkspace, directEvidenceProjects);
		long validationStartedAtNanos = System.nanoTime();
		List<Path> readOnlyEvidence = List.of();
		if (directEvidenceValidator)
			readOnlyEvidence = List.copyOf(directEvidenceProjects.values());
		HarnessEvaluationExecution validatorExecution = PromptEvaluation.executeHarnessEvaluation(plan, "validator_batch",
			validatorWorkspace, PromptEvaluation.bridgeInput(Files.readString(validatorPrompt, StandardCharsets.UTF_8)),
			validatorOutput, validatorReceipt, readOnlyEvidence, runner);
		HarnessEvaluationResult validatorRun = validatorExecution.result();
		Path validatorResult = validatorRun.retainedProject().resolve("verdicts.json");
		ObjectNode verdicts = PromptEvaluation.object(PromptEvaluation.read(validatorResult), "batch validator verdicts");
		return new ValidatorLifecycle.GroupResult<>(group, verdicts, validatorResult, validationStartedAtNanos,
			validatorExecution.completedAtNanos());
	}

	/**
	 * Records a validator-group failure for every assigned worker that remains unrecorded.
	 *
	 * @param runPath the persistent evaluation record
	 * @param evidence the affected executor evidence
	 * @param validatorWorkspace the workspace that owns the diagnostic
	 * @param failure the observed validator failure
	 * @throws IOException if the diagnostic or blocked result cannot be retained
	 */
	private static void recordGroupFailure(Path runPath, List<ExecutorEvidence> evidence,
		Path validatorWorkspace, IOException failure) throws IOException
	{
		Path diagnostic = validatorWorkspace.resolve("validator-diagnostic.txt");
		Files.createDirectories(diagnostic.getParent());
		String details = failure + System.lineSeparator();
		Path commandOutput = validatorWorkspace.resolve("validator-output.txt");
		if (Files.isRegularFile(commandOutput, NOFOLLOW_LINKS))
			details += Files.readString(commandOutput, StandardCharsets.UTF_8);
		Files.writeString(diagnostic, details, StandardCharsets.UTF_8);
		for (ExecutorEvidence item : evidence)
			Execution.access().recordIfUnrecorded(runPath, item, AttemptStatus.BLOCKED, diagnostic);
	}

	/**
	 * Writes the independent-review prompt for the supplied retained executor evidence.
	 *
	 * @param json the mapper that constructs the batched review request
	 * @param context the validator contract
	 * @param evidence the executor evidence
	 * @param workspace the validator workspace
	 * @param directEvidenceProjects direct-evidence copies indexed by executor evidence
	 * @return the written validator prompt
	 * @throws IOException if prompt construction fails
	 */
	private static Path writePrompt(JsonMapper json, Path context, List<ExecutorEvidence> evidence, Path workspace,
		Map<ExecutorEvidence, Path> directEvidenceProjects) throws IOException
	{
		ArrayNode cases = json.createArrayNode();
		for (ExecutorEvidence item : evidence)
		{
			Path directEvidenceProject = directEvidenceProjects.get(item);
			Path response;
			if (directEvidenceProject == null)
				response = workspace.resolve("trial-" + item.trial()).resolve(item.caseId()).resolve(
					PromptEvaluation.VALIDATOR_FINAL_RESPONSE);
			else
				response = Execution.access().directValidatorResponse(directEvidenceProject);
			if (!Files.isRegularFile(response, NOFOLLOW_LINKS))
				throw new IOException("Executor evidence contains no final task response for semantic review: " +
					item.rawResult());
			ObjectNode evaluatedCase = cases.addObject();
			evaluatedCase.put("id", Execution.access().verdictKey(item));
			evaluatedCase.put("task_prompt", Files.readString(item.prompt(), StandardCharsets.UTF_8));
			if (directEvidenceProject != null)
			{
				evaluatedCase.put("source_evidence", directEvidenceProject.toString());
				evaluatedCase.put("executor_output", Execution.access().directValidatorOutput(directEvidenceProject).
					toString());
				evaluatedCase.put("executor_response", response.toString());
				evaluatedCase.put("executor_response_sha256", PromptEvaluation.sha256(response));
			}
			else
			{
				String evidenceDirectory = "trial-" + item.trial() + "/" + item.caseId();
				evaluatedCase.put("source_evidence", evidenceDirectory);
				evaluatedCase.put("executor_output", evidenceDirectory + "/executor-output.jsonl");
				evaluatedCase.put("executor_response", evidenceDirectory + "/" +
					PromptEvaluation.VALIDATOR_FINAL_RESPONSE);
				evaluatedCase.put("executor_response_sha256", PromptEvaluation.sha256(response));
			}
			ArrayNode assertions = evaluatedCase.putArray("assertions");
			for (JsonNode assertion : PromptEvaluation.array(item.testCase().path("assertions"), "assertions",
				item.prompt()))
			{
				ObjectNode assertionObject = PromptEvaluation.object(assertion, "assertion");
				if (EvaluationKind.parse(PromptEvaluation.text(assertionObject, "evaluation_kind",
					item.prompt())) != EvaluationKind.SEMANTIC)
					continue;
				ObjectNode evaluatedAssertion = assertions.addObject();
				evaluatedAssertion.put("id", PromptEvaluation.text(assertionObject, "id", item.prompt()));
				evaluatedAssertion.put("instruction", PromptEvaluation.text(assertionObject, "instruction", item.prompt()));
			}
		}
		Path destination = workspace.resolve("validator-prompt.md");
		String prompt = ValidatorPrompt.render(json, ValidatorPrompt.BATCH_TEMPLATE, context, cases);
		Files.writeString(destination, prompt, StandardCharsets.UTF_8);
		return destination;
	}
}
