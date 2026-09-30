package io.github.cowwoc.cat.common.optimizer.runner;

/**
 * Identifies whether a sterile input carries authority or only inert evidence.
 */
public enum SterileWorkspaceInputRole
{
	/**
	 * Guidance that governs the nested native call.
	 */
	AUTHORITY,
	/**
	 * Content supplied for observation without granting instruction authority.
	 */
	INERT_EVIDENCE
}
