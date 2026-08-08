package io.github.cowwoc.cat.common.git.replay;

/**
 * A path's mode and blob identity used to validate a selected replay destination.
 *
 * @param mode the six-digit Git mode, or {@code 000000} when absent
 * @param blob the full Git blob ID, or forty zeroes when absent
 */
record PathState(String mode, String blob)
{
	/**
	 * Returns the state for a path absent from a Git revision.
	 *
	 * @return the absent path state
	 */
	static PathState absent()
	{
		return new PathState("000000", "0000000000000000000000000000000000000000");
	}
}
