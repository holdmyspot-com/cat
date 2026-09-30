package io.github.cowwoc.cat.common.optimizer.runner;

/**
 * Identifies why a native call uses an isolated sterile workspace.
 */
public enum SterileWorkspaceKind
{
	/**
	 * Derives a contract from inert source evidence.
	 */
	CONTRACT_DERIVATION,
	/**
	 * Validates the candidate generator before admission.
	 */
	GENERATOR_VALIDATION,
	/**
	 * Evaluates a frozen candidate outside the live worktree.
	 */
	EVALUATOR
}
