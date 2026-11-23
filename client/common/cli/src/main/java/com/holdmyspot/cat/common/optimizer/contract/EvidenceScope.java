package com.holdmyspot.cat.common.optimizer.contract;

/**
 * What the judge of a judged assertion reads. Each scope is judged in its own session.
 */
public enum EvidenceScope
{
	/**
	 * What the agent says: the judge reads the test case and the trial's replies, and no evidence section, built-in
	 * tool result, project directory or transcript. Its verdicts are recorded and reused for identical replies.
	 */
	RESPONSE,
	/**
	 * What the agent does: the judge reads the replies, the evidence section, the built-in tool results, the trial's
	 * project and the transcript. Its verdicts are never recorded, because that evidence differs in every trial.
	 */
	ACTIONS
}
