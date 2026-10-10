package io.github.cowwoc.cat.common.optimizer.inventory;

/**
 * A kind of change that a prompt author attempt or a needed edit makes to one path.
 * <p>
 * A rename is a {@link #DELETE} of the old path and a {@link #CREATE} of the new one.
 */
public enum Operation
{
	/**
	 * Replaces the bytes of an existing regular file.
	 */
	EDIT_CONTENT,
	/**
	 * Creates a file where none existed.
	 */
	CREATE,
	/**
	 * Deletes an existing file.
	 */
	DELETE,
	/**
	 * Changes the mode of an existing file.
	 */
	CHANGE_MODE,
	/**
	 * Creates, deletes or retargets a symbolic link.
	 */
	CHANGE_LINK
}
