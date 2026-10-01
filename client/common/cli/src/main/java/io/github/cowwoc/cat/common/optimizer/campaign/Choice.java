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
	 * Start a new measurement identity under the changed conditions.
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
	 * Run the paused logical trial again after an infrastructure failure, or calibrate again.
	 */
	RETRY,
	/**
	 * Cancel the campaign, keeping the pending decision for a later resume.
	 */
	STOP,
	/**
	 * Continue sampling a category past its review checkpoint, up to a new review point.
	 */
	MORE_EVIDENCE,
	/**
	 * Start a development round at a review checkpoint.
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
	 * Deliver a certified candidate that is proven worse than the incumbent in some category.
	 */
	ACCEPT_TRADE_OFF,
	/**
	 * Keep the incumbent instead of a certified candidate that is proven worse in some category.
	 */
	RETAIN_INCUMBENT,
	/**
	 * Deliver the working candidate labelled {@code not certified}.
	 */
	ACCEPT_WITHOUT_CERTIFICATION
}
