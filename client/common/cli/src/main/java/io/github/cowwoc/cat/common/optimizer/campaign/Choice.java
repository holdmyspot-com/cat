package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * An answer that the user can give to a pending decision.
 */
public enum Choice
{
	/**
	 * The user restored a changed measurement condition; the affected trial runs again with the same instance.
	 */
	RESTORE,
	/**
	 * Start recording results under the changed measurement conditions, keeping earlier results historical.
	 */
	NEW_IDENTITY,
	/**
	 * Keep a changed loader configuration and close this campaign so that a new one can start.
	 */
	NEW_CAMPAIGN,
	/**
	 * Continue: resume a cancelled campaign, or continue after an unowned change was dealt with.
	 */
	RESUME,
	/**
	 * Run the paused trial again after a run ended without a compliance result, or retry trial-cap calibration.
	 */
	RETRY,
	/**
	 * Cancel the campaign, keeping the pending decision for a later resume.
	 */
	STOP,
	/**
	 * Continue sampling a cell (category and trial profile) until a newly chosen outcome count triggers another review.
	 */
	MORE_EVIDENCE,
	/**
	 * Start author attempts to improve failing cells when the sampled-outcome count triggers a review.
	 */
	TARGETED_OPTIMIZATION,
	/**
	 * Deliver a qualifying attempt's candidate labelled {@code improved, not certified} and end optimization.
	 */
	KEEP_IMPROVEMENT,
	/**
	 * Return to development after a qualifying attempt instead of keeping it.
	 */
	OPTIMIZE_FURTHER,
	/**
	 * Deliver a certified candidate that is proven worse than the original prompt system in some cell.
	 */
	ACCEPT_TRADE_OFF,
	/**
	 * Keep the original prompt system instead of a certified candidate that is proven worse in some cell.
	 */
	RETAIN_INCUMBENT,
	/**
	 * Deliver the working candidate labelled {@code not certified}.
	 */
	ACCEPT_WITHOUT_CERTIFICATION,
	/**
	 * Continue development within the confirmed edit authority, leaving the warned edit unmade.
	 */
	USE_AUTHORIZED_SOURCE,
	/**
	 * Select a numbered resolution of the first unanswered conflict and revise once every conflict has an answer.
	 */
	RESOLUTION,
	/**
	 * Run more reproduction rounds for the failure groups that did not reproduce, each with new development values.
	 */
	MORE_ROUNDS,
	/**
	 * Leave the failure groups that did not reproduce, and start a new certification attempt with the working
	 * candidate once it meets the readiness gate.
	 */
	NEW_ATTEMPT
}
