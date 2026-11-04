package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Recognizes the placeholders that a sandbox lays over files that a session may not read.
 */
final class SandboxPlaceholders
{
	/**
	 * Returns {@code true} if a path is a placeholder.
	 *
	 * @param path       the path to check
	 * @param attributes the attributes of {@code path}, read without following symbolic links
	 * @param nullDevice the null device, which a sandbox mounts over each file that a session may not read
	 * @return {@code true} if {@code path} is {@code nullDevice} itself. A symbolic link is never a placeholder, and
	 *   nothing is when {@code nullDevice} does not exist, as on Windows.
	 * @throws IOException if the file system cannot be read
	 */
	static boolean isPlaceholder(Path path, BasicFileAttributes attributes, Path nullDevice) throws IOException
	{
		// Windows has no null device in its file system, so no placeholder there.
		return !attributes.isSymbolicLink() && Files.exists(nullDevice, LinkOption.NOFOLLOW_LINKS) &&
			Files.isSameFile(path, nullDevice);
	}

	/**
	 * Prevents instantiation.
	 */
	private SandboxPlaceholders()
	{
	}
}
