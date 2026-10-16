package io.github.cowwoc.cat.common.optimizer.contract;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;

/**
 * The files that a session's workspace receives on top of its staged source before the first turn.
 */
public interface WorkspaceFiles
{
	/**
	 * Indicates whether the workspace receives no file.
	 *
	 * @return {@code true} if there are no files
	 */
	boolean isEmpty();

	/**
	 * Writes every file below a directory, creating the directories that it needs. Nothing is replaced: a path that
	 * exists already is refused.
	 *
	 * @param directory the directory that stands for the workspace root
	 * @throws NullPointerException       if {@code directory} is null
	 * @throws FileAlreadyExistsException if a file's path exists already
	 * @throws IOException                if a file cannot be written
	 */
	void write(Path directory) throws IOException;
}
