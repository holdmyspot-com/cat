package com.holdmyspot.cat.common;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Opens the channel through which a lock file is locked.
 */
@FunctionalInterface
public interface LockFileOpener
{
	/**
	 * Opens a lock file.
	 *
	 * @param file the lock file
	 * @return the open channel, which the caller owns
	 * @throws IOException if the file cannot be opened
	 */
	FileChannel open(Path file) throws IOException;
}
