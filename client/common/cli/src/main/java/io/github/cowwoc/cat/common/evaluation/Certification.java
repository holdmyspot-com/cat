package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.file.Path;

import tools.jackson.databind.node.ObjectNode;

/**
 * Advances a certification evaluation through semantic controls and complete-suite batches to a terminal decision.
 */
public final class Certification
{
	/**
	 * Provides lock-bound evaluator operations that certification progression orders without owning their persistence.
	 */
	public interface Access
	{
		/**
		 * Publishes that semantic controls own the active evaluation workspace.
		 *
		 * @throws IOException if the active status cannot be retained
		 */
		void publishSemanticControls() throws IOException;

		/**
		 * Verifies semantic controls before certification can select a trial batch.
		 *
		 * @throws IOException if a control cannot establish the required prerequisite
		 */
		void verifySemanticControls() throws IOException;

		/**
		 * Verifies that the active evaluation still owns its workspace.
		 *
		 * @throws IOException if the active owner is no longer valid
		 */
		void verifyHeartbeat() throws IOException;

		/**
		 * Publishes that certification is selecting the next complete-suite batch.
		 *
		 * @throws IOException if the active status cannot be retained
		 */
		void publishBatchSelection() throws IOException;

		/**
		 * Returns the current retained certification decision.
		 *
		 * @return the current decision
		 * @throws IOException if retained decision evidence cannot be read
		 */
		ObjectNode decide() throws IOException;

		/**
		 * Returns an incomplete retained batch that must resume before a fresh batch can be allocated.
		 *
		 * @return the retained batch, or {@code null} when none remains
		 * @throws IOException if retained batch evidence cannot be read
		 */
		Path resumableBatch() throws IOException;

		/**
		 * Resumes one incomplete complete-suite batch.
		 *
		 * @param batch the retained batch workspace
		 * @return the decision after recording its remaining outcomes
		 * @throws IOException if the batch cannot retain terminal evidence
		 */
		ObjectNode resume(Path batch) throws IOException;

		/**
		 * Returns the number of unallocated certification trials allowed by the suite contract.
		 *
		 * @return the remaining trial capacity
		 * @throws IOException if the retained record cannot be read
		 */
		int remainingTrials() throws IOException;

		/**
		 * Returns the capacity available to one fresh complete-suite batch.
		 *
		 * @return the positive batch capacity
		 */
		int maximumConcurrency();

		/**
		 * Runs one fresh complete-suite batch with the supplied bounded trial count.
		 *
		 * @param trialCount the positive number of trials to allocate
		 * @return the decision after recording the batch outcomes
		 * @throws IOException if the batch cannot retain terminal evidence
		 */
		ObjectNode runFresh(int trialCount) throws IOException;
	}

	/**
	 * Prevents construction of this static policy owner.
	 */
	private Certification()
	{
	}

	/**
	 * Advances one certification evaluation until its retained decision is terminal.
	 *
	 * @param access the lock-bound evaluator operations
	 * @return the terminal retained decision
	 * @throws IOException if a required control, batch, or active ownership check fails
	 */
	public static ObjectNode run(Access access) throws IOException
	{
		access.publishSemanticControls();
		access.verifySemanticControls();
		access.verifyHeartbeat();
		access.publishBatchSelection();
		ObjectNode decision = access.decide();
		while (decision.path("overall_decision").asString().equals("RUNNING"))
		{
			Path resumable = access.resumableBatch();
			if (resumable != null)
			{
				decision = access.resume(resumable);
				access.verifyHeartbeat();
				continue;
			}
			int remainingTrials = access.remainingTrials();
			if (remainingTrials < 1)
				return access.decide();
			int batchSize = Math.min(access.maximumConcurrency(), remainingTrials);
			decision = access.runFresh(batchSize);
			access.verifyHeartbeat();
		}
		return decision;
	}
}
