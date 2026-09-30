package io.github.cowwoc.cat.common.optimizer.loading;

/**
 * Identifies the kind of source loaded into a prompt session.
 */
public enum SourceOrigin
{
	/**
 * The selected target prompt.
 */
	TARGET,
	/**
 * A project rule.
 */
	PROJECT_RULE,
	/**
 * A reusable skill.
 */
	SKILL,
	/**
 * An agent definition.
 */
	AGENT,
	/**
 * Any other declared source.
 */
	OTHER
}
