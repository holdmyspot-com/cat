package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selects and records the next durable transition for one prompt candidate.
 *
 * <p>
 * This lifecycle owns controller sequencing and candidate-state changes. Its operations port separates regression,
 * promotion, and formal-evaluation work from the decision that selects the next transition.
 */
public final class CandidateLifecycle
{
	/**
	 * Supplies coordinator-owned validation to the candidate lifecycle.
	 */
	public interface Access
	{
		/**
		 * Validates the durable candidate-state record location.
		 *
		 * @param temporaryRoot the CAT work temporary directory
		 * @param candidateStatePath the durable candidate-state record
		 * @throws IOException if the record location is unsafe
		 */
		void validateCandidateState(Path temporaryRoot, Path candidateStatePath) throws IOException;

		/**
		 * Validates the shared harness-concurrency limit.
		 *
		 * @param maximumConcurrency the requested shared capacity
		 * @throws IOException if the requested capacity is outside the supported range
		 */
		void validateMaximumConcurrency(int maximumConcurrency) throws IOException;
	}

	/**
	 * Performs the evaluator operations that a selected candidate transition requires.
	 */
	public interface Operations
	{
		/**
		 * Runs the current candidate's regression check.
		 *
		 * @param request the candidate inputs and evaluator runner
		 * @return the retained regression-check receipt
		 * @throws IOException if the check cannot retain its result
		 */
		ObjectNode regressionCheck(Request request) throws IOException;

		/**
		 * Runs the promoted candidate's formal evaluation while the supplied controller remains active.
		 *
		 * @param request the candidate inputs and evaluator runner
		 * @param controller the controller that owns the candidate transition
		 * @return the retained formal-evaluation receipt
		 * @throws IOException if formal evaluation cannot retain a terminal decision
		 */
		ObjectNode certifyPromoted(Request request, CandidateController controller) throws IOException;

		/**
		 * Resumes the exact formal evaluation retained in candidate state.
		 *
		 * @param candidateStatePath the state that retains the pending evaluation
		 * @param maximumConcurrency the shared worker capacity
		 * @return the resumed formal-evaluation receipt
		 * @throws IOException if the retained evaluation cannot be resumed safely
		 */
		ObjectNode resumePendingCertification(Path candidateStatePath, int maximumConcurrency) throws IOException;

		/**
		 * Publishes a passed regression check as the candidate eligible for formal evaluation.
		 *
		 * @param checkRunPath the passed regression-check record
		 * @param candidateStatePath the candidate state that will own the promotion
		 * @return the promotion receipt
		 * @throws IOException if the check cannot be promoted
		 */
		ObjectNode promote(Path checkRunPath, Path candidateStatePath) throws IOException;
	}

	/**
	 * Groups the immutable inputs required by one candidate transition.
	 *
	 * @param temporaryRoot the CAT work temp directory
	 * @param candidateStatePath the durable candidate state
	 * @param suitePath the visible suite
	 * @param promptPath the candidate prompt
	 * @param harness the selected harness
	 * @param contextPath the validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent result directory
	 * @param maximumConcurrency the shared worker capacity
	 * @param evaluationRunner the runner that executes evaluator requests
	 */
	public record Request(Path temporaryRoot, Path candidateStatePath, Path suitePath, Path promptPath, String harness,
		Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory, int maximumConcurrency,
		HarnessEvaluationRunner evaluationRunner)
	{
	}

	private final JsonMapper json;
	private final Access access;
	private final Operations operations;

	/**
	 * Creates a candidate lifecycle with its evaluator operations.
	 *
	 * @param json the mapper that creates candidate-controller status records
	 * @param access the coordinator-owned validation access
	 * @param operations the evaluator operations selected at the composition boundary
	 */
	public CandidateLifecycle(JsonMapper json, Access access, Operations operations)
	{
		this.json = json;
		this.access = access;
		this.operations = operations;
	}

	/**
	 * Executes the only transition that the current candidate state permits.
	 *
	 * @param request the candidate inputs and evaluator runner
	 * @return the executed transition and its detailed receipt
	 * @throws IOException if retained state is malformed, stale evidence needs repair, or the selected operation fails
	 */
	public ObjectNode advance(Request request) throws IOException
	{
		Path candidateStatePath = request.candidateStatePath().toAbsolutePath().normalize();
		access.validateCandidateState(request.temporaryRoot(), candidateStatePath);
		Request normalizedRequest = new Request(request.temporaryRoot(), candidateStatePath, request.suitePath(),
			request.promptPath(), request.harness(), request.contextPath(), request.runner(), request.buildPlanPath(),
			request.retainedDirectory(), request.maximumConcurrency(), request.evaluationRunner());
		try (CandidateController controller = CandidateController.start(json, access, candidateStatePath))
		{
			try
			{
				controller.identify(access, normalizedRequest.suitePath(), normalizedRequest.promptPath(),
					normalizedRequest.harness(),
					normalizedRequest.contextPath(), normalizedRequest.runner(), normalizedRequest.buildPlanPath(),
					normalizedRequest.maximumConcurrency());
				ObjectNode result = advanceOwned(normalizedRequest, controller);
				controller.complete();
				return result;
			}
			catch (IOException e)
			{
				controller.fail(e);
				throw e;
			}
		}
	}

	/**
	 * Executes a selected transition while its controller owns the complete lifecycle.
	 *
	 * @param request the normalized candidate inputs
	 * @param controller the active candidate controller
	 * @return the completed transition receipt
	 * @throws IOException if candidate state or the selected evaluator operation cannot complete safely
	 */
	private ObjectNode advanceOwned(Request request, CandidateController controller) throws IOException
	{
		Path candidateStatePath = request.candidateStatePath();
		if (!Files.exists(candidateStatePath))
		{
			CandidateState.initialize(json, candidateStatePath);
			controller.publish("running_regression_check");
			return recordTransition(candidateStatePath, CandidatePolicy.Transition.REGRESSION_CHECK,
				operations.regressionCheck(request));
		}
		ObjectNode state = CandidateState.read(candidateStatePath);
		if (CandidateState.hasPendingCertification(state))
		{
			controller.publish("resuming_formal_evaluation");
			return recordTransition(candidateStatePath, CandidatePolicy.Transition.CERTIFY_PROMOTED,
				operations.resumePendingCertification(candidateStatePath, request.maximumConcurrency()));
		}
		String staleCheckReason = CandidateState.staleLatestCheckReason(state, candidateStatePath,
			request.suitePath(), request.promptPath(), request.harness(), request.contextPath());
		if (staleCheckReason != null)
		{
			if (state.path("current_promotion").isObject())
			{
				controller.publish("refreshing_stale_promotion");
				CandidateState.invalidatePromotion(candidateStatePath, staleCheckReason);
			}
			controller.publish("running_regression_check");
			return recordTransition(candidateStatePath, CandidatePolicy.Transition.REGRESSION_CHECK,
				operations.regressionCheck(request));
		}
		List<String> uncovered = CandidateState.uncoveredLineageObligationKeys(state,
			candidateStatePath);
		boolean latestCheckPassed = CandidateState.latestCheckPassed(state, uncovered,
			candidateStatePath);
		CandidatePolicy.Transition transition = CandidatePolicy.select(state, uncovered,
			latestCheckPassed, candidateStatePath);
		if (transition == CandidatePolicy.Transition.REGRESSION_CHECK)
		{
			controller.publish("running_regression_check");
			return recordTransition(candidateStatePath, transition, operations.regressionCheck(request));
		}
		if (transition == CandidatePolicy.Transition.CERTIFY_PROMOTED)
		{
			String stalePromotionReason = CandidateState.stalePromotionReason(candidateStatePath,
				request.suitePath());
			if (stalePromotionReason != null)
			{
				controller.publish("refreshing_stale_promotion");
				CandidateState.invalidatePromotion(candidateStatePath, stalePromotionReason);
				controller.publish("running_regression_check");
				return recordTransition(candidateStatePath, CandidatePolicy.Transition.REGRESSION_CHECK,
					operations.regressionCheck(request));
			}
			return recordTransition(candidateStatePath, transition, operations.certifyPromoted(request, controller));
		}
		Path latestCheckPath = CandidateState.latestCheckPath(state, candidateStatePath);
		controller.publish("promoting_candidate");
		return recordTransition(candidateStatePath, transition, operations.promote(latestCheckPath, candidateStatePath));
	}

	/**
	 * Records a completed transition and returns the receipt that identifies it.
	 *
	 * @param candidateStatePath the state that owns the transition
	 * @param transition the transition selected by candidate policy
	 * @param transitionResult the detailed operation receipt
	 * @return the transition name and operation receipt
	 * @throws IOException if the state cannot record the transition
	 */
	private ObjectNode recordTransition(Path candidateStatePath, CandidatePolicy.Transition transition,
		ObjectNode transitionResult) throws IOException
	{
		return CandidateState.recordTransition(json, candidateStatePath, transition.command(),
			transition.recordField(), transitionResult);
	}
}
