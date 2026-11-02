package com.holdmyspot.cat.common.optimizer.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The {@link WritableDirectories} that create and delete an empty directory in the directory to confirm it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FileSystemWritableDirectories implements WritableDirectories
{
	/**
	 * Creates the check.
	 */
	public FileSystemWritableDirectories()
	{
	}

	@Override
	public void requireWritable(Path directory) throws IOException
	{
		Files.createDirectories(directory);
		Files.delete(Files.createTempDirectory(directory, "cat-write-check-"));
	}
}
