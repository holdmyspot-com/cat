package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.cat.common.PromptEvaluation.array;
import static io.github.cowwoc.cat.common.PromptEvaluation.attempts;
import static io.github.cowwoc.cat.common.PromptEvaluation.object;
import static io.github.cowwoc.cat.common.PromptEvaluation.path;
import static io.github.cowwoc.cat.common.PromptEvaluation.read;
import static io.github.cowwoc.cat.common.PromptEvaluation.text;
import static io.github.cowwoc.cat.common.PromptEvaluation.withRunLock;
import static io.github.cowwoc.cat.common.PromptEvaluation.write;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns non-certifying candidate-regression execution and its retained result.
 *
 * <p>
 * A regression check first runs every durable scenario once, then repeats only scenarios with active failure
 * obligations. It stops scheduling later stress waves when a completed wave fails, retaining the completed evidence
 * rather than treating a partial check as certification evidence.
 */
public final class RegressionCheck
{
	private static final int BASELINE_TRIALS = 1;
	private static final int INITIAL_FAILURE_STRESS_TRIALS = 8;
	private static final int RECURRENT_FAILURE_STRESS_TRIALS = 16;
	private static final int REPEATED_FAILURE_STRESS_TRIALS = 32;

	/**
	 * Prevents construction of this static policy owner.
	 */
	private RegressionCheck()
	{
	}

	/**
	 * Executes one whole-suite candidate check and retains its terminal summary.
	 *
	 * @param json the mapper that creates summaries and classifies failure signatures
	 * @param runPath the prepared record
	 * @param workspaceRoot the owned evaluation workspace
	 * @param planPath the typed execution plan
	 * @param maximumConcurrency the shared worker capacity
	 * @param runner the runner that retains executor and validator results
	 * @return the terminal check summary
	 * @throws IOException if retained evidence is incomplete
	 */
	public static ObjectNode execute(JsonMapper json, Path runPath, Path workspaceRoot, Path planPath,
		int maximumConcurrency,
		HarnessEvaluationRunner runner) throws IOException
	{
		ObjectNode run = object(read(runPath), "run");
		Path suitePath = path(text(run, "suite", runPath));
		ObjectNode suite = SuiteContract.read(suitePath);
		List<Integer> trials = TrialClaims.reserve(runPath, BASELINE_TRIALS, false);
		ObjectNode plan = object(read(planPath), "execution plan");
		Path trialWorkspace = workspaceRoot.resolve("check-trial-" + trials.getFirst());
		List<ObjectNode> cases = new ArrayList<>();
		for (JsonNode node : array(suite.path("cases"), "cases", suitePath))
			cases.add(object(node, "test case"));

		List<ExecutorEvidence> evidence = ExecutionScheduler.executeRound(runPath, suitePath, cases, trials,
			trialWorkspace, plan, maximumConcurrency, true, runner);
		ValidatorLifecycle.recordRound(ValidatorExecution.access(json, runPath, trialWorkspace, planPath, plan, runner),
			evidence, trialWorkspace, maximumConcurrency);

		ObjectNode result = summarize(json, runPath, suite, trials, trialWorkspace);
		List<ObjectNode> activeCases = activeFailureCases(runPath, suite);
		if (result.path("status").asString().equals("passed") && !activeCases.isEmpty())
		{
			int plannedStressRepetitions = activeFailureStressTrials(runPath);
			List<Integer> completedStressTrials = new ArrayList<>();
			Path stressWorkspace = workspaceRoot.resolve("active-failure-stress");
			for (int repetition = 1; repetition <= plannedStressRepetitions; ++repetition)
			{
				List<Integer> waveTrials = TrialClaims.reserve(runPath, 1, false);
				Path waveWorkspace = stressWorkspace.resolve("wave-" + repetition);
				List<ExecutorEvidence> stressEvidence = ExecutionScheduler.executeRound(runPath, suitePath, activeCases,
					waveTrials, waveWorkspace, plan, maximumConcurrency, true, runner);
				ValidatorLifecycle.recordRound(ValidatorExecution.access(json, runPath, waveWorkspace, planPath, plan, runner),
					stressEvidence, waveWorkspace, maximumConcurrency);
				completedStressTrials.addAll(waveTrials);
				if (hasFailedStressWave(runPath, activeCases, waveTrials))
					break;
			}
			appendActiveFailureStressResult(json, result, runPath, activeCases, completedStressTrials, stressWorkspace,
				plannedStressRepetitions);
		}
		withRunLock(runPath, () ->
		{
			ObjectNode record = object(read(runPath), "run");
			record.set("regression_check", result);
			write(runPath, record);
		});
		return result;
	}

	/**
	 * Returns the coverage required before a previously observed failure may proceed to formal certification.
	 *
	 * @param observations the observations for this signature under the current assembled prompt route
	 * @return the required count of comparable non-certifying stress trials
	 */
	public static int stressTrialCount(int observations)
	{
		if (observations <= 1)
			return INITIAL_FAILURE_STRESS_TRIALS;
		if (observations == 2)
			return RECURRENT_FAILURE_STRESS_TRIALS;
		return REPEATED_FAILURE_STRESS_TRIALS;
	}

	/**
	 * Returns the shared stress coverage needed by every active signature in one candidate check.
	 *
	 * @param runPath the prepared regression-check record
	 * @return the largest required active-signature coverage, or zero when no signature is active
	 * @throws IOException if the candidate state is malformed
	 */
	public static int activeFailureStressTrials(Path runPath) throws IOException
	{
		ObjectNode run = object(read(runPath), "regression-check run");
		if (!run.has("failure_signature_state"))
			return 0;
		Path statePath = path(text(run, "failure_signature_state", runPath));
		if (!Files.isRegularFile(statePath, NOFOLLOW_LINKS))
			return 0;
		String route = Identity.candidateRevision(run, runPath);
		int result = 0;
		ObjectNode state = object(read(statePath), "candidate state");
		for (JsonNode node : array(state.path("failure_signatures"), "failure signatures", statePath))
		{
			ObjectNode signature = object(node, "failure signature");
			if (signature.has("cleared_by_certification"))
				continue;
			int observations = 0;
			if (signature.path("coverage_route_sha256").asString().equals(route))
				observations = signature.path("coverage_observations").asInt();
			result = Math.max(result, stressTrialCount(observations));
		}
		return result;
	}

	/**
	 * Selects suite scenarios whose unresolved failure signatures require repeated evidence.
	 *
	 * @param runPath identifies the retained regression-check record
	 * @param suite supplies the durable scenarios in their required order
	 * @return the scenarios with unresolved failure signatures
	 * @throws IOException if the retained candidate state cannot be read
	 */

	private static List<ObjectNode> activeFailureCases(Path runPath, ObjectNode suite) throws IOException
	{
		ObjectNode run = object(read(runPath), "regression-check run");
		if (!run.has("failure_signature_state"))
			return List.of();
		Path statePath = path(text(run, "failure_signature_state", runPath));
		if (!Files.isRegularFile(statePath, NOFOLLOW_LINKS))
			return List.of();
		ObjectNode state = object(read(statePath), "candidate state");
		Set<String> activeCaseIds = new HashSet<>();
		for (JsonNode node : array(state.path("failure_signatures"), "failure signatures", statePath))
		{
			ObjectNode signature = object(node, "failure signature");
			if (signature.has("cleared_by_certification"))
				continue;
			activeCaseIds.add(text(object(signature.path("latest_case"), "latest failure case"), "case_id", statePath));
		}
		List<ObjectNode> result = new ArrayList<>();
		for (JsonNode node : array(suite.path("cases"), "cases", runPath))
		{
			ObjectNode testCase = object(node, "test case");
			if (activeCaseIds.contains(text(testCase, "id", runPath)))
				result.add(testCase);
		}
		return result;
	}

	/**
	 * Adds completed active-failure stress evidence to a regression-check summary.
	 *
	 * @param json the mapper that classifies failure signatures
	 * @param result receives the stress summary and any resulting failure state
	 * @param runPath identifies the retained check record
	 * @param cases identifies the stressed scenarios
	 * @param trials identifies the completed stress trials
	 * @param workspace identifies the retained stress workspace
	 * @param plannedRepetitions records the required stress-wave count
	 * @throws IOException if retained trial evidence cannot be read
	 */

	private static void appendActiveFailureStressResult(JsonMapper json, ObjectNode result, Path runPath,
		List<ObjectNode> cases,
		List<Integer> trials, Path workspace, int plannedRepetitions) throws IOException
	{
		ObjectNode run = object(read(runPath), "regression-check run");
		Map<String, ObjectNode> outcomes = outcomes(run, runPath, trials);
		ObjectNode stress = result.putObject("active_failure_stress");
		stress.put("planned_repetitions", plannedRepetitions);
		stress.put("completed_repetitions", trials.size());
		stress.put("workspace", workspace.toString());
		ArrayNode caseIds = stress.putArray("case_ids");
		ArrayNode failures = array(result.path("failure_signatures"), "failure signatures", runPath);
		ArrayNode infrastructureFailures = result.putArray("infrastructure_failures");
		boolean incomplete = false;
		for (ObjectNode testCase : cases)
		{
			String caseId = text(testCase, "id", runPath);
			caseIds.add(caseId);
			for (int trial : trials)
			{
				ObjectNode outcome = outcomes.get(trial + "/" + caseId);
				if (outcome == null)
				{
					incomplete = true;
					appendInfrastructureFailure(infrastructureFailures, caseId, trial, "retained testcase outcome",
						"outcome absent");
					continue;
				}
				AttemptStatus status = AttemptStatus.parse(text(outcome, "status", runPath));
				if (status == AttemptStatus.FAILED)
					FailureClassifier.appendFailureSignatures(json, failures, outcome, testCase, runPath);
				else if (status != AttemptStatus.PASSED)
				{
					incomplete = true;
					appendInfrastructureFailure(infrastructureFailures, caseId, trial, "retained testcase outcome",
						status.name().toLowerCase(java.util.Locale.ROOT));
				}
			}
		}
		if (incomplete)
		{
			result.put("status", "incomplete");
			result.put("infrastructure_failure", true);
			stress.put("status", "incomplete");
		}
		else if (!failures.isEmpty())
		{
			result.put("status", "failed");
			stress.put("status", "failed");
		}
		else
			stress.put("status", "passed");
	}

	/**
	 * Indicates whether a completed stress wave retained a failed active scenario.
	 *
	 * @param runPath identifies the retained regression-check record
	 * @param cases identifies the active scenarios
	 * @param trials identifies the completed stress trials
	 * @return {@code true} when one active scenario failed in the completed wave
	 * @throws IOException if retained trial evidence cannot be read
	 */

	private static boolean hasFailedStressWave(Path runPath, List<ObjectNode> cases, List<Integer> trials)
		throws IOException
	{
		Set<String> caseIds = new HashSet<>();
		for (ObjectNode testCase : cases)
			caseIds.add(text(testCase, "id", runPath));
		for (JsonNode node : attempts(object(read(runPath), "regression-check run"), runPath))
		{
			ObjectNode attempt = object(node, "attempt");
			if (trials.contains(attempt.path("trial").asInt()) && caseIds.contains(text(attempt, "case_id", runPath)) &&
				AttemptStatus.parse(text(attempt, "status", runPath)) == AttemptStatus.FAILED)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Projects retained baseline outcomes into a non-certifying regression-check result.
	 *
	 * @param json the mapper that creates and classifies the retained summary
	 * @param runPath identifies the retained evaluation record
	 * @param suite supplies every scenario that must have one baseline outcome
	 * @param trials identifies the baseline trials to summarize
	 * @param workspace identifies the retained baseline workspace
	 * @return the passed, failed, or incomplete regression-check summary
	 * @throws IOException if retained trial evidence cannot be read
	 */

	public static ObjectNode summarize(JsonMapper json, Path runPath, ObjectNode suite, List<Integer> trials,
		Path workspace)
		throws IOException
	{
		ObjectNode run = object(read(runPath), "run");
		Map<String, ObjectNode> outcomes = outcomes(run, runPath, trials);
		ObjectNode result = json.createObjectNode();
		result.put("classification", "non_certifying_regression_check");
		ArrayNode sweepTrials = result.putArray("sweep_trials");
		trials.forEach(sweepTrials::add);
		result.put("workspace", workspace.toString());
		ArrayNode failures = result.putArray("failure_signatures");
		ArrayNode infrastructureFailures = result.putArray("infrastructure_failures");
		boolean incomplete = false;
		for (JsonNode node : array(suite.path("cases"), "cases", runPath))
		{
			ObjectNode testCase = object(node, "test case");
			String caseId = text(testCase, "id", runPath);
			for (int trial : trials)
			{
				ObjectNode outcome = outcomes.get(trial + "/" + caseId);
				if (outcome == null)
				{
					incomplete = true;
					appendInfrastructureFailure(infrastructureFailures, caseId, trial, "retained testcase outcome",
						"outcome absent");
					continue;
				}
				AttemptStatus status = AttemptStatus.parse(text(outcome, "status", runPath));
				if (status == AttemptStatus.FAILED)
					FailureClassifier.appendFailureSignatures(json, failures, outcome, testCase, runPath);
				else if (status != AttemptStatus.PASSED)
				{
					incomplete = true;
					appendInfrastructureFailure(infrastructureFailures, caseId, trial, "retained testcase outcome",
						status.name().toLowerCase(java.util.Locale.ROOT));
				}
			}
		}
		String status = "passed";
		if (incomplete)
			status = "incomplete";
		else if (!failures.isEmpty())
			status = "failed";
		result.put("status", status);
		result.put("completed_at_epoch_millis", System.currentTimeMillis());
		result.put("infrastructure_failure", incomplete);
		return result;
	}

	/**
	 * Indexes retained attempt outcomes by trial and scenario identity.
	 *
	 * @param run supplies the retained attempt ledger reference
	 * @param runPath identifies the evaluation record
	 * @param trials limits the indexed outcomes to the requested trials
	 * @return the indexed retained outcomes
	 * @throws IOException if the attempt ledger cannot be read
	 */

	private static Map<String, ObjectNode> outcomes(ObjectNode run, Path runPath, List<Integer> trials)
		throws IOException
	{
		Map<String, ObjectNode> outcomes = new HashMap<>();
		for (JsonNode entry : attempts(run, runPath))
		{
			ObjectNode attempt = object(entry, "attempt");
			if (trials.contains(attempt.path("trial").asInt()))
				outcomes.put(attempt.path("trial").asInt() + "/" + text(attempt, "case_id", runPath), attempt);
		}
		return outcomes;
	}

	/**
	 * Adds one missing or nonterminal scenario outcome to a result summary.
	 *
	 * @param failures receives the unavailable-outcome detail
	 * @param caseId identifies the affected scenario
	 * @param trial identifies the affected trial
	 * @param boundary identifies the unavailable evidence boundary
	 * @param result records the observed unavailable state
	 */

	private static void appendInfrastructureFailure(ArrayNode failures, String caseId, int trial, String boundary,
		String result)
	{
		failures.addObject().put("case_id", caseId).put("trial", trial).put("boundary", boundary).
			put("result", result);
	}
}
