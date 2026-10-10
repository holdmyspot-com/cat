package io.github.cowwoc.cat.common.optimizer.port;

/**
 * The kind of work that a nested session performs.
 */
public enum SessionRole
{
	/**
	 * Runs one logical trial's turn sequence against a staged candidate.
	 */
	TRIAL,
	/**
	 * Judges one trial's evidence against the frozen rubric.
	 */
	JUDGE,
	/**
	 * Decides whether a failed development trial left the user without the turn's result.
	 */
	TRIAGE,
	/**
	 * Edits a copy of the working candidate in response to failure evidence.
	 */
	PROMPT_AUTHOR,
	/**
	 * Inspects the prompt system and proposes a contract.
	 */
	CONTRACT_AUTHOR
}
