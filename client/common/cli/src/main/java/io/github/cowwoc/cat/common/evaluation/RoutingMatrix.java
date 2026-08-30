package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import io.github.cowwoc.cat.common.PromptEvaluation;
import io.github.cowwoc.cat.common.evaluation.RoutingEvidence.CandidateRoute;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes a routing-only diagnostic matrix and publishes its retained outcome.
 */
public final class RoutingMatrix
{
	/**
	 * Supplies coordinator-owned record and execution operations.
	 */
	public interface Access
	{
		/**
		 * Claims the routing matrix's single non-certifying trial.
		 *
		 * @param runPath the persistent routing-matrix record
		 * @return its claimed trial numbers
		 * @throws IOException if the trial cannot be claimed
		 */
		List<Integer> claimTrial(Path runPath) throws IOException;

		/**
		 * Executes the claimed routing cases through the general bounded scheduler.
		 *
		 * @param runPath the persistent routing-matrix record
		 * @param suitePath the immutable routing suite
		 * @param cases the selected routing cases
		 * @param trials the claimed trial numbers
		 * @param workspace the routing-matrix trial workspace
		 * @param plan the typed execution plan
		 * @param maximumConcurrency the shared worker capacity
		 * @param runner the typed evaluator runner
		 * @return retained executor evidence for the selected cases
		 * @throws IOException if execution cannot retain case evidence
		 */
		List<Evidence> execute(Path runPath, Path suitePath, List<ObjectNode> cases, List<Integer> trials,
			Path workspace, ObjectNode plan, int maximumConcurrency, HarnessEvaluationRunner runner) throws IOException;

		/**
		 * Summarizes one completed routing-matrix trial.
		 *
		 * @param runPath the persistent routing-matrix record
		 * @param suite the immutable routing suite
		 * @param trials the claimed trial numbers
		 * @param workspace the routing-matrix trial workspace
		 * @return the retained routing-matrix summary
		 * @throws IOException if retained evidence is incomplete
		 */
		ObjectNode result(Path runPath, ObjectNode suite, List<Integer> trials, Path workspace) throws IOException;

		/**
		 * Publishes the completed routing-matrix summary while retaining record locking.
		 *
		 * @param runPath the persistent routing-matrix record
		 * @param result the completed summary
		 * @throws IOException if the record cannot be published
		 */
		void publish(Path runPath, ObjectNode result) throws IOException;

		/**
		 * Records one completed routing assertion for a claimed testcase.
		 *
		 * @param runPath the persistent routing-matrix record
		 * @param evidence the executor evidence to retain
		 * @param status the routing assertion result
		 * @param check the retained routing assertion evidence
		 * @throws IOException if the result cannot be retained
		 */
		void record(Path runPath, Evidence evidence, AttemptStatus status, Path check) throws IOException;
	}

	/**
	 * Carries retained executor evidence for one routing-matrix case.
	 *
	 * @param testCase the routing testcase contract
	 * @param caseId the stable testcase identifier
	 * @param trial the claimed whole-suite trial
	 * @param rawResult the retained executor result
	 */
	public record Evidence(ObjectNode testCase, String caseId, int trial, Path rawResult)
	{
	}

	private static volatile Access access;
	/**
	 * Prevents instantiation.
	 */
	private RoutingMatrix()
	{
	}

	/**
	 * Executes and summarizes one prepared routing matrix.
	 *
	 * @param json the mapper that creates route-delivery checks
	 * @param runPath the prepared routing-matrix record
	 * @param workspaceRoot the owned evaluator workspace
	 * @param planPath the typed execution plan
	 * @param maximumConcurrency the shared worker capacity
	 * @param runner the runner that retains each executor result
	 * @return the retained routing-matrix summary
	 * @throws IOException if the retained evidence is incomplete
	 */
	public static ObjectNode execute(JsonMapper json, Path runPath, Path workspaceRoot, Path planPath,
		int maximumConcurrency,
		HarnessEvaluationRunner runner) throws IOException
	{
		List<Integer> trials = access().claimTrial(runPath);
		ObjectNode run = PromptEvaluation.object(PromptEvaluation.read(runPath), "run");
		Path suitePath = PromptEvaluation.path(PromptEvaluation.text(run, "suite", runPath));
		ObjectNode suite = SuiteContract.read(suitePath);
		ObjectNode plan = PromptEvaluation.object(PromptEvaluation.read(planPath), "execution plan");
		Path trialWorkspace = workspaceRoot.resolve("routing-matrix-trial-" + trials.getFirst());
		List<ObjectNode> cases = new ArrayList<>();
		for (JsonNode node : PromptEvaluation.array(suite.path("cases"), "cases", suitePath))
			cases.add(PromptEvaluation.object(node, "routing testcase"));
		List<Evidence> evidence = access().execute(runPath, suitePath, cases, trials, trialWorkspace, plan,
			maximumConcurrency, runner);
		recordVerdicts(json, runPath, evidence);
		ObjectNode result = access().result(runPath, suite, trials, trialWorkspace);
		result.put("classification", "non_certifying_routing_matrix");
		access().publish(runPath, result);
		return result;
	}

	/**
	 * Records the deterministic route result for each completed executor response.
	 *
	 * @param json the mapper that creates route-delivery checks
	 * @param runPath the persistent routing-matrix record
	 * @param evidence the completed executor evidence
	 * @throws IOException if retained routing evidence cannot be interpreted or recorded
	 */
	private static void recordVerdicts(JsonMapper json, Path runPath, List<Evidence> evidence) throws IOException
	{
		CandidateRoute candidate = RoutingEvidence.candidateRoute(
			PromptEvaluation.object(PromptEvaluation.read(runPath), "routing matrix record"), runPath);
		for (Evidence item : evidence)
		{
			Path result = item.rawResult().getParent().resolve("routing-matrix-check.json");
			ObjectNode check = RoutingEvidence.routeDeliveryCheck(json, item.testCase(), item.rawResult(),
				"routing-only", candidate);
			PromptEvaluation.write(result, check);
			ObjectNode assertions = PromptEvaluation.object(check.path("assertions"), "routing matrix assertions");
			String outcome = PromptEvaluation.text(PromptEvaluation.object(assertions.path("routing-only"),
				"routing-only assertion"), "status", result);
			AttemptStatus status = AttemptStatus.FAILED;
			if (outcome.equals("passed"))
				status = AttemptStatus.PASSED;
			access().record(runPath, item, status, result);
		}
	}

	/**
	 * Registers coordinator-owned record and execution operations.
	 *
	 * @param access the record and execution operations
	 */
	public static void configure(Access access)
	{
		RoutingMatrix.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Returns the configured record and execution operations.
	 *
	 * @return the record and execution operations
	 * @throws AssertionError if evaluator composition did not configure routing-matrix execution
	 */
	private static Access access()
	{
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure routing-matrix access");
		return result;
	}
}
