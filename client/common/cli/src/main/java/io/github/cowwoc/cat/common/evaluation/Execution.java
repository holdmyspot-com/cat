package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import io.github.cowwoc.cat.common.secrets.EvaluatorSecrets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Connects evaluator-owned execution transactions to coordinator-owned record and workspace operations.
 */
public final class Execution
{
	/**
	 * Supplies the coordinator operations ordered by the execution-evidence transaction.
	 */
	public interface Access
	{
		/**
		 * Returns the default typed harness runner.
		 *
		 * @param json the mapper that reads runner receipts
		 * @return the runner
		 */
		HarnessEvaluationRunner defaultRunner(JsonMapper json);

		/**
		 * Publishes the active execution phase.
		 *
		 * @param workspaceRoot the owned workspace root
		 * @param phase the active phase
		 * @throws IOException if publication fails
		 */
		void publishActiveStatus(Path workspaceRoot, String phase) throws IOException;

		/**
		 * Determines whether the supplied run is terminal.
		 *
		 * @param runPath the evaluation record
		 * @param suitePath the suite contract
		 * @return whether the run is terminal
		 * @throws IOException if the record cannot be read
		 */
		boolean isTerminal(Path runPath, Path suitePath) throws IOException;

		/**
		 * Returns the requested trials that still lack a record.
		 *
		 * @param runPath the evaluation record
		 * @param caseId the testcase identifier
		 * @param trials the requested trials
		 * @return unrecorded trials
		 * @throws IOException if the record cannot be read
		 */
		List<Integer> unrecordedTrials(Path runPath, String caseId, List<Integer> trials) throws IOException;

		/**
		 * Records a terminal testcase result under the coordinator lock.
		 *
		 * @param runPath the evaluation record
		 * @param caseId the testcase identifier
		 * @param trial the trial number
		 * @param status the terminal status
		 * @param rawResult the executor result
		 * @param validatorResult the validator result
		 * @throws IOException if the result cannot be recorded
		 */
		void record(Path runPath, String caseId, int trial, AttemptStatus status, Path rawResult,
			Path validatorResult) throws IOException;

		/**
		 * Records a terminal testcase result and deterministic-check evidence.
		 *
		 * @param runPath the evaluation record
		 * @param caseId the testcase identifier
		 * @param trial the trial number
		 * @param status the terminal status
		 * @param rawResult the executor result
		 * @param validatorResult the validator result
		 * @param checkResult the deterministic-check result
		 * @throws IOException if the result cannot be recorded
		 */
		void record(Path runPath, String caseId, int trial, AttemptStatus status, Path rawResult,
			Path validatorResult, Path checkResult) throws IOException;

		/**
		 * Determines whether a retained runner manifest has a live owner.
		 *
		 * @param manifestPath the runner manifest
		 * @return whether the manifest has an active owner
		 * @throws IOException if ownership cannot be determined
		 */
		boolean hasActiveWorkerOwner(Path manifestPath) throws IOException;

		/**
		 * Prepares an initially absent owned workspace.
		 *
		 * @param workspace the workspace
		 * @param operation the owning operation
		 * @throws IOException if preparation fails
		 */
		void prepareEmptyWorkspace(Path workspace, String operation) throws IOException;

		/**
		 * Copies the selected testcase contents into an executor workspace.
		 *
		 * @param suitePath the suite contract
		 * @param caseId the testcase identifier
		 * @param workspace the executor workspace
		 * @throws IOException if contents cannot be prepared
		 */
		void prepareTrialContents(Path suitePath, String caseId, Path workspace) throws IOException;

		/**
		 * Produces the typed runner input for one testcase.
		 *
		 * @param testCase the testcase contract
		 * @param suitePath the suite contract
		 * @param prompt the testcase prompt
		 * @return the runner input
		 * @throws IOException if input cannot be produced
		 */
		String bridgeInput(ObjectNode testCase, Path suitePath, Path prompt) throws IOException;

		/**
		 * Determines whether a validator consumes staged direct evidence.
		 *
		 * @param validatorPlan the validator plan
		 * @param planPath the plan path
		 * @return whether direct evidence is required
		 * @throws IOException if the plan is invalid
		 */
		boolean directEvidenceValidator(ObjectNode validatorPlan, Path planPath) throws IOException;

		/**
		 * Prepares ordinary retained executor evidence for a validator.
		 *
		 * @param retainedProject the retained executor project
		 * @param rawResult the executor result
		 * @param workspace the validator workspace
		 * @param exclusions excluded evidence directories
		 * @throws IOException if evidence cannot be prepared
		 */
		void prepareValidatorContents(Path retainedProject, Path rawResult, Path workspace, Set<String> exclusions)
			throws IOException;

		/**
		 * Returns the directories a validator must not consume.
		 *
		 * @param plan the execution plan
		 * @param planPath the plan path
		 * @return excluded directory names
		 * @throws IOException if the plan is invalid
		 */
		Set<String> validatorExcludedDirectories(ObjectNode plan, Path planPath) throws IOException;

		/**
		 * Stages direct validator evidence.
		 *
		 * @param retainedProject the retained executor project
		 * @param rawResult the executor result
		 * @param destination the evidence destination
		 * @throws IOException if staging fails
		 */
		void stageDirectValidatorEvidence(Path retainedProject, Path rawResult, Path destination) throws IOException;

		/**
		 * Returns the staged validator-output path.
		 *
		 * @param directEvidenceProject the staged evidence root
		 * @return the output path
		 */
		Path directValidatorOutput(Path directEvidenceProject);

		/**
		 * Returns the staged final-response path.
		 *
		 * @param directEvidenceProject the staged evidence root
		 * @return the response path
		 */
		Path directValidatorResponse(Path directEvidenceProject);

		/**
		 * Writes a validator prompt for one testcase.
		 *
		 * @param context the validator context
		 * @param taskPrompt the testcase prompt
		 * @param testCase the testcase contract
		 * @param workspace the validator workspace
		 * @param retainedProject the retained executor project
		 * @param rawResult the executor result
		 * @param response the executor response
		 * @return the written prompt
		 * @throws IOException if the prompt cannot be written
		 */
		Path writeValidatorPrompt(Path context, Path taskPrompt, ObjectNode testCase, Path workspace,
			Path retainedProject, Path rawResult, Path response) throws IOException;

		/**
		 * Runs the deterministic check for retained executor evidence.
		 *
		 * @param testCase the testcase contract
		 * @param suitePath the suite contract
		 * @param retainedProject the retained executor project
		 * @param rawResult the executor result
		 * @param workspace the execution workspace
		 * @param candidate the candidate route
		 * @return the optional check result
		 * @throws IOException if the check cannot run
		 */
		Path runDeterministicCheck(ObjectNode testCase, Path suitePath, Path retainedProject, Path rawResult,
			Path workspace, RoutingEvidence.CandidateRoute candidate) throws IOException;

		/**
		 * Determines whether a deterministic-check result failed.
		 *
		 * @param result the check result
		 * @param testCase the testcase contract
		 * @param prompt the testcase prompt
		 * @return whether the check failed
		 * @throws IOException if the result cannot be read
		 */
		boolean deterministicCheckFailed(Path result, ObjectNode testCase, Path prompt) throws IOException;

		/**
		 * Reads one retained typed harness receipt.
		 *
		 * @param receiptPath the receipt path
		 * @param receiptName the receipt description
		 * @return the retained harness result
		 * @throws IOException if the receipt is invalid
		 */
		HarnessEvaluationResult harnessResult(Path receiptPath, String receiptName) throws IOException;

		/**
		 * Determines whether executor evidence requires semantic review.
		 *
		 * @param evidence the executor evidence
		 * @return whether semantic review is required
		 */
		boolean hasSemanticAssertions(ExecutorEvidence evidence);

		/**
		 * Determines whether deterministic assertions passed.
		 *
		 * @param evidence the executor evidence
		 * @return whether deterministic assertions passed
		 * @throws IOException if the evidence cannot be read
		 */
		boolean deterministicAssertionsPassed(ExecutorEvidence evidence) throws IOException;

		/**
		 * Records a deterministic-only verdict.
		 *
		 * @param runPath the evaluation record
		 * @param evidence the executor evidence
		 * @throws IOException if the verdict cannot be recorded
		 */
		void recordDeterministicCheckVerdict(Path runPath, ExecutorEvidence evidence) throws IOException;

		/**
		 * Returns the stable validator-verdict key.
		 *
		 * @param evidence the executor evidence
		 * @return the verdict key
		 */
		String verdictKey(ExecutorEvidence evidence);

		/**
		 * Records a merged cross-trial validator verdict.
		 *
		 * @param runPath the evaluation record
		 * @param evidence the executor evidence
		 * @param verdicts the validator verdicts
		 * @param validatorResult the validator result
		 * @param timing the worker timing
		 * @throws IOException if the verdict cannot be recorded
		 */
		void recordCrossTrialVerdict(Path runPath, ExecutorEvidence evidence, ObjectNode verdicts,
			Path validatorResult, WorkerTiming timing) throws IOException;

		/**
		 * Records a validator failure only when its testcase remains unrecorded.
		 *
		 * @param runPath the evaluation record
		 * @param evidence the executor evidence
		 * @param status the terminal status
		 * @param validatorResult the validator result
		 * @throws IOException if the result cannot be recorded
		 */
		void recordIfUnrecorded(Path runPath, ExecutorEvidence evidence, AttemptStatus status, Path validatorResult)
			throws IOException;
	}

	private static volatile Access access;

	/** Prevents construction of this static access boundary. */
	private Execution()
	{
	}

	/**
	 * Registers the coordinator operations used by execution owners.
	 *
	 * @param access the coordinator operations
	 */
	public static void configure(Access access)
	{
		Execution.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Returns the configured coordinator operations.
	 *
	 * @return the coordinator operations
	 */
	public static Access access()
	{
		EvaluatorSecrets.access().initialize();
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure execution access");
		return result;
	}
}
