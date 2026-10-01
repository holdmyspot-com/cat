package io.github.cowwoc.cat.common.optimizer.inventory;

/**
 * The reason an operation is forbidden.
 */
public enum Limit
{
	/**
	 * The resolved target lies in the project but outside every prompt source that the contract names.
	 */
	OUTSIDE_PROMPT_SOURCES,
	/**
	 * The resolved target is loader configuration, which decides which prompt files load and stays fixed for the
	 * campaign. The user prepares a needed loader change before a new campaign.
	 */
	LOADER_CONFIGURATION_FIXED,
	/**
	 * The modes of existing files stay fixed.
	 */
	MODE_FIXED,
	/**
	 * Symbolic links and their destinations stay fixed.
	 */
	LINK_FIXED,
	/**
	 * The resolved target lies outside the project and the user has not authorized edits to it.
	 */
	EXTERNAL_TARGET_READ_ONLY,
	/**
	 * The resolved target lies outside the project, where only content edits to authorized existing files are
	 * allowed: never a create, rename or delete. Authorizing the target cannot lift this limit.
	 */
	EXTERNAL_STRUCTURE_FIXED,
	/**
	 * The operation creates or deletes something other than an ordinary file, such as a named pipe.
	 */
	NOT_ORDINARY_FILE,
	/**
	 * The attempt changed one resolved target differently through two or more paths.
	 */
	AMBIGUOUS_EDIT,
	/**
	 * The path cannot be resolved because the symbolic links on it form a cycle.
	 */
	LINK_CYCLE
}
