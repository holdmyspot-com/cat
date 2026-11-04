package com.holdmyspot.cat.common.updater.cli.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Renames a directory within one file system, as setup does when it replaces the installed Java runtime or updater.
 */
@FunctionalInterface
public interface DirectoryMover
{
	/**
	 * Renames directories atomically, failing when the target exists.
	 */
	DirectoryMover ATOMIC = (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);

	/**
	 * Renames a directory.
	 *
	 * @param source the existing directory
	 * @param target its new path, which does not exist
	 * @throws IOException if the directory cannot be renamed, for example because a running program holds a file in it
	 */
	void move(Path source, Path target) throws IOException;
}
