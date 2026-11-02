package com.holdmyspot.cat.common.optimizer.workspace;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Confirms that this process can write a directory, such as the directory in which Git registers a new linked
 * worktree.
 * <p>
 * A sandbox can deny writing a directory whose file permissions allow it, so only an attempt to write it shows
 * whether a later write will succeed.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
@FunctionalInterface
public interface WritableDirectories
{
	/**
	 * Creates a directory if it does not exist, and confirms that this process can create an entry in it.
	 *
	 * @param directory the directory's absolute path
	 * @throws IOException if this process cannot create the directory or an entry in it
	 */
	void requireWritable(Path directory) throws IOException;
}
